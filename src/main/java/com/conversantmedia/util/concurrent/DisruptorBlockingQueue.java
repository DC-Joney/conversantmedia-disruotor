package com.conversantmedia.util.concurrent;

import java.io.Serializable;
import java.util.Collection;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * A fixed length blocking queue based on the principles of:
 *
 *  http://disruptor.googlecode.com/files/Disruptor-1.0.pdf
 *
 * This class could be used where ArrayBlockingQueue would be otherwise.
 *
 * This is a lock free blocking queue that implements
 * a fixed length queue backed by a ring buffer.   Access to the ring buffer
 * is sequenced by iterating a pair of atomic sequence numbers.  One is
 * for the head and another for the tail.
 *
 * When a particular thread would like to append to the queue, it obtains the
 * sequence number for the tail.  When the thread is ready to commit changes,
 * a machine compare and set is used to prove that the sequence number matches
 * the expected value.  In other words, no other thread has modified the sequence.
 *
 * If the sequence number does not match, the operation fails.   If the
 * sequence number matches expectation the thread can continue to operate
 * on the queue's ring buffer without contention.   This check cleverly
 * avoids any synchronization thus the moniker "lock free."   The lack
 * of synchronization results in significant performance advantages.
 *
 * For consumers, access to the back of the ring is controlled by a memory
 * barrier mechanism, namely the "volatile" keyword.   Spin locks are employed
 * to ensure the ring tail cursor is up to date prior to updating it.  Once the
 * ring cursor is updated, the reader/consumer can be assured that there
 * is data available to read.   The consumer thread then employs a
 * mechanism similar to the producer to validate access to the ring.
 *
 * A sequence number for the head of the ring is obtained and when
 * the reader would like to commit the change to the buffer it
 * uses the machine compare and set to prove that no other thread
 * has modified the ring in the interim.
 *
 * This pattern of access is roughly an order of magnitude faster than ArrayBlockingQueue.
 * It is roughly 2x faster than LinkedTransferQueue for similar operations/conditions.
 * Given that LinkedTransferQueue is "state of the art" in terms of Java performance,
 * it is clear that the Disruptor mechanism offers advantages over other
 * strategies.
 *
 * The only memory allocation in this object occurs at object creation and in the clone
 * and drainTo methods.   Otherwise, no garbage collection will ever be triggered by
 * calls to the disruptor queue.
 *
 * The drainTo method implements an efficient "batch" mechanism, and may be
 * used to safely claim all of the available queue entries.  Drain will not
 * perform as well when it is dealing with contention from other reader threads.
 *
 * Overall the disruptor pattern is weak in dealing with massive thread contention,
 * however efforts have been made to deal with that case here.   As always,
 * one should test their intended strategy.
 *
 * @author John Cairns <jcairns@dotomi.com> Date: 4/25/12 Time: 12:00 PM
 */
public final class DisruptorBlockingQueue<E> extends MultithreadConcurrentQueue<E> implements Serializable, Iterable<E>, Collection<E>, BlockingQueue<E>, Queue<E>, ConcurrentQueue<E> {

    // locking objects used for independent locking
    // of not empty, not full status, for java BlockingQueue support
    // if MultithreadConcurrentQueue is used directly, these calls are
    // optimized out and have no impact on timing values
    //
    protected final QueueCondition queueNotFullCondition;
    protected final QueueCondition queueNotEmptyCondition;

    /**
     * Construct a blocking queue of the given fixed capacity.
     * <p/>
     * Note: actual capacity will be the next power of two
     * larger than capacity.
     *
     * @param capacity maximum capacity of this queue
     */

    public DisruptorBlockingQueue(final int capacity) {
        // waiting locking gives substantial performance improvements
        // but makes disruptor aggressive with cpu utilization
        this(capacity, true);
    }

    /**
     * Construct a blocking queue with a given fixed capacity
     *
     * <p/>
     * Note: actual capacity will be the next power of two
     * larger than capacity.
     *
     * Waiting locking may be used in servers that are tuned for it, waiting
     * locking provides a high performance locking implementation which is approximately
     * a factor of 2 improvement in throughput (40M/s for 1-1 thread transfers)
     *
     * However waiting locking is more CPU aggressive and causes servers that may be
     * configured with far too many threads to show very high load averages.   This is probably
     * not as detrimental as it is annoying.
     *
     * @param capacity
     * @param useWaitingLocking
     */
    public DisruptorBlockingQueue(final int capacity, final boolean useWaitingLocking) {
        super(capacity);

        if(useWaitingLocking) {
            queueNotFullCondition = new WaitingQueueNotFull();
            queueNotEmptyCondition = new WaitingQueueNotEmpty();
        } else {
            queueNotFullCondition = new QueueNotFull();
            queueNotEmptyCondition = new QueueNotEmpty();
        }
    }

