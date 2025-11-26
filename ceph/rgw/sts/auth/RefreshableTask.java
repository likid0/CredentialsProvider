/*
 * Copyright 2011-2018 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package ceph.rgw.sts.auth;

import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.exception.SdkServiceException;
import java.io.Closeable;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;

import ceph.rgw.sts.auth.DaemonThreadFactory;

/**
 * Handles refreshing a value with a simple synchronization policy. Does a blocking, synchronous
 * refresh if needed, otherwise queues an asynchronous refresh and returns the current value.
 */
class RefreshableTask<T> implements Closeable {
    /**
     * Maximum time to wait for a blocking refresh lock before calling refresh again. This is to
     * rate limit how many times we call refresh. In the ideal case, refresh always occurs in a
     * timely fashion and only one thread actually does the refresh.
     */
    private static final long BLOCKING_REFRESH_MAX_WAIT_IN_SECONDS = 5;

    /**
     * Used to synchronize a blocking refresh. Used when a caller can't return without getting the
     * refreshed value.
     */
    private final Lock blockingRefreshLock = new ReentrantLock();

    /**
     * Atomic holder for refreshable value.
     */
    private final AtomicReference<T> refreshableValueHolder = new AtomicReference<T>();

    /**
     * Single threaded executor to asynchronous refresh the value.
     */
    private final ExecutorService executor = Executors.newSingleThreadExecutor(new DaemonThreadFactory());

    /**
     * Used to ensure only one thread at any given time refreshes the value.
     */
    private final AtomicBoolean asyncRefreshing = new AtomicBoolean(false);

    /**
     * Callback to get a new refreshed value.
     */
    private final Callable<T> refreshCallable;

    /**
     * Predicate to determine whether a blocking refresh should be performed
     */
    private final Predicate<T> shouldDoBlockingRefresh;

    /**
     * Predicate to determine whether a async refresh can be done rather than a blocking refresh.
     */
    private final Predicate<T> shouldDoAsyncRefresh;

    private RefreshableTask(Callable<T> refreshCallable, Predicate<T> shouldDoBlockingRefresh,
                            Predicate<T> shouldDoAsyncRefresh) {
        this.refreshCallable = Objects.requireNonNull(refreshCallable, "refreshCallable");
        this.shouldDoBlockingRefresh = Objects.requireNonNull(shouldDoBlockingRefresh, "shouldDoBlockingRefresh");
        this.shouldDoAsyncRefresh = Objects.requireNonNull(shouldDoAsyncRefresh, "shouldDoAsyncRefresh");
    }

    @Override
    public void close() {
        executor.shutdown();
    }

    public static class Builder<T> {
        private Callable<T> refreshCallable;
        private Predicate<T> shouldDoBlockingRefresh;
        private Predicate<T> shouldDoAsyncRefresh;

        /**
         * Set the callable that will provide the value when a refresh occurs.
         *
         * @return This object for method chaining.
         */
        public Builder withRefreshCallable(Callable<T> refreshCallable) {
            this.refreshCallable = refreshCallable;
            return this;
        }

        /**
         * Set the predicate that will determine when the task will do a blocking refresh.
         *
         * @return This object for method chaining.
         */
        public Builder withBlockingRefreshPredicate(Predicate<T> shouldDoBlockingRefresh) {
            this.shouldDoBlockingRefresh = shouldDoBlockingRefresh;
            return this;
        }

        /**
         * Set the predicate that will determine when the task will queue a non-blocking, async
         * refresh.
         *
         * @return This object for method chaining.
         */
        public Builder withAsyncRefreshPredicate(Predicate<T> shouldDoAsyncRefresh) {
            this.shouldDoAsyncRefresh = shouldDoAsyncRefresh;
            return this;
        }

        /**
         * @return The configured RefreshableTask
         */
        public RefreshableTask<T> build() {
            return new RefreshableTask<T>(refreshCallable, shouldDoBlockingRefresh,
                                          shouldDoAsyncRefresh);
        }
    }

