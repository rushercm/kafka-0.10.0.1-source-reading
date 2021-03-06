/**
  * Licensed to the Apache Software Foundation (ASF) under one or more
  * contributor license agreements.  See the NOTICE file distributed with
  * this work for additional information regarding copyright ownership.
  * The ASF licenses this file to You under the Apache License, Version 2.0
  * (the "License"); you may not use this file except in compliance with
  * the License.  You may obtain a copy of the License at
  *
  * http://www.apache.org/licenses/LICENSE-2.0
  *
  * Unless required by applicable law or agreed to in writing, software
  * distributed under the License is distributed on an "AS IS" BASIS,
  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  * See the License for the specific language governing permissions and
  * limitations under the License.
  */

package kafka.server

import java.util.LinkedList
import java.util.concurrent.atomic._
import java.util.concurrent.locks.ReentrantReadWriteLock

import com.yammer.metrics.core.Gauge
import kafka.metrics.KafkaMetricsGroup
import kafka.utils.CoreUtils.{inReadLock, inWriteLock}
import kafka.utils._
import kafka.utils.timer._

import scala.collection._


/**
  * An operation whose processing needs to be delayed for at most the given delayMs. For example
  * a delayed produce operation could be waiting for specified number of acks; or
  * a delayed fetch operation could be waiting for a given number of bytes to accumulate.
  *
  * The logic upon completing a delayed operation is defined in onComplete() and will be called exactly once.
  * Once an operation is completed, isCompleted() will return true. onComplete() can be triggered by either
  * forceComplete(), which forces calling onComplete() after delayMs if the operation is not yet completed,
  * or tryComplete(), which first checks if the operation can be completed or not now, and if yes calls
  * forceComplete().
  *
  * A subclass of DelayedOperation needs to provide an implementation of both onComplete() and tryComplete().
  */
abstract class DelayedOperation(override val delayMs: Long) extends TimerTask with Logging {

  private val completed = new AtomicBoolean(false)

  /*
   * Force completing the delayed operation, if not already completed.
   * This function can be triggered when
   *
   * 1. The operation has been verified to be completable inside tryComplete()
   * 2. The operation has expired and hence needs to be completed right now
   *
   * Return true iff the operation is completed by the caller: note that
   * concurrent threads can try to complete the same operation, but only
   * the first thread will succeed in completing the operation and return
   * true, others will still return false
   */
  def forceComplete(): Boolean = {
    if (completed.compareAndSet(false, true)) {
      // cancel the timeout timer
      cancel()
      onComplete()
      true
    } else {
      false
    }
  }

  /**
    * Check if the delayed operation is already completed
    */
  def isCompleted(): Boolean = completed.get()

  /**
    * Call-back to execute when a delayed operation gets expired and hence forced to complete.
    */
  def onExpiration(): Unit

  /**
    * Process for completing an operation; This function needs to be defined
    * in subclasses and will be called exactly once in forceComplete()
    */
  def onComplete(): Unit

  /*
   * Try to complete the delayed operation by first checking if the operation
   * can be completed by now. If yes execute the completion logic by calling
   * forceComplete() and return true iff forceComplete returns true; otherwise return false
   *
   * This function needs to be defined in subclasses
   */
  def tryComplete(): Boolean

  /*
   * run() method defines a task that is executed on timeout
   */
  override def run(): Unit = {
    if (forceComplete())
      onExpiration()
  }
}

object DelayedOperationPurgatory {

  def apply[T <: DelayedOperation](purgatoryName: String,
                                   brokerId: Int = 0,
                                   purgeInterval: Int = 1000): DelayedOperationPurgatory[T] = {
    val timer = new SystemTimer(purgatoryName)
    new DelayedOperationPurgatory[T](purgatoryName, timer, brokerId, purgeInterval)
  }

}

/**
  * A helper purgatory class for bookkeeping delayed operations with a timeout, and expiring timed out operations.
  *
  * 管理DelayedOperation，处理到期的DelayedOperation，比如说 {@link DelayedProduce}
  *
  * 延迟任务的炼狱 DelayedOperationPurgatory （消费延迟任务）
  */
