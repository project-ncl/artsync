package org.jboss.pnc.artsync.concurrency;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public interface ConstrainedCompletableFuture {

    <T> CompletableFuture<T> runAsync(int permits, Runnable task, T result);

    <T> CompletableFuture<T> runAsync(Runnable task, T result);

    <T> CompletableFuture<Void> runAsync(int permits, Runnable task);

    <T> CompletableFuture<Void> runAsync(Runnable task);

    <T> CompletableFuture<T> supplyAsync(int permits, Supplier<T> supplier);

    <T> CompletableFuture<T> supplyAsync(Supplier<T> supplier);
}
