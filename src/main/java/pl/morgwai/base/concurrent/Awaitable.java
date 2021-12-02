// Copyright (c) Piotr Morgwai Kotarbinski, Licensed under the Apache License, Version 2.0
package pl.morgwai.base.concurrent;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Stream;



/**
 * An object performing {@link #await(long) timed blocking operation}, such as
 * {@link Thread#join(long)}, {@link Object#wait(long)},
 * {@link ExecutorService#awaitTermination(long, TimeUnit)} etc.
 * @see #awaitMultiple(long, TimeUnit, boolean, Function, Stream)
 */
@FunctionalInterface
public interface Awaitable {



	/**
	 * A timed blocking operation}, such as {@link Thread#join(long)}, {@link Object#wait(long)},
	 * {@link ExecutorService#awaitTermination(long, TimeUnit)} etc.
	 */
	boolean await(long timeoutMillis) throws InterruptedException;



	/**
	 * Adapts {@link Awaitable} to {@link Awaitable.WithUnit}.
	 * <p>
	 * Timeout supplied to {@link Awaitable.WithUnit#await(long, TimeUnit)} is converted to millis
	 * using {@link TimeUnit#convert(long, TimeUnit)}, except when it is smaller than 1ms yet
	 * non-zero, in which case it will be rounded up to 1ms.</p>
	 */
	default Awaitable.WithUnit toAwaitableWithUnit() {
		return (timeout, unit) -> await(
				timeout == 0l ? 0l : Math.max(1l, unit.toMillis(timeout)));
	}



	/**
	 * A more precise and flexible {@link Awaitable}.
	 */
	@FunctionalInterface
	interface WithUnit extends Awaitable {

		boolean await(long timeout, TimeUnit unit) throws InterruptedException;



		@Override
		default boolean await(long timeoutMillis) throws InterruptedException {
			return await(timeoutMillis, TimeUnit.MILLISECONDS);
		}



		@Override
		default Awaitable.WithUnit toAwaitableWithUnit() {
			return this;
		}
	}



