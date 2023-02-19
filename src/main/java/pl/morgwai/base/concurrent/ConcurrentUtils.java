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
	 * an exception, it is wrapped in a {@link CompletionException}, so that it can be pipelined
	 * to {@link CompletableFuture#handle(BiFunction)},
	 * {@link CompletableFuture#handleAsync(BiFunction, Executor)} and
	 * {@link CompletableFuture#exceptionally(Function)} chained calls.
	 */
	static <T> CompletableFuture<T> completableFutureSupplyAsync(
			Callable<T> task, Executor executor) {
		return CompletableFuture.supplyAsync(
				completableThrowingSupplierFromCallable(task), executor);
	}

	/**
	 * Convenient version of {@link CompletableFuture#supplyAsync(Supplier)} that takes a
	 * {@link Callable} instead of a {@link Supplier}. If {@link Callable#call() task.call()} throws
	 * an exception, it is wrapped in a {@link CompletionException}, so that it can be pipelined
	 * to {@link CompletableFuture#handle(BiFunction)},
	 * {@link CompletableFuture#handleAsync(BiFunction, Executor)} and
	 * {@link CompletableFuture#exceptionally(Function)} chained calls.
	 */
	static <T> CompletableFuture<T> completableFutureSupplyAsync(Callable<T> task) {
		return CompletableFuture.supplyAsync(completableThrowingSupplierFromCallable(task));
	}

	/**
	 * Wraps {@link Callable task} in a {@link Supplier} that wraps any exceptions thrown by
	 * {@link Callable#call() task.call()} in a {@link CompletionException}. For use with
	 * {@link CompletableFuture#supplyAsync(Supplier)} and
	 * {@link CompletableFuture#supplyAsync(Supplier, Executor)}.
	 * @see #completableFutureSupplyAsync(Callable)
	 * @see #completableFutureSupplyAsync(Callable, Executor)
	 */
	static <T> Supplier<T> completableThrowingSupplierFromCallable(Callable<T> task) {
		return new Supplier<>() {

			@Override public T get() {
				try {
					return task.call();
				} catch (CompletionException e) {
					throw e;
				} catch (Exception e) {
					throw new CompletionException(e);
				}
			}

			@Override public String toString() { return task.toString(); }
		};
	}
}