    /**
     * Construct a blocking queue of the given fixed capacity
     * <p/>
     * Note: actual capacity will be the next power of two
     * larger than capacity.
     * <p/>
     * The values from the collection, c, are appended to the
     * queue in iteration order.     If the number of elements
     * in the collection exceeds the actual capacity, then the
     * additional elements overwrite the previous ones until
     * all elements have been written once.
     *
     * @param capacity maximum capacity of this queue
     * @param c        A collection to use to populate inital values
     */
    public DisruptorBlockingQueue(final int capacity, Collection<? extends E> c) {
        this(capacity);
        for (final E e : c) {
            offer(e);
        }
    }

    @Override
    public final boolean offer(E e) {
        if (super.offer(e)) {
            queueNotEmptyCondition.signal();
            return true;
        } else {
            queueNotEmptyCondition.signal();
            return false;
        }
    }

    @Override
    public final E poll() {
        final E e = super.poll();
        // not full now
        queueNotFullCondition.signal();
        return e;
    }

    @Override
    public int remove(final E[] e) {
        final int n = super.remove(e);
        // queue can not be full
        queueNotFullCondition.signal();
        return n;
    }

    @Override
    public E remove() {
        return poll();
    }

    @Override
    public E element() {
        final E val = peek();
        if (val != null)
            return val;
        throw new NoSuchElementException("No element found.");
    }

    @Override
    public void put(E e) throws InterruptedException {
        // add object, wait for space to become available
        while (offer(e) == false) {
            if(Thread.currentThread().isInterrupted()) {
                throw new InterruptedException();
            }
            LockSupport.parkNanos(QueueCondition.PARK_TIMEOUT);
        }
    }

    @Override
    public boolean offer(E e, long timeout, TimeUnit unit) throws InterruptedException {
        for (;;) {
            if (offer(e)) {
                return true;
            } else {

                // wait for available capacity and try again
                if (!waitStatus(timeout, unit, queueNotFullCondition)) return false;
            }
        }
    }

    @Override
    public E take() throws InterruptedException {
        for (;;) {
            E pollObj = poll();
            if (pollObj != null) {
                return pollObj;
            }
            if(Thread.currentThread().isInterrupted()) {
                throw new InterruptedException();
            }
            LockSupport.parkNanos(QueueCondition.PARK_TIMEOUT);
        }
    }