	/**
	 * Awaits for multiple timed blocking operations, such as {@link Thread#join(long)},
	 * {@link Object#wait(long)}, {@link ExecutorService#awaitTermination(long, TimeUnit)} etc,
	 * specified by {@code toAwaitable}.
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
	static <T> List<T> awaitMultiple(
		Function<T, Awaitable.WithUnit> adapter,
		long timeout,
		TimeUnit unit,
		boolean continueOnInterrupt,
		Stream<T> objects
	) throws CombinedInterruptedException {
		final var startTimestamp = System.nanoTime();
		var remainingTime =  unit.toNanos(timeout);
		final var uncompleted = new LinkedList<T>();
		final var iterator = objects.iterator();
		boolean interrupted = false;
		while (iterator.hasNext()) {
			final var object = iterator.next();
			try {
				if ( ! adapter.apply(object).await(remainingTime, TimeUnit.NANOSECONDS)) {
					uncompleted.add(object);
				}
				if (timeout != 0l && ! interrupted) {
					remainingTime -= System.nanoTime() - startTimestamp;
					if (remainingTime < 1l) remainingTime = 1l;
				}
			} catch (InterruptedException e) {
				uncompleted.add(object);
				if ( ! continueOnInterrupt) {
					while (iterator.hasNext()) uncompleted.add(iterator.next());
					throw new CombinedInterruptedException(uncompleted);
				}
				remainingTime = 1l;
				interrupted = true;
			}
		}
		if (interrupted) throw new CombinedInterruptedException(uncompleted);
		return uncompleted;
	}



	class CombinedInterruptedException extends InterruptedException {

		final List<?> uncompleted;
		public List<?> getUncompleted() { return uncompleted; }

		public CombinedInterruptedException(List<?> uncompleted) {
			this.uncompleted = uncompleted;
		}

		private static final long serialVersionUID = 1745601970917052988L;
	}



	static <T> List<T> awaitMultiple(
		Function<T, Awaitable.WithUnit> adapter, long timeout, TimeUnit unit, Stream<T> objects
	) throws CombinedInterruptedException {
		return awaitMultiple(adapter, timeout, unit, true, objects);
	}

	static <T> List<T> awaitMultiple(
		Function<T, Awaitable.WithUnit> adapter, long timeout, TimeUnit unit, List<T> objects
	) throws CombinedInterruptedException {
		return awaitMultiple(adapter, timeout, unit, true, objects.stream());
	}

	@SafeVarargs
	static <T> List<T> awaitMultiple(
		Function<T, Awaitable.WithUnit> adapter, long timeout, TimeUnit unit, T... objects
	) throws CombinedInterruptedException {
		return awaitMultiple(adapter, timeout, unit, true, Arrays.stream(objects));
	}

	static <TaskT extends Awaitable.WithUnit> List<TaskT> awaitMultiple(
		long timeout, TimeUnit unit, Stream<TaskT> tasks
	) throws CombinedInterruptedException {
		return awaitMultiple((t) -> t, timeout, unit, true, tasks);
	}

	static <TaskT extends Awaitable.WithUnit> List<TaskT> awaitMultiple(
		long timeout, TimeUnit unit, List<TaskT> tasks
	) throws CombinedInterruptedException {
		return awaitMultiple((t) -> t, timeout, unit, true, tasks.stream());
	}

	@SafeVarargs
	static <TaskT extends Awaitable.WithUnit> List<TaskT> awaitMultiple(
		long timeout, TimeUnit unit, TaskT... tasks
	) throws CombinedInterruptedException {
		return awaitMultiple((t) -> t, timeout, unit, true, Arrays.stream(tasks));
	}



	static <T> List<T> awaitMultiple(
			Function<T, Awaitable.WithUnit> adapter, long timeoutMillis, Stream<T> objects)
			throws CombinedInterruptedException {
		return Awaitable.awaitMultiple(
				(object) -> adapter.apply(object).toAwaitableWithUnit(),
				timeoutMillis,
				TimeUnit.MILLISECONDS,
				true,
				objects);
	}

	static <T> List<T> awaitMultiple(
			Function<T, Awaitable.WithUnit> adapter, long timeoutMillis, List<T> objects)
			throws CombinedInterruptedException {
		return Awaitable.awaitMultiple(
				(object) -> adapter.apply(object).toAwaitableWithUnit(),
				timeoutMillis,
				TimeUnit.MILLISECONDS,
				true,
				objects.stream());
	}

	@SafeVarargs
	static <T> List<T> awaitMultiple(
			Function<T, Awaitable.WithUnit> adapter, long timeoutMillis, T... objects)
			throws CombinedInterruptedException {
		return Awaitable.awaitMultiple(
				(object) -> adapter.apply(object).toAwaitableWithUnit(),
				timeoutMillis,
				TimeUnit.MILLISECONDS,
				true,
				Arrays.stream(objects));
	}

	static <TaskT extends Awaitable> List<TaskT> awaitMultiple(
			long timeoutMillis, Stream<TaskT> tasks)
			throws CombinedInterruptedException {
		return Awaitable.awaitMultiple(
				(task) -> task.toAwaitableWithUnit(),
				timeoutMillis,
				TimeUnit.MILLISECONDS,
				true,
				tasks);
	}

	static <TaskT extends Awaitable> List<TaskT> awaitMultiple(
			long timeoutMillis, List<TaskT> tasks)
			throws CombinedInterruptedException {
		return Awaitable.awaitMultiple(
				(task) -> task.toAwaitableWithUnit(),
				timeoutMillis,
				TimeUnit.MILLISECONDS,
				true,
				tasks.stream());
	}

	@SafeVarargs
	static <TaskT extends Awaitable> List<TaskT> awaitMultiple(
			long timeoutMillis, TaskT... tasks)
			throws CombinedInterruptedException {
		return Awaitable.awaitMultiple(
				(task) -> task.toAwaitableWithUnit(),
				timeoutMillis,
				TimeUnit.MILLISECONDS,
				true,
				Arrays.stream(tasks));
	}



	/**
	 * Creates {@link Awaitable.WithUnit} of {@link Thread#join(long) joining a thread}.
	 */
	static Awaitable.WithUnit ofJoin(Thread thread) {
		return (timeout, unit) -> {
				final var timeoutMillis = unit.toMillis(timeout);
				if (timeout == 0l || unit.ordinal() >= TimeUnit.MILLISECONDS.ordinal()) {
					thread.join(timeoutMillis);
				} else {
					thread.join(timeoutMillis, (int) (unit.toNanos(timeout) % 1000l));
				}
				return ! thread.isAlive();
		};
	}



	/**
	 * Creates {@link Awaitable.WithUnit} of
	 * {@link ExecutorService#awaitTermination(long, TimeUnit) termination of an executor}.
	 */
	static Awaitable.WithUnit ofTermination(ExecutorService executor) {
		return (timeout, unit) -> executor.awaitTermination(timeout, unit);
	}
}
