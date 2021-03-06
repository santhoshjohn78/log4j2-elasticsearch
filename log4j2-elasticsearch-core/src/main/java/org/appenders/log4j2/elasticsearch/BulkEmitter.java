package org.appenders.log4j2.elasticsearch;

/*-
 * #%L
 * %%
 * Copyright (C) 2017 Rafal Foltynski
 * %%
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 * #L%
 */

import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Time- and size-based batch scheduler. Uses provided {@link BatchOperations} implementation to produce batches and
 * delivers them to provided listener.
 *
 * @param <BATCH_TYPE> type of processed batches
 */
public class BulkEmitter<BATCH_TYPE> implements BatchEmitter {

    private final AtomicInteger size = new AtomicInteger();

    private final int maxSize;
    private final int interval;
    private final BatchOperations<BATCH_TYPE> batchOperations;

    private final AtomicReference<BatchBuilder<BATCH_TYPE>> builder;

    private final Timer scheduler = new Timer();
    private Function<BATCH_TYPE, Boolean> listener;

    private final ListenerLock listenerLock = new ListenerLock();

    public BulkEmitter(int atSize, int intervalInMillis, BatchOperations<BATCH_TYPE> batchOperations) {
        this.maxSize = atSize;
        this.interval = intervalInMillis;
        this.batchOperations = batchOperations;
        this.builder = new AtomicReference<>(batchOperations.createBatchBuilder());
        this.scheduler.scheduleAtFixedRate(createNotificationTask(), 0, interval);
    }

    /**
     * Delivers current batch to the listener if at least one item is waiting for delivery, no-op otherwise.
     */
    public final void notifyListener() {
        synchronized (listenerLock) {
            if (size.get() == 0) {
                return;
            }
            this.size.set(0);
            listener.apply(builder.getAndSet(batchOperations.createBatchBuilder()).build());
        }
    }

    @Override
    public void add(Object batchItem) {

        builder.get().add(batchItem);

        if (size.incrementAndGet() >= maxSize) {
            notifyListener();
        }
    }

    private TimerTask createNotificationTask() {
        return new TimerTask() {
            @Override
            public void run() {
                notifyListener();
            }
        };
    }

    /**
     * Sets new batch listener. Currently only one listener may be set. However, since it's an extension point, this
     * limitation can be overridden.
     *
     * @param onReadyListener batch-to-client handler
     */
    public void addListener(Function<BATCH_TYPE, Boolean> onReadyListener) {
        this.listener = onReadyListener;
    }

    /*
     * Class used as monitor to increase lock visibility in profiling tools
     */
    private class ListenerLock {
    }

}