    @Override
    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        for(;;) {
            E pollObj = poll();
            if(pollObj != null) {
                return pollObj;
            } else {
                // wait for the queue to have at least one element or time out
                if(!waitStatus(timeout, unit, queueNotEmptyCondition)) return null;
            }
        }
    }

    @Override
    public void clear() {
        super.clear();
        queueNotFullCondition.signal();
    }

    @Override
    public int remainingCapacity() {
        return size - size();
    }

    @Override
    public int drainTo(Collection<? super E> c) {
        return drainTo(c, size());
    }

    @Override
    // drain the whole queue at once
    public int drainTo(Collection<? super E> c, int maxElements) {

        // required by spec
        if (this == c) throw new IllegalArgumentException("Can not drain to self.");

        /* This employs a "batch" mechanism to load all objects from the ring
         * in a single update.    This could have significant cost savings in comparison
         * with poll, however it does require a memory allocation.
         */

        // save out the values - java should allocate this object on the stack
        final E[] pollObj = (E[]) new Object[Math.min(size(), maxElements)];

        final int nEle = remove(pollObj);
        int nRead = 0;

        for (int i = 0; i < nEle; i++) {
            if (c.add((E) pollObj[i])) nRead++;
            // else invalid state -- object is lost -- see javadoc for drainTo
        }

        // only return the number that was actually added to the collection
        return nRead;
    }


    @Override
    public Object[] toArray() {
        final E[] e = (E[]) new Object[size()];
        toArray(e);

        return e;

    }

    @Override
    public <T> T[] toArray(T[] a) {

        remove((E[])a);

        return a;
    }

    @Override
    public boolean add(E e) {
        if (offer(e)) return true;
        throw new IllegalStateException("queue is full");
    }

    /**
     * Provided for compatibility with the BlockingQueue interface only.
     * <p/>
     * This interface has been fixed to be properly concurrent, but will
     * block the entire queue, it should not be used!
     */
    @Override
    public boolean remove(Object o) {

        for (; ; ) {
            final long head = this.head.get();
            // we are optimistically advancing the head by one
            // if the object does not exist we have to put it back
            if (headCursor.compareAndSet(head, head + 1)) {
                for (; ; ) {
                    final long tail = this.tail.get();
                    if (tailCursor.compareAndSet(tail, tail + 1)) {
                        // number removed
                        int n = 0;

                        // just blocked access to the entire queue - go for it
                        for (int i = 0; i < size(); i++) {
                            final int slot = (int) ((this.head.get() + i) & mask);
                            if (buffer[slot] != null && buffer[slot].equals(o)) {
                                n++;

                                for (int j = i; j > 0; j--) {
                                    final int cSlot = (int) ((this.head.get() + j - 1) & mask);
                                    final int nextSlot = (int) ((this.head.get() + j) & mask);
                                    // overwrite ith element with previous
                                    buffer[nextSlot] = buffer[cSlot];
                                }
                            }
                        }

                        if (n > 0) {
                            // head is advanced once for each
                            headCursor.lazySet(head + n);

                            // undo the change to tail state - this was only
                            // done to block others from changing
                            tailCursor.lazySet(tail);
                            // tail is unchanged
                            this.head.lazySet(head+n);


                            // queue is not full now
                            queueNotFullCondition.signal();
                            return true;


                        } else {
                            // no change to the queue - unblock others
                            tailCursor.lazySet(tail);
                            headCursor.lazySet(head);
                            return false;
                        }
                    }
                }
            }
        }
    }

    @Override
    public boolean containsAll(Collection<?> c) {
        for (final Object o : c) {
            if (!contains(o)) return false;
        }
        return true;
    }

    @Override
    public boolean addAll(Collection<? extends E> c) {
        for (final E e : c) {
            if (!offer(e)) return false;
        }
        return true;
    }

    @Override
    public boolean removeAll(Collection<?> c) {
        int numFalses = 0;
        for (final Object o : c) {
            if (!remove(o)) numFalses++;
        }
        return numFalses > 0;
    }

    @Override
    public boolean retainAll(Collection<?> c) {
        int numFalses = 0;

        for (int i = 0; i < size(); i++) {
            final int headSlot = (int) ((head.get() + i) & mask);
            if (!c.contains(buffer[headSlot])) {
                if (!remove(buffer[headSlot])) {
                    numFalses++;
                } else {
                    // backtrack one step, we just backed values up at this point
                    i--;
                }

            }
        }

        return numFalses > 0;
    }

    @Override
    public Iterator<E> iterator() {
        return new RingIter();
    }


    private final class RingIter implements Iterator<E> {
        int dx = 0;

        E lastObj = null;

        private RingIter() {

        }

        @Override
        public boolean hasNext() {
            return dx < size();
        }

        @Override
        public E next() {
            final long pollPos = head.get();
            final int slot = (int) ((pollPos + dx++) & mask);
            lastObj = buffer[slot];
            return lastObj;
        }

        @Override
        public void remove() {
            DisruptorBlockingQueue.this.remove(lastObj);
        }
    }

    /**
     * Wait for timeout on condition
     *
     * @param timeout
     * @param unit
     * @param condition
     * @return
     * @throws InterruptedException
     */
    protected final boolean waitStatus(final long timeout, final TimeUnit unit, final QueueCondition condition) throws InterruptedException {
        // until condition is signaled

        final long timeoutNanos = TimeUnit.NANOSECONDS.convert(timeout, unit);
        final long expireTime = System.nanoTime() + timeoutNanos;
        // the queue is empty or full wait for something to change
        while (condition.test()) {
            if (System.nanoTime() > expireTime) {
                return false;
            }

            condition.awaitNanos(timeoutNanos);

        }

        return true;
    }

    // condition used for signaling queue is full
    private final class QueueNotFull extends AbstractQueueCondition {

        @Override
        // @return boolean - true if the queue is full
        public final boolean test() {
            final long queueStart = tail.get() - size;
            return ((headCache.value == queueStart) || (headCache.value = head.get()) == queueStart);
        }
    }

    // condition used for signaling queue is empty
    private final class QueueNotEmpty extends AbstractQueueCondition {
        @Override
        // @return boolean - true if the queue is empty
        public final boolean test() {
            return tail.get() == head.get();
        }
    }

    // condition used for signaling queue is full
    private final class WaitingQueueNotFull extends AbstractWaitingQueueCondition {

        @Override
        // @return boolean - true if the queue is full
        public final boolean test() {
            final long queueStart = tail.get() - size;
            return ((headCache.value == queueStart) || (headCache.value = head.get()) == queueStart);
        }
    }

    // condition used for signaling queue is empty
    private final class WaitingQueueNotEmpty extends AbstractWaitingQueueCondition {
        @Override
        // @return boolean - true if the queue is empty
        public final boolean test() {
            return tail.get() == head.get();
        }
    }

}
