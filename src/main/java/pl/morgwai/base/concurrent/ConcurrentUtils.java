// Copyright (c) Piotr Morgwai Kotarbinski, Licensed under the Apache License, Version 2.0
package pl.morgwai.base.concurrent;

import java.util.concurrent.*;
import java.util.function.*;



/**
 * Various concurrent utility functions.
 */
public interface ConcurrentUtils {



	/**
	 * Convenient version of {@link CompletableFuture#supplyAsync(Supplier, Executor)} that takes a
	 * {@link Callable} instead of a {@link Supplier}. If {@link Callable#call() task.call()} throws
	 * an exception, it will be pipelined to
	 * {@link CompletableFuture#handle(BiFunction) handle(...)} /
	 * {@link CompletableFuture#whenComplete(BiConsumer) whenComplete(...)} /
	 * {@link CompletableFuture#exceptionally(Function) exceptionally(...)} chained calls.
	 */
	static <T> CompletableFuture<T> completableFutureSupplyAsync(
			Callable<T> task, Executor executor) {
		final var result = new CompletableFuture<T>();
		executor.execute(
			() -> {
				try {
					result.complete(task.call());
				} catch (Exception e) {
					result.completeExceptionally(e);
				}
			}
		);
		return result;
	}
}