    /**
     * Return a valid value, refreshing if necessary. May return the current value, do an async
     * refresh if possible, or do a blocking refresh if needed.
     *
     * @throws SdkClientException If error occurs during refresh.
     * @throws IllegalStateException If value if invalid after refreshing.
     */
    public T getValue() throws SdkClientException, IllegalStateException {
        if (shouldDoBlockingRefresh()) {
            blockingRefresh();
        } else if (shouldDoAsyncRefresh()) {
            asyncRefresh();
        }

        return getRefreshedValue();
    }

    /**
     * Forces a refresh of the value. This method will not attempt to lock on calls to refresh the
     * value.
     *
     * @throws SdkClientException If error occurs during refresh.
     * @throws IllegalStateException If value if invalid after refreshing.
     */
    public T forceGetValue() {
        refreshValue();
        return getRefreshedValue();
    }

    /**
     * @return The refreshed value.
     * @throws IllegalStateException If the refreshed value is still invalid.
     */
    private T getRefreshedValue() throws IllegalStateException {
        T refreshableValue = refreshableValueHolder.get();
        if (refreshableValue != null) {
            return refreshableValue;
        } else {
            throw new IllegalStateException("Refreshed value should never be null.");
        }
    }

    private boolean shouldDoBlockingRefresh() {
        return shouldDoBlockingRefresh.test(refreshableValueHolder.get());
    }

    /**
     * @return True if we should kick of an asynchronous refresh of the value. False otherwise.
     */
    private boolean shouldDoAsyncRefresh() {
        return shouldDoAsyncRefresh.test(refreshableValueHolder.get());
    }

    /**
     * Used when there is no valid value to return. Callers are blocked until a new value is created
     * or an exception is thrown.
     */
    private void blockingRefresh() {
        try {
            if (blockingRefreshLock
                    .tryLock(BLOCKING_REFRESH_MAX_WAIT_IN_SECONDS, TimeUnit.SECONDS)) {
                try {
                    // Return if successful refresh occurred while waiting for the lock
                    if (!shouldDoBlockingRefresh()) {
                        return;
                    } else {
                        // Otherwise do a synchronous refresh if the last lock holder was unable to
                        // refresh the value
                        refreshValue();
                        return;
                    }
                } finally {
                    blockingRefreshLock.unlock();
                }
            }
        } catch (InterruptedException ex) {
            handleInterruptedException("Interrupted waiting to refresh the value.", ex);
        }
        // Couldn't acquire the lock. Just try a synchronous refresh
        refreshValue();
    }

    /**
     * Used to asynchronously refresh the value. Caller is never blocked.
     */
    private void asyncRefresh() {
        // Immediately return if refresh already in progress
        if (asyncRefreshing.compareAndSet(false, true)) {
            try {
                executor.submit(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            refreshValue();
                        } finally {
                            asyncRefreshing.set(false);
                        }
                    }
                });
            } catch (RuntimeException ex) {
                asyncRefreshing.set(false);
                throw ex;
            }
        }
    }

    /**
     * Invokes the callback to get a new value.
     */
    private void refreshValue() {
        try {
            refreshableValueHolder
                    .compareAndSet(refreshableValueHolder.get(), refreshCallable.call());
        } catch (SdkServiceException sse) {
            // Preserve the original SSE
            throw sse;
        } catch (SdkClientException sce) {
            // Preserve the original SCE
            throw sce;
        } catch (Exception e) {
            throw SdkClientException.builder().cause(e).build();
        }
    }

    /**
     * If we are interrupted while waiting for a lock we just restore the interrupt status and throw
     * an SdkClientException back to the caller.
     */
    private void handleInterruptedException(String message, InterruptedException cause) {
        Thread.currentThread().interrupt();
        throw SdkClientException.builder().message(message).cause(cause).build();
    }

}

