// Copyright (c) Piotr Morgwai Kotarbinski, Licensed under the Apache License, Version 2.0
package pl.morgwai.base.concurrent;

import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;



/**
 * An object performing {@link #await(long, TimeUnit) timed blocking operation}, such as
 * {@link Thread#join(long)}, {@link Object#wait(long)},
 * {@link ExecutorService#awaitTermination(long, TimeUnit)} etc.
 * @see InMillis#awaitMultiple(long, InMillis...)
 * @see #awaitMultiple(long, TimeUnit, boolean, Awaitable...)
 */
@FunctionalInterface
public interface Awaitable {



	boolean await(long timeout, TimeUnit unit) throws InterruptedException;



	/**
	 * Awaits for multiple timed blocking operations, such as {@link Thread#join(long)},
	 * {@link Object#wait(long)}, {@link Thread#sleep(long)},
	 * {@link ExecutorService#awaitTermination(long, TimeUnit)} etc.
	 * <p>
	 * If {@code timeout} passes before completing all {@code tasks}, continues to await for
	 * remaining tasks with timeout of 1 nanosecond.<br/>
	 * If {@code continueOnInterrupt} is {@code true}, does so also in case
	 * {@link InterruptedException} is thrown while awaiting.</p>
	 * <p>
	 * Note: internally all time measurements are done in nanoseconds, hence this function is not
	 * suitable for timeouts spanning several years.</p>
	 * @return {@code true} if all {@code tasks} complete cleanly. {@code false} otherwise;
	 */
	static boolean awaitMultiple(
			long timeout, TimeUnit unit, boolean continueOnInterrupt, Awaitable... tasks)
			throws InterruptedException {
		final var startTimestamp = System.nanoTime();
		var remainingTime = TimeUnit.NANOSECONDS.convert(timeout, unit);
		var allCompleted = true;
		InterruptedException interrupted = null;
		for (var task: tasks) {
			try {
				allCompleted &= task.await(remainingTime, TimeUnit.NANOSECONDS);
				if (interrupted == null && timeout != 0l) {
					remainingTime -= System.nanoTime() - startTimestamp;
					if (remainingTime < 1l) remainingTime = 1l;
				}
			} catch (InterruptedException e) {
				if ( ! continueOnInterrupt) throw e;
				allCompleted = false;
				remainingTime = 1l;
				interrupted = e;
			}
		}
		if (interrupted != null) throw interrupted;
		return allCompleted;
	}



	@FunctionalInterface
	interface InMillis {
		boolean await(long timeoutMillis) throws InterruptedException;
	}



	/**
	 * Calls {@link #awaitMultiple(long, TimeUnit, boolean, Awaitable...)
	 * awaitMultiple(timeoutMillis, TimeUnit.MILLISECONDS, true, tasks)} converting {@code tasks}
	 * using {@link #toAwaitable(InMillis)}.
	 */
	static boolean awaitMultiple(long timeoutMillis, Awaitable.InMillis... tasks)
			throws InterruptedException {
		return Awaitable.awaitMultiple(
			timeoutMillis,
			TimeUnit.MILLISECONDS,
			true,
			Arrays.stream(tasks).map((task) -> toAwaitable(task)).toArray(Awaitable[]::new)
		);
	}



	/**
	 * Converts given {@link Awaitable.InMillis} to {@link Awaitable}.
	 * Timeout supplied to {@link Awaitable#await(long, TimeUnit)} is converted to millis using
	 * {@link TimeUnit#convert(long, TimeUnit)}, except when it is smaller than 1ms yet non-zero,
	 * in which case it will be rounded up to 1ms.
	 */
	static Awaitable toAwaitable(Awaitable.InMillis task) {
		return (timeout, unit) -> task.await(
				timeout == 0l ? 0l : Math.max(1l, TimeUnit.MILLISECONDS.convert(timeout, unit)));
	}



	static Awaitable.InMillis ofJoin(Thread thread) {
		return (timeoutMillis) -> {
			thread.join(timeoutMillis);
			return ! thread.isAlive();
		};
	}



	static Awaitable.InMillis ofAwaitTermination(ExecutorService executor) {
		return (timeoutMillis) -> executor.awaitTermination(timeoutMillis, TimeUnit.MILLISECONDS);
	}
}
