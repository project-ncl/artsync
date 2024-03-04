package org.jboss.pnc.artsync.concurrency;


import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.core.functions.Either;
import io.github.resilience4j.decorators.Decorators;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.jboss.pnc.artsync.config.ServiceApplicationConfig;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class ConstrainedExecutor implements ConstrainedExecutorService, ConstrainedCompletableFuture {

    private final ExecutorService delegate;

    private final ScheduledExecutorService scheduler;

    private final RateLimiter rateLimiter;

    private final Retry retry;

    public ConstrainedExecutor(ExecutorService delegate,
                               ScheduledExecutorService scheduler,
                               ServiceApplicationConfig applicationConfig,
                               Predicate<Either<? extends Throwable, ?>> isRateLimited,
                               Predicate<? extends Throwable> retryOnException,
                               Predicate<Object> retryOnResult) {
        this.delegate = delegate;
        this.scheduler = scheduler;
        RateLimiterConfig.Builder config = RateLimiterConfig.custom()
            .limitRefreshPeriod(applicationConfig.rateLimit().timeConstraint())
            .limitForPeriod(applicationConfig.rateLimit().rateOfRequests())
            .timeoutDuration(applicationConfig.rateLimit().requestTimeout());
        if (isRateLimited != null) {
            config.drainPermissionsOnResult(isRateLimited);
        }
        this.rateLimiter = RateLimiter.of(applicationConfig.serviceName(), config.build());

        if (applicationConfig.retry().enabled()) {
            RetryConfig.Builder retryConf = RetryConfig.custom().maxAttempts(applicationConfig.retry().maxAttempts())
                .waitDuration(applicationConfig.retry().interval());
            if (retryOnException != null) {
                retryConf.retryOnException(retryOnException);
            }
            if (retryOnResult != null) {
                retryConf.retryOnResult(retryOnResult);
            }
            if (applicationConfig.retry().exponentialBackoff()) {
                retryConf.intervalFunction(IntervalFunction.ofExponentialBackoff());
            }
            this.retry = Retry.of(applicationConfig.serviceName(), retryConf.build());
        } else {
            this.retry = null;
        }
    }

    public int getNumberOfWaitingThreads() {
        return rateLimiter.getMetrics().getNumberOfWaitingThreads();
    }

    public int getNumberOfPermissions() {
        return rateLimiter.getMetrics().getAvailablePermissions();
    }

    @Override
    public <T> CompletableFuture<T> runAsync(int permits, Runnable task, T result) {
        Supplier<CompletionStage<T>> future = () -> CompletableFuture.runAsync(task, delegate).thenApply((ign) -> result);

        Decorators.DecorateCompletionStage<T> decorator = Decorators.ofCompletionStage(future);
        if (retry != null) {
            decorator.withRetry(retry, scheduler);
        }
        return decorator.withRateLimiter(rateLimiter, permits).get().toCompletableFuture();
    }

    @Override
    public <T> CompletableFuture<T> runAsync(Runnable task, T result) {
        return runAsync(1, task, result);
    }

    @Override
    public CompletableFuture<Void> runAsync(int permits, Runnable task) {
        return limitCompletionStage(permits, () -> CompletableFuture.runAsync(task, delegate)).toCompletableFuture();
    }

//    public <T> CompletableFuture<T> wrapAsync(int permits, Supplier<CompletionStage<T>> stage) {
//
//        return limitCompletionStage(permits,CompletableFuture.supplyAsync(stage, delegate).handle((cs, t) ->
//            switch (t) {
//                case null -> cs.
//            });
//    }

    @Override
    public <T> CompletableFuture<Void> runAsync(Runnable task) {
        return runAsync(1, task);
    }

    @Override
    public <T> CompletableFuture<T> supplyAsync(int permits, Supplier<T> supplier) {
        return CompletableFuture.supplyAsync(limitSupplier(permits, supplier), delegate);
    }

    @Override
    public <T> CompletableFuture<T> supplyAsync(Supplier<T> supplier) {
        return supplyAsync(1, supplier);
    }

    private <T> Supplier<T> limitSupplier(int permits, Supplier<T> supplier) {
        Decorators.DecorateSupplier<T> decorator = Decorators.ofSupplier(supplier);
        decorator.withRateLimiter(rateLimiter, permits);
        if (retry != null) {
            decorator.withRetry(retry);
        }

        return decorator.decorate();
    }

    private <T> Supplier<T> limitSupplier(Supplier<T> supplier) {
        return limitSupplier(1, supplier);
    }

    private <T> CompletionStage<T> limitCompletionStage(int permits, Supplier<CompletionStage<T>> stage) {
        Decorators.DecorateCompletionStage<T> decorator = Decorators.ofCompletionStage(stage);
        decorator.withRateLimiter(rateLimiter, permits);
        if (retry != null) {
            decorator.withRetry(retry, scheduler);
        }

        return decorator.get();
    }

    private Runnable limitRunnable(int permits, Runnable runnable) {
        Decorators.DecorateRunnable decorator = Decorators.ofRunnable(runnable);
        decorator.withRateLimiter(rateLimiter, permits);
        if (retry != null) {
            decorator.withRetry(retry);
        }
        return decorator.decorate();
    }

    private Runnable limitRunnable(Runnable runnable) {
        return limitRunnable(1, runnable);
    }

    private <T> Callable<T> limitCallable(int permits, Callable<T> callable) {
        Decorators.DecorateCallable<T> decorator = Decorators.ofCallable(callable);
        decorator.withRateLimiter(rateLimiter, permits);
        if (retry != null) {
            decorator.withRetry(retry);
        }
        return decorator.decorate();
    }

    private <T> Callable<T> limitCallable(Callable<T> callable) {
        return limitCallable(1, callable);
    }

    // METHODS enhanced with permits for cases when a single task takes more permits

    @Override
    public <T> Future<T> submit(int permits, Callable<T> task) {
        return delegate.submit(limitCallable(permits, task));
    }

    @Override
    public <T> Future<T> submit(int permits, Runnable task, T result) {
        return delegate.submit(limitRunnable(permits, task), result);
    }

    @Override
    public Future<?> submit(int permits, Runnable task) {
        return delegate.submit(limitRunnable(permits, task));
    }

    @Override
    public <T> List<Future<T>> invokeAll(int permits, Collection<? extends Callable<T>> tasks) throws InterruptedException {
        return delegate.invokeAll(tasks.stream().map(callable -> limitCallable(permits, callable)).collect(Collectors.toList()));
    }

    @Override
    public <T> List<Future<T>> invokeAll(int permits, Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) throws InterruptedException {
        return delegate.invokeAll(tasks.stream().map(callable -> limitCallable(permits, callable)).collect(Collectors.toList()), timeout, unit);
    }

    @Override
    public <T> T invokeAny(int permits, Collection<? extends Callable<T>> tasks) throws InterruptedException, ExecutionException {
        return delegate.invokeAny(tasks.stream().map(callable -> limitCallable(permits, callable)).collect(Collectors.toList()));
    }

    @Override
    public <T> T invokeAny(int permits, Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        return delegate.invokeAny(tasks.stream().map(callable -> limitCallable(permits, callable)).collect(Collectors.toList()), timeout, unit);
    }

    @Override
    public void execute(int permits, Runnable command) {
        delegate.execute(limitRunnable(permits, command));
    }

    // DELEGATION to VirtualThread executor

    @Override
    public void shutdown() {
        throw new UnsupportedOperationException("Do not shutdown the executor manually.");
    }

    @Override
    public boolean isShutdown() {
        return delegate.isShutdown();
    }

    @Override
    public boolean isTerminated() {
        return delegate.isTerminated();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return delegate.awaitTermination(timeout, unit);
    }

    @Override
    public <T> Future<T> submit(Callable<T> task) {
        return delegate.submit(limitCallable(task));
    }

    @Override
    public <T> Future<T> submit(Runnable task, T result) {
        return delegate.submit(limitRunnable(task), result);
    }

    @Override
    public Future<?> submit(Runnable task) {
        return delegate.submit(limitRunnable(task));
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) throws InterruptedException {
        return delegate.invokeAll(tasks.stream().map(this::limitCallable).collect(Collectors.toList()));
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) throws InterruptedException {
        return delegate.invokeAll(tasks.stream().map(this::limitCallable).collect(Collectors.toList()), timeout, unit);
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks) throws InterruptedException, ExecutionException {
        return delegate.invokeAny(tasks.stream().map(this::limitCallable).collect(Collectors.toList()));
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        return delegate.invokeAny(tasks.stream().map(this::limitCallable).collect(Collectors.toList()), timeout, unit);
    }

    @Override
    public void close() {
        throw new UnsupportedOperationException("Do not close the executor manually.");
    }

    @Override
    public void execute(Runnable command) {
        delegate.execute(limitRunnable(command));
    }

    @Override
    public List<Runnable> shutdownNow() {
        throw new UnsupportedOperationException("Do not shutdown the executor manually.");
    }
}
