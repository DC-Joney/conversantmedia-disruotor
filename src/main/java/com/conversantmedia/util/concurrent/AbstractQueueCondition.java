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

import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Created by jcairns on 12/11/14.
 */

// use java sync to signal
abstract class AbstractQueueCondition implements QueueCondition {

    private final ReentrantLock queueLock = new ReentrantLock();
    private final Condition queueCondition = queueLock.newCondition();

    // wake me when the condition is satisfied, or timeout
    @Override
    public void awaitNanos(final long timeout) throws InterruptedException {
        queueLock.lock();
        try {
            queueCondition.awaitNanos(timeout);
        }
        finally {
            queueLock.unlock();
        }
    }

    // wake if signal is called, or wait indefinitely
    @Override
    public void await() throws InterruptedException {
        queueLock.lock();
        try {
            queueCondition.await();
        }
        finally {
            queueLock.unlock();
        }
    }

    // tell threads waiting on condition to wake up
    @Override
    public void signal() {
        queueLock.lock();
        try {
            queueCondition.signalAll();
        }
        finally {
            queueLock.unlock();
        }

    }

}
