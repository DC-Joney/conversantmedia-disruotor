package com.conversantmedia.util.concurrent;

import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.TimeUnit;

public class DisruptorQueueTest {

    private DisruptorBlockingQueue<Object> blockingQueue;

    @Before
    public void setUp() throws Exception {
        this.blockingQueue = new DisruptorBlockingQueue<>(8);
    }

    @Test
    public void testTake() throws InterruptedException {
        blockingQueue.take();
    }

    @Test
    public void testNanoSeconds() throws InterruptedException {
        System.out.println(TimeUnit.MILLISECONDS.toNanos(1));
    }
}
