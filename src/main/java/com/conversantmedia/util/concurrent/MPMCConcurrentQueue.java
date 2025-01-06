package com.conversantmedia.util.concurrent;

/*
 * #%L
 * Conversant Disruptor
 * ~~
 * Conversantmedia.com © 2016, Conversant, Inc. Conversant® is a trademark of Conversant, Inc.
 * ~~
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */


/**
 * Dmitry Vyukov, Bounded MPMC queue - http://www.1024cores.net/home/lock-free-algorithms/queues/bounded-mpmc-queue
 * <p>
 * Added for benchmarking and comparison.     MultithreadConcurrentQueue performs better in the regimes I have tested.
 * <p>
 * Created by jcairns on 5/29/14.
 */
//其原理与JCtools中的MpmcArrayQueue原理相似，不过MamccArrayQueue底层是采用seq数组来实现的
//而MPMCConcurrentQueue则是将seq迁移到到每个元素内部
class MPMCConcurrentQueue<E> implements ConcurrentQueue<E> {

    protected final int size;

    final long mask;

    // a ring buffer representing the queue
    final Cell<E>[] buffer;

    /**
     * 当前取出的元素的位置，可以理解为pollIndex
     */
    final ContendedAtomicLong head = new ContendedAtomicLong(0L);

    /**
     * 当前添加的元素的位置，可以理解为offerIndex
     */
    final ContendedAtomicLong tail = new ContendedAtomicLong(0L);

    /**
     * Construct a blocking queue of the given fixed capacity.
     * <p>
     * Note: actual capacity will be the next power of two
     * larger than capacity.
     *
     * @param capacity maximum capacity of this queue
     */

    public MPMCConcurrentQueue(final int capacity) {
        // capacity of at least 2 is assumed
        int c = 2;
        //计算capacity to next power of 2
        while (c < capacity) c <<= 1;
        //将当前的容量大小设置为size
        size = c;
        //计算mask
        mask = size - 1L;
        //计算cell数组的大小
        buffer = new Cell[size];
        //初始化cell数组
        for (int i = 0; i < size; i++) {
            buffer[i] = new Cell<E>(i);
        }
    }

    @Override
    public boolean offer(E e) {
        Cell<E> cell;
        //获取offerIndex的位置
        long tail = this.tail.get();
        for (; ; ) {
            //获取offerIndex对应的cell元素
            cell = buffer[(int) (tail & mask)];
            //获取cell元素对应的seq位置，既cell元素在数组中的位置
            final long seq = cell.seq.get();
            //计算cell index 与 offerIndex的差值
            final long dif = seq - tail;
            //如果差值==0 表示当前存储的元素已经被消费掉了
            if (dif == 0) {
                //设置tail为下一次需要添加元素的位置
                if (this.tail.compareAndSet(tail, tail + 1)) {
                    break;
                }
            }
            //如果dif < 0 表示当前位置的元素还未被消费掉
            else if (dif < 0) {
                return false;
            }

            //否则的话说明已经有其他线程正在添加元素，获取tail的最新值进行计算
            else {
                tail = this.tail.get();
            }
        }

        //将cell位置的entry设置为offer的元素
        cell.entry = e;
        //将cell的seq设置为tail + 1 表示未消费
        cell.seq.set(tail + 1);
        return true;
    }

    ;

    @Override
    public E poll() {
        Cell<E> cell;
        //获取pollIndex
        long head = this.head.get();
        for (; ; ) {
            //计算pollIndex对应的cell元素
            cell = buffer[(int) (head & mask)];
            //获取cell元素的seq，如果该元素还未被消费掉，那么seq = pollIndex + 1
            long seq = cell.seq.get();
            //计算seq与pollIndex + 1的差值，如果该位置的元素未被消费掉，那么计算的差值为0
            final long dif = seq - (head + 1L);
            //如果dif == 0 表示该位置的元素未被消费掉
            if (dif == 0) {
                if (this.head.compareAndSet(head, head + 1)) {
                    break;
                }
            }
            //如果dif < 0 表示该位置的元素还未生成，举个例子：假设 ring buffer 大小为2，其中数据如下：
            // （seq = 1， seq = 2），当我们第一次消费完成数据后 (seq = pollIndex + mask + 1) (seq = 2, seq = 3 )
            // 这个时候当我们在需要消费数据时 head = 2，seq = 2, seq - (head + 1) < 0 所以该位置的数据还未被生产
            else if (dif < 0) {
                return null;
            }
            //否则的话说明已经有其他线程正在消费元素，获取head的最新值进行计算
            else {
                head = this.head.get();
            }
        }

        try {
            return cell.entry;
        } finally {
            cell.entry = null;
            //将seq设置为head + mask + 1，假设head = 0 ，那么seq的值为 2
            // 当下次需要生产元素时通过tail - seq 判断是否为0就可以知道该位置的元素是否被消费掉
            cell.seq.set(head + mask + 1L);
        }

    }

    @Override
    public final E peek() {
        return buffer[(int) (head.get() & mask)].entry;
    }


    @Override
    // drain the whole queue at once
    public int remove(final E[] e) {
        int nRead = 0;
        while (nRead < e.length && !isEmpty()) {
            final E entry = poll();
            if (entry != null) {
                e[nRead++] = entry;
            }
        }
        return nRead;
    }

    @Override
    public final int size() {
        return (int) Math.max((tail.get() - head.get()), 0);
    }

    @Override
    public int capacity() {
        return size;
    }

    @Override
    public final boolean isEmpty() {
        return head.get() == tail.get();
    }

    @Override
    public void clear() {
        while (!isEmpty()) poll();
    }

    @Override
    public final boolean contains(Object o) {
        for (int i = 0; i < size(); i++) {
            final int slot = (int) ((head.get() + i) & mask);
            if (buffer[slot].entry != null && buffer[slot].entry.equals(o)) return true;
        }
        return false;

    }

    /**
     * cell 为数组中的每一个元素，且每一个cell都会有一个对应的seq以及对应的value
     * <p>
     * 举个例子如下：假设我们添加以下元素: [a,b,c]
     * 那么Cell数组中存储的值为：cell(seq=1,a),cell(seq=2,b),cell(seq=3,c)
     * 为什么cell数组中的seq比实际的 {@code (cell index & mask)} 要大1呢，是因为底层采用的是ring buffer来存储的数据
     * 当我们需要添加数据时，我们需要考虑到该位置的数据是否被消费，所以在offer时会通过{@code  tail - cell seq} 来进行判断
     * 如果未被消费掉则 {@code tail - cell seq < 0}
     * 如果被被消费掉则 {@code tail - cell seq == 0}，因为在通过poll取出该位置数据后会将cell seq设置为 head 既pollIndex
     */
    protected static final class Cell<R> {
        final ContendedAtomicLong seq = new ContendedAtomicLong(0L);

        R entry;

        Cell(final long s) {
            seq.set(s);
            entry = null;
        }

    }

}