class DelayedOperationPurgatory[T <: DelayedOperation](purgatoryName: String,
                                                       timeoutTimer: Timer,
                                                       brokerId: Int = 0,
                                                       purgeInterval: Int = 1000,
                                                       reaperEnabled: Boolean = true)
  extends Logging with KafkaMetricsGroup {

  /* a list of operation watching keys */
  /**
    * 可以根据某个key获取到对应的Watchers（延迟任务队列）
    */
  private val watchersForKey = new Pool[Any, Watchers /* 表示一个DelayedOperation的集合（linkedList） */ ](Some((key: Any) => new Watchers(key)))

  private val removeWatchersLock = new ReentrantReadWriteLock()

  // the number of estimated total operations in the purgatory
  /** 这个operation下的任务总数 */
  private[this] val estimatedTotalOperations = new AtomicInteger(0)

  /* background thread expiring operations that have timed out */
  /** 它有两个功能，一个是推进时间轮表针，另一个是定期清理watchersForKey中已经完成的DelayedOperation，清理条件由
    * purgeInterval字段指定。在DelayedOperationPurgatory初始化时会启动此线程 */
  private val expirationReaper = new ExpiredOperationReaper()

  private val metricsTags = Map("delayedOperation" -> purgatoryName)

  newGauge(
    "PurgatorySize",
    new Gauge[Int] {
      def value = watched()
    },
    metricsTags
  )

  newGauge(
    "NumDelayedOperations",
    new Gauge[Int] {
      def value = delayed()
    },
    metricsTags
  )

  if (reaperEnabled)
    expirationReaper.start()

  /**
    * Check if the operation can be completed, if not watch it based on the given watch keys
    *
    * Note that a delayed operation can be watched on multiple keys. It is possible that
    * an operation is completed after it has been added to the watch list for some, but
    * not all of the keys. In this case, the operation is considered completed and won't
    * be added to the watch list of the remaining keys. The expiration reaper thread will
    * remove this operation from any watcher list in which the operation exists.
    *
    * @param operation the delayed operation to be checked
    * @param watchKeys keys for bookkeeping the operation
    * @return true iff the delayed operations can be completed by the caller
    */
  def tryCompleteElseWatch(operation: T, watchKeys: Seq[Any]): Boolean = {
    assert(watchKeys.size > 0, "The watch key list can't be empty")

    // The cost of tryComplete() is typically proportional to the number of keys. Calling
    // tryComplete() for each key is going to be expensive if there are many keys. Instead,
    // we do the check in the following way. Call tryComplete(). If the operation is not completed,
    // we just add the operation to all keys. Then we call tryComplete() again. At this time, if
    // the operation is still not completed, we are guaranteed that it won't miss any future triggering
    // event since the operation is already on the watcher list for all keys. This does mean that
    // if the operation is completed (by another thread) between the two tryComplete() calls, the
    // operation is unnecessarily added for watch. However, this is a less severe issue since the
    // expire reaper will clean it up periodically.

    // tryComplete 的成本通常和这个键的成员成正比（ISR越多，越慢），
    // 我们通过下面的方式来进行检查：调用 tryComplete()，如果operation没有完成，我们只会把Operation添加到键上。
    // 然后我们会再次调用tryComplete，这次调用，如果operation还没有完成，我们认为它没有错过任何future的触发，
    // 因为operation已经在watcherList上准备好了。这意味着在两次tryComplete调用之间，如果operation已经被另外的线程完成，
    // operation就没有必要添加到watch上。然而，这是一个不太严重的问题，因为到期后reaper会定期清理它。

    // todo tryCompleted：
    //    * The delayed produce operation can be completed if every partition
    //    * it produces to is satisfied by one of the following:
    //    *
    //    * Case A: This broker is no longer the leader: set an error in response
    // CAUTION 怎么判断是不是leader？ replicaManager 中判断：allPartitions.get((topic, partitionId))，取不到则不是leader
    //    * Case B: This broker is the leader:
    //    *   B.1 - If there was a local error thrown while checking if at least requiredAcks
    //    * replicas have caught up to this operation: set an error in response
    //    *   B.2 - Otherwise, set the response with no error.
    var isCompletedByMe = operation synchronized operation.tryComplete() // 首先调用tryComplete

    if (isCompletedByMe)
      return true

    var watchCreated = false
    for (key <- watchKeys) {
      // If the operation is already completed, stop adding it to the rest of the watcher list.
      if (operation.isCompleted())// 判断一下有没有被别的线程完成// Operation是一个延迟任务
        return false

      // 实际上就是往list里面塞东西
      // 对于produce来说这个operation就是DelayedProduce
      watchForOperation(key, operation)

      if (!watchCreated) {
        watchCreated = true
        estimatedTotalOperations.incrementAndGet()
      }
    }

    isCompletedByMe = operation synchronized operation.tryComplete()
    if (isCompletedByMe)
      return true

    // if it cannot be completed by now and hence is watched, add to the expire queue also
    if (!operation.isCompleted()) {
      timeoutTimer.add(operation)
      if (operation.isCompleted()) {
        // cancel the timer task
        operation.cancel()
      }
    }

    false
  }

  /**
    * Check if some some delayed operations can be completed with the given watch key,
    * and if yes complete them.
    *
    * @return the number of completed operations during this process
    */
  def checkAndComplete(key: Any): Int = {
    val watchers = inReadLock(removeWatchersLock) {
      watchersForKey.get(key)
    }
    if (watchers == null)
      0
    else
      watchers.tryCompleteWatched()
  }

  /**
    * Return the total size of watch lists the purgatory. Since an operation may be watched
    * on multiple lists, and some of its watched entries may still be in the watch lists
    * even when it has been completed, this number may be larger than the number of real operations watched
    */
  def watched() = allWatchers.map(_.watched).sum

  /**
    * Return the number of delayed operations in the expiry queue
    */
  def delayed() = timeoutTimer.size

  /*
   * Return all the current watcher lists,
   * note that the returned watchers may be removed from the list by other threads
   */
  private def allWatchers = inReadLock(removeWatchersLock) {
    watchersForKey.values
  }

  /*
   * Return the watch list of the given key, note that we need to
   * grab the removeWatchersLock to avoid the operation being added to a removed watcher list
   */
  private def watchForOperation(key: Any, operation: T) {
    inReadLock(removeWatchersLock) {
      val watcher: Watchers = watchersForKey.getAndMaybePut(key)
      watcher.watch(operation)
    }
  }

  /*
   * Remove the key from watcher lists if its list is empty
   */
  private def removeKeyIfEmpty(key: Any, watchers: Watchers) {
    inWriteLock(removeWatchersLock) {
      // if the current key is no longer correlated to the watchers to remove, skip
      if (watchersForKey.get(key) != watchers)
        return

      if (watchers != null && watchers.watched == 0) {
        watchersForKey.remove(key)
      }
    }
  }

  /**
    * Shutdown the expire reaper thread
    */
  def shutdown() {
    if (reaperEnabled)
      expirationReaper.shutdown()
    timeoutTimer.shutdown()
  }

  /**
    * A linked list of watched delayed operations based on some key
    */
  private class Watchers(val key: Any) {

    private[this] val operations = new LinkedList[T]() // 用于管理DelayedOperation的队列

    def watched: Int = operations synchronized operations.size

    // add the element to watch
    def watch(t: T) {
      operations synchronized operations.add(t)
    }

    // traverse the list and try to complete some watched elements
    def tryCompleteWatched(): Int = {

      var completed = 0
      operations synchronized {
        val iter = operations.iterator()
        while (iter.hasNext) {
          val curr = iter.next()
          if (curr.isCompleted) {
            // another thread has completed this operation, just remove it
            iter.remove()
          } else if (curr synchronized curr.tryComplete()) {
            completed += 1
            iter.remove()
          }
        }
      }

      if (operations.size == 0)
        removeKeyIfEmpty(key, this)

      completed
    }

    // traverse the list and purge elements that are already completed by others
    def purgeCompleted(): Int = {
      var purged = 0
      operations synchronized {
        val iter = operations.iterator()
        while (iter.hasNext) {
          val curr = iter.next()
          if (curr.isCompleted) {
            iter.remove()
            purged += 1
          }
        }
      }

      if (operations.size == 0)
        removeKeyIfEmpty(key, this)

      purged
    }
  }

  def advanceClock(timeoutMs: Long) {
    timeoutTimer.advanceClock(timeoutMs)

    // Trigger a purge if the number of completed but still being watched operations is larger than
    // the purge threshold. That number is computed by the difference btw the estimated total number of
    // operations and the number of pending delayed operations.
    if (estimatedTotalOperations.get - delayed > purgeInterval) {
      // now set estimatedTotalOperations to delayed (the number of pending operations) since we are going to
      // clean up watchers. Note that, if more operations are completed during the clean up, we may end up with
      // a little overestimated total number of operations.
      estimatedTotalOperations.getAndSet(delayed)
      debug("Begin purging watch lists")
      val purged = allWatchers.map(_.purgeCompleted()).sum
      debug("Purged %d elements from watch lists.".format(purged))
    }
  }

  /**
    * A background reaper to expire delayed operations that have timed out
    */
  private class ExpiredOperationReaper extends ShutdownableThread(
    "ExpirationReaper-%d".format(brokerId),
    false) {

    override def doWork() {
      advanceClock(200L)
    }
  }

}
