// Copyright (c) Piotr Morgwai Kotarbinski, Licensed under the Apache License, Version 2.0
package pl.morgwai.base.concurrent;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
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
	 * suitable for timeouts spanning several decades (not that it would make much sense, but I'm
	 * just sayin...&nbsp;;-)&nbsp;&nbsp;).</p>
	 * @return an empty list if all tasks completed, list of uncompleted tasks otherwise.
	 */
	static List<Awaitable> awaitMultiple(
			long timeout, TimeUnit unit, boolean continueOnInterrupt, Awaitable... tasks)
			throws InterruptedException {
		final var startTimestamp = System.nanoTime();
		var remainingTime =  unit.toNanos(timeout);
		final var uncompleted = new LinkedList<Awaitable>();
		InterruptedException interrupted = null;
		for (var task: tasks) {
			try {
				if ( ! task.await(remainingTime, TimeUnit.NANOSECONDS)) uncompleted.add(task);
				if (interrupted == null && timeout != 0l) {
					remainingTime -= System.nanoTime() - startTimestamp;
					if (remainingTime < 1l) remainingTime = 1l;
				}
			} catch (InterruptedException e) {
				if ( ! continueOnInterrupt) throw e;
				remainingTime = 1l;
				interrupted = e;
			}
		}
		if (interrupted != null) throw interrupted;
		return uncompleted;
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
	static List<Awaitable> awaitMultiple(long timeoutMillis, Awaitable.InMillis... tasks)
			throws InterruptedException {
		return Awaitable.awaitMultiple(
			timeoutMillis,
			TimeUnit.MILLISECONDS,
			true,
			Arrays.stream(tasks).map((task) -> toAwaitable(task)).toArray(Awaitable[]::new)
		);
	}



	/**
	 * Converts given {@link Awaitable.InMillis} to {@link Awaitable} unless {@code task} already
	 * implements {@link Awaitable}.
	 * <p>
	 * Timeout supplied to {@link Awaitable#await(long, TimeUnit)} is converted to millis using
	 * {@link TimeUnit#convert(long, TimeUnit)}, except when it is smaller than 1ms yet non-zero,
	 * in which case it will be rounded up to 1ms.</p>
	 */
	static Awaitable toAwaitable(Awaitable.InMillis task) {
		if (task instanceof Awaitable) return (Awaitable) task;
		return (timeout, unit) -> task.await(
				timeout == 0l ? 0l : Math.max(1l, unit.toMillis(timeout)));
	}



	class GenericAwaitable<T> implements Awaitable, Awaitable.InMillis {

		T subject;
		public T getSubject() { return subject; }

		Awaitable awaitHandler;



		public GenericAwaitable(T subject, Awaitable awaitHandler) {
			this.subject = subject;
			this.awaitHandler = awaitHandler;
		}



		@Override
		public boolean await(long timeoutMillis) throws InterruptedException {
			return await(timeoutMillis, TimeUnit.MILLISECONDS);
		}



		@Override
		public boolean await(long timeout, TimeUnit unit) throws InterruptedException {
			return awaitHandler.await(timeout, unit);
		}



		@Override
		public String toString() {
			return "GenericAwaitable { subject = " + subject + '}';
		}
	}



	static GenericAwaitable<Thread> ofJoin(Thread thread) {
		return new GenericAwaitable<>(
			thread,
			(timeout, unit) -> {
				final var timeoutMillis = unit.toMillis(timeout);
				if (timeout == 0l || unit.ordinal() >= TimeUnit.MILLISECONDS.ordinal()) {
					thread.join(timeoutMillis);
				} else {
					thread.join(timeoutMillis, (int) (unit.toNanos(timeout) % 1000l));
				}
				return ! thread.isAlive();
			}
		);
	}



	static GenericAwaitable<ExecutorService> ofAwaitTermination(ExecutorService executor) {
		return new GenericAwaitable<>(
				executor,
				(timeout, unit) -> executor.awaitTermination(timeout, unit));
	}
}
