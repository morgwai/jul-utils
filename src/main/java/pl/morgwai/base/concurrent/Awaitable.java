// Copyright (c) Piotr Morgwai Kotarbinski, Licensed under the Apache License, Version 2.0
package pl.morgwai.base.concurrent;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Stream;



/**
 * An object performing {@link #await(long) timed blocking operation}, such as
 * {@link Thread#join(long)}, {@link Object#wait(long)},
 * {@link ExecutorService#awaitTermination(long, TimeUnit)} etc.
 * @see #awaitMultiple(Function, long, TimeUnit, boolean, Stream)
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



		/**
		 * Calls {@link #await(long, TimeUnit) await(timeoutMillis, TimeUnit.MILLISECONDS)}.
		 */
		@Override
		default boolean await(long timeoutMillis) throws InterruptedException {
			return await(timeoutMillis, TimeUnit.MILLISECONDS);
		}



		/**
		 * Returns this.
		 */
		@Override
		default Awaitable.WithUnit toAwaitableWithUnit() {
			return this;
		}
	}



	/**
	 * Creates {@link Awaitable.WithUnit} of {@link Thread#join(long, int) joining a thread}.
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
		long timeout,
		TimeUnit unit,
		boolean continueOnInterrupt,
		Stream<Map.Entry<T, Awaitable>> taskEntries
	) throws CombinedInterruptedException {
		final var startTimestamp = System.nanoTime();
		var remainingTime =  unit.toNanos(timeout);
		final var uncompleted = new LinkedList<T>();
		final var taskIterator = taskEntries.iterator();
		boolean interrupted = false;
		while (taskIterator.hasNext()) {
			final var taskEntry = taskIterator.next();
			try {
				if ( ! taskEntry.getValue().toAwaitableWithUnit()
						.await(remainingTime, TimeUnit.NANOSECONDS)) {
					uncompleted.add(taskEntry.getKey());
				}
				if (timeout != 0l && ! interrupted) {
					remainingTime -= System.nanoTime() - startTimestamp;
					if (remainingTime < 1l) remainingTime = 1l;
				}
			} catch (InterruptedException e) {
				uncompleted.add(taskEntry.getKey());
				if ( ! continueOnInterrupt) {
					while (taskIterator.hasNext()) uncompleted.add(taskIterator.next().getKey());
					throw new CombinedInterruptedException(uncompleted);
				}
				remainingTime = 1l;
				interrupted = true;
			}
		}
		if (interrupted) throw new CombinedInterruptedException(uncompleted);
		return uncompleted;
	}



	/**
	 * An {@link InterruptedException} that contains the {@link #getUncompleted() list of tasks}
	 * passed to {@link Awaitable#awaitMultiple(Function, long, TimeUnit, boolean, Stream)} that
	 * were not completed due to an interrupt.
	 */
	class CombinedInterruptedException extends InterruptedException {

		final List<?> uncompleted;
		public List<?> getUncompleted() { return uncompleted; }

		public CombinedInterruptedException(List<?> uncompleted) {
			this.uncompleted = uncompleted;
		}

		private static final long serialVersionUID = 1745601970917052988L;
	}



	/**
	 * Calls {@link #awaitMultiple(Function, long, TimeUnit, boolean, Stream)}.
	 */
	static <T> List<T> awaitMultiple(
		long timeout, TimeUnit unit, Stream<Map.Entry<T, Awaitable>> taskEntries
	) throws CombinedInterruptedException {
		return awaitMultiple(timeout, unit, true, taskEntries);
	}

	/**
	 * Calls {@link #awaitMultiple(Function, long, TimeUnit, boolean, Stream)}.
	 */
	static <T> List<T> awaitMultiple(
		long timeout, TimeUnit unit, List<Map.Entry<T, Awaitable>> taskEntries
	) throws CombinedInterruptedException {
		return awaitMultiple(timeout, unit, true, taskEntries.stream());
	}

	/**
	 * Calls {@link #awaitMultiple(Function, long, TimeUnit, boolean, Stream)}.
	 */
	@SafeVarargs
	static <T> List<T> awaitMultiple(
		long timeout, TimeUnit unit, Map.Entry<T, Awaitable>... taskEntries
	) throws CombinedInterruptedException {
		return awaitMultiple(timeout, unit, true, Arrays.stream(taskEntries));
	}

	/**
	 * Calls {@link #awaitMultiple(Function, long, TimeUnit, boolean, Stream)}.
	 */
	static <T> List<T> awaitMultiple(
		long timeoutMillis, Stream<Map.Entry<T, Awaitable>> taskEntries
	) throws CombinedInterruptedException {
		return awaitMultiple(timeoutMillis, TimeUnit.MILLISECONDS, true, taskEntries);
	}

	/**
	 * Calls {@link #awaitMultiple(Function, long, TimeUnit, boolean, Stream)}.
	 */
	static <T> List<T> awaitMultiple(
		long timeoutMillis, List<Map.Entry<T, Awaitable>> taskEntries
	) throws CombinedInterruptedException {
		return awaitMultiple(timeoutMillis, TimeUnit.MILLISECONDS, true, taskEntries.stream());
	}

	/**
	 * Calls {@link #awaitMultiple(Function, long, TimeUnit, boolean, Stream)}.
	 */
	@SafeVarargs
	static <T> List<T> awaitMultiple(
		long timeoutMillis, Map.Entry<T, Awaitable>... taskEntries
	) throws CombinedInterruptedException {
		return awaitMultiple(
				timeoutMillis, TimeUnit.MILLISECONDS, true, Arrays.stream(taskEntries));
	}



	/**
	 * Calls {@link #awaitMultiple(Function, long, TimeUnit, boolean, Stream)}.
	 */
	static <T> List<T> awaitMultiple(
		long timeout, TimeUnit unit, Function<T, Awaitable> adapter, Stream<T> objects
	) throws CombinedInterruptedException {
		return awaitMultiple(timeout, unit, true, objects.map(
				(object) -> Map.entry(object, adapter.apply(object))));
	}

	/**
	 * Calls {@link #awaitMultiple(Function, long, TimeUnit, boolean, Stream)}.
	 */
	static <T> List<T> awaitMultiple(
		long timeout, TimeUnit unit, Function<T, Awaitable> adapter, List<T> objects
	) throws CombinedInterruptedException {
		return awaitMultiple(timeout, unit, true, objects.stream().map(
				(object) -> Map.entry(object, adapter.apply(object))));
	}

	/**
	 * Calls {@link #awaitMultiple(Function, long, TimeUnit, boolean, Stream)}.
	 */
	@SafeVarargs
	static <T> List<T> awaitMultiple(
		long timeout, TimeUnit unit, Function<T, Awaitable> adapter, T... objects
	) throws CombinedInterruptedException {
		return awaitMultiple(timeout, unit, true, Arrays.stream(objects).map(
				(object) -> Map.entry(object, adapter.apply(object))));
	}



	/**
	 * Calls {@link #awaitMultiple(Function, long, TimeUnit, boolean, Stream)}.
	 */
	static <T> List<T> awaitMultiple(
		long timeoutMillis, Function<T, Awaitable> adapter, Stream<T> objects
	) throws CombinedInterruptedException {
		return Awaitable.awaitMultiple(
				timeoutMillis,
				TimeUnit.MILLISECONDS,
				true,
				objects
					.map((object) -> Map.entry(object, adapter.apply(object))));
	}

	/**
	 * Calls {@link #awaitMultiple(Function, long, TimeUnit, boolean, Stream)}.
	 */
	static <T> List<T> awaitMultiple(
		long timeoutMillis, Function<T, Awaitable> adapter, List<T> objects
	) throws CombinedInterruptedException {
		return Awaitable.awaitMultiple(
				timeoutMillis,
				TimeUnit.MILLISECONDS,
				true,
				objects.stream()
					.map((object) -> Map.entry(object, adapter.apply(object))));
	}

	/**
	 * Calls {@link #awaitMultiple(Function, long, TimeUnit, boolean, Stream)}.
	 */
	@SafeVarargs
	static <T> List<T> awaitMultiple(
		long timeoutMillis, Function<T, Awaitable> adapter, T... objects
	) throws CombinedInterruptedException {
		return Awaitable.awaitMultiple(
				timeoutMillis,
				TimeUnit.MILLISECONDS,
				true,
				Arrays.stream(objects)
					.map((object) -> Map.entry(object, adapter.apply(object))));
	}



	/**
	 * Calls {@link #awaitMultiple(Function, long, TimeUnit, boolean, Stream)}.
	 */
	// Erasure of method is the same as another method in type Awaitable: java generics SUCK!!!!
	static boolean awaitMultipleTasks(long timeout, TimeUnit unit, Stream<Awaitable> tasks)
			throws CombinedInterruptedException {
		return awaitMultiple(timeout, unit, Awaitable::toAwaitableWithUnit, tasks).isEmpty();
	}

	/**
	 * Calls {@link #awaitMultiple(Function, long, TimeUnit, boolean, Stream)}.
	 */
	static boolean awaitMultipleTasks(long timeout, TimeUnit unit, List<Awaitable> tasks)
			throws CombinedInterruptedException {
		return awaitMultiple(timeout, unit, Awaitable::toAwaitableWithUnit, tasks).isEmpty();
	}

	/**
	 * Calls {@link #awaitMultiple(Function, long, TimeUnit, boolean, Stream)}.
	 */
	static boolean awaitMultipleTasks(long timeout, TimeUnit unit, Awaitable... tasks)
			throws CombinedInterruptedException {
		return awaitMultiple(timeout, unit, Awaitable::toAwaitableWithUnit, tasks).isEmpty();
	}

	/**
	 * Calls {@link #awaitMultiple(Function, long, TimeUnit, boolean, Stream)}.
	 */
	static boolean awaitMultipleTasks(long timeoutMillis, Stream<Awaitable> tasks)
			throws CombinedInterruptedException {
		return awaitMultiple(timeoutMillis, Awaitable::toAwaitableWithUnit, tasks).isEmpty();
	}

	/**
	 * Calls {@link #awaitMultiple(Function, long, TimeUnit, boolean, Stream)}.
	 */
	static boolean awaitMultipleTasks(long timeoutMillis, List<Awaitable> tasks)
			throws CombinedInterruptedException {
		return awaitMultiple(timeoutMillis, Awaitable::toAwaitableWithUnit, tasks).isEmpty();
	}

	/**
	 * Calls {@link #awaitMultiple(Function, long, TimeUnit, boolean, Stream)}.
	 */
	static boolean awaitMultipleTasks(long timeoutMillis, Awaitable... tasks)
			throws CombinedInterruptedException {
		return awaitMultiple(timeoutMillis, Awaitable::toAwaitableWithUnit, tasks).isEmpty();
	}
}
