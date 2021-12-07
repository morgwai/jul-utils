// Copyright (c) Piotr Morgwai Kotarbinski, Licensed under the Apache License, Version 2.0
package pl.morgwai.base.concurrent;

import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;



/**
 * An object performing {@link #await(long) timed blocking operation}, such as
 * {@link Thread#join(long)}, {@link Object#wait(long)},
 * {@link ExecutorService#awaitTermination(long, TimeUnit)} etc.
 * @see #awaitMultiple(long, TimeUnit, boolean, Iterator))
 */
@FunctionalInterface
public interface Awaitable {



	/**
	 * A timed blocking operation}, such as {@link Thread#join(long)}, {@link Object#wait(long)},
	 * {@link ExecutorService#awaitTermination(long, TimeUnit)} etc.
	 */
	boolean await(long timeoutMillis) throws InterruptedException;



	/**
	 * Adapts this {@code Awaitable} to {@link Awaitable.WithUnit}.
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

		/**
		 * A version of {@link #await(long)} method that additionally accepts {@link TimeUnit}
		 * param.
		 */
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
	 * Awaits for multiple timed blocking operations} specified by {@code operationEntries}.
	 * Each entry maps an object on which the awaiting operation should be performed (for example a
	 * {@link Thread} to be {@link Thread#join(long) joined} or an {@link ExecutorService executor}
	 * to be {@link ExecutorService#awaitTermination(long, TimeUnit) terminated}) to
	 * a {@link Awaitable closure performing the given operation}.
	 * <p>
	 * If {@code timeout} passes before all operations are completed, continues to perform remaining
	 * ones with timeout of 1 nanosecond.<br/>
	 * If {@code continueOnInterrupt} is {@code true}, does so also in case
	 * {@link InterruptedException} is thrown by any of the operations.</p>
	 * <p>
	 * Note: internally all time measurements are done in nanoseconds, hence this function is not
	 * suitable for timeouts spanning several decades (not that it would make much sense, but I'm
	 * just sayin...&nbsp;;-)&nbsp;&nbsp;).</p>
	 * <p>
	 * Note: this is a "low-level" core version: there are several "frontend" functions defined in
	 * this class with more convenient API divided into 3 families:<ul>
	 *   <li>a family that accepts varargs of {@code operationEntries}
	 *     ({@code Map.Entry<T, Awaitable>}). This family returns a list of entry keys for which
	 *     their respective {@link Awaitable operations} failed (returned {@code false})</li>
	 *   <li>a family that accepts a {@link List} of objects and an {@code adapter} {@link Function}
	 *     that returns an {@link Awaitable operation} for a given object. Similarly to the
	 *     previous family, this one returns a list of objects for which their respective
	 *     {@link Awaitable operations} failed (returned {@code false})</li>
	 *   <li>a family that accepts varargs of {@link Awaitable operations}. This family returns
	 *     {@code true} if all {@code operations} succeeded, {@code false} otherwise.</li>
	 * </ul>
	 * Within each family there are variants that either accept {@code (long timeout, TimeUnit
	 * unit)} params or a single {@code long timeoutMillis} param and either accept
	 * {@code boolean continueOnInterrupt} param or always pass {@code true}.</p>
	 * @return an empty list if all tasks completed, list of uncompleted tasks otherwise.
	 * @throws AwaitInterruptedException if any of the operations throws
	 *     {@link InterruptedException}.
	 */
	static <T> List<T> awaitMultiple(
		long timeout,
		TimeUnit unit,
		boolean continueOnInterrupt,
		Iterator<Map.Entry<T, Awaitable>> operationEntries
	) throws AwaitInterruptedException {
		final var startTimestamp = System.nanoTime();
		var remainingTime =  unit.toNanos(timeout);
		final var failedTasks = new LinkedList<T>();
		final var interruptedTasks = new LinkedList<T>();
		boolean interrupted = false;
		while (operationEntries.hasNext()) {
			final var operationEntry = operationEntries.next();
			try {
				if ( ! operationEntry.getValue().toAwaitableWithUnit()
						.await(remainingTime, TimeUnit.NANOSECONDS)) {
					failedTasks.add(operationEntry.getKey());
				}
				if (timeout != 0l && ! interrupted) {
					remainingTime -= System.nanoTime() - startTimestamp;
					if (remainingTime < 1l) remainingTime = 1l;
				}
			} catch (InterruptedException e) {
				interruptedTasks.add(operationEntry.getKey());
				if ( ! continueOnInterrupt) {
					throw new AwaitInterruptedException(
							failedTasks, interruptedTasks, operationEntries);
				}
				remainingTime = 1l;
				interrupted = true;
			}
		}
		if (interrupted) {
			throw new AwaitInterruptedException(
					failedTasks, interruptedTasks, operationEntries);
		}
		return failedTasks;
	}



	/**
	 * An {@link InterruptedException} that contains await state of operations passed to one of
	 * {@link Awaitable#awaitMultiple(long, TimeUnit, boolean, Iterator) awaitMultipe(...)
	 * functions}.
	 */
	class AwaitInterruptedException extends InterruptedException {

		final List<?> failed;
		public List<?> getFailed() { return failed; }

		final List<?> interrupted;
		public List<?> getInterrupted() { return interrupted; }

		final Iterator<Map.Entry<?, Awaitable>> unexecuted;
		public Iterator<Map.Entry<?, Awaitable>> getUnexecuted() { return unexecuted; }

		public <T> AwaitInterruptedException(
				List<T> failed, List<T> interrupted, Iterator<Map.Entry<T, Awaitable>> unexecuted) {
			this.failed = failed;
			this.interrupted = interrupted;
			@SuppressWarnings("unchecked") final Iterator<Map.Entry<?, Awaitable>> tmp =
					(Iterator<Entry<?, Awaitable>>) (Iterator<?>) unexecuted;
			this.unexecuted = tmp;
		}

		private static final long serialVersionUID = 4840433122434594416L;
	}



	/**
	 * @see #awaitMultiple(long, TimeUnit, boolean, Iterator)
	 */
	@SafeVarargs
	static <T> List<T> awaitMultiple(
		long timeout,
		TimeUnit unit,
		boolean continueOnInterrupt,
		Map.Entry<T, Awaitable>... operationEntries
	) throws AwaitInterruptedException {
		return awaitMultiple(
				timeout, unit, continueOnInterrupt, Arrays.asList(operationEntries).iterator());
	}

	/**
	 * @see #awaitMultiple(long, TimeUnit, boolean, Iterator)
	 */
	@SafeVarargs
	static <T> List<T> awaitMultiple(
		long timeoutMillis,
		boolean continueOnInterrupt,
		Map.Entry<T, Awaitable>... operationEntries
	) throws AwaitInterruptedException {
		return awaitMultiple(
				timeoutMillis,
				TimeUnit.MILLISECONDS,
				continueOnInterrupt,
				Arrays.asList(operationEntries).iterator());
	}

	/**
	 * @see #awaitMultiple(long, TimeUnit, boolean, Iterator)
	 */
	@SafeVarargs
	static <T> List<T> awaitMultiple(
		long timeout, TimeUnit unit, Map.Entry<T, Awaitable>... operationEntries
	) throws AwaitInterruptedException {
		return awaitMultiple(timeout, unit, true, Arrays.asList(operationEntries).iterator());
	}

	/**
	 * @see #awaitMultiple(long, TimeUnit, boolean, Iterator)
	 */
	@SafeVarargs
	static <T> List<T> awaitMultiple(
		long timeoutMillis, Map.Entry<T, Awaitable>... operationEntries
	) throws AwaitInterruptedException {
		return awaitMultiple(
				timeoutMillis,
				TimeUnit.MILLISECONDS,
				true,
				Arrays.asList(operationEntries).iterator());
	}



	/**
	 * @see #awaitMultiple(long, TimeUnit, boolean, Iterator)
	 */
	static <T> List<T> awaitMultiple(
		long timeout,
		TimeUnit unit,
		boolean continueOnInterrupt,
		Function<? super T, Awaitable> adapter,
		List<T> objects
	) throws AwaitInterruptedException {
		return awaitMultiple(
				timeout,
				unit,
				continueOnInterrupt,
				objects.stream()
					.map((object) -> Map.entry(object, adapter.apply(object)))
					.iterator());
	}

	/**
	 * @see #awaitMultiple(long, TimeUnit, boolean, Iterator)
	 */
	static <T> List<T> awaitMultiple(
		long timeoutMillis,
		boolean continueOnInterrupt,
		Function<? super T, Awaitable> adapter,
		List<T> objects
	) throws AwaitInterruptedException {
		return Awaitable.awaitMultiple(
				timeoutMillis, TimeUnit.MILLISECONDS, continueOnInterrupt, adapter, objects);
	}

	/**
	 * @see #awaitMultiple(long, TimeUnit, boolean, Iterator)
	 */
	static <T> List<T> awaitMultiple(
		long timeout, TimeUnit unit, Function<? super T, Awaitable> adapter, List<T> objects
	) throws AwaitInterruptedException {
		return Awaitable.awaitMultiple(timeout, unit, true, adapter, objects);
	}

	/**
	 * @see #awaitMultiple(long, TimeUnit, boolean, Iterator)
	 */
	static <T> List<T> awaitMultiple(
		long timeoutMillis, Function<? super T, Awaitable> adapter, List<T> objects
	) throws AwaitInterruptedException {
		return Awaitable.awaitMultiple(
				timeoutMillis, TimeUnit.MILLISECONDS, true, adapter, objects);
	}



	/**
	 * @see #awaitMultiple(long, TimeUnit, boolean, Iterator)
	 */
	static boolean awaitMultiple(
		long timeout, TimeUnit unit, boolean continueOnInterrupt, Awaitable... operations
	) throws AwaitInterruptedException {
		return (
			awaitMultiple(
				timeout,
				unit,
				continueOnInterrupt,
				Arrays.stream(operations)
					.map((operation) -> Map.entry(operation, operation))
					.iterator()
			).isEmpty()
		);
	}

	/**
	 * @see #awaitMultiple(long, TimeUnit, boolean, Iterator)
	 */
	static boolean awaitMultiple(
		long timeoutMillis, boolean continueOnInterrupt, Awaitable... operations
	) throws AwaitInterruptedException {
		return awaitMultiple(timeoutMillis, TimeUnit.MILLISECONDS, continueOnInterrupt, operations);
	}

	/**
	 * @see #awaitMultiple(long, TimeUnit, boolean, Iterator)
	 */
	static boolean awaitMultiple(
		long timeout, TimeUnit unit, boolean continueOnInterrupt, Awaitable.WithUnit... operations
	) throws AwaitInterruptedException {
		return awaitMultiple(timeout, unit, continueOnInterrupt, (Awaitable[]) operations);
	}

	/**
	 * @see #awaitMultiple(long, TimeUnit, boolean, Iterator)
	 */
	static boolean awaitMultiple(
		long timeoutMillis, boolean continueOnInterrupt, Awaitable.WithUnit... operations
	) throws AwaitInterruptedException {
		return awaitMultiple(
			timeoutMillis, TimeUnit.MILLISECONDS, continueOnInterrupt, (Awaitable[]) operations
		);
	}

	/**
	 * @see #awaitMultiple(long, TimeUnit, boolean, Iterator)
	 */
	static boolean awaitMultiple(long timeout, TimeUnit unit, Awaitable... operations)
			throws AwaitInterruptedException {
		return awaitMultiple(timeout, unit, true, operations);
	}

	/**
	 * @see #awaitMultiple(long, TimeUnit, boolean, Iterator)
	 */
	static boolean awaitMultiple(long timeoutMillis, Awaitable... operations)
			throws AwaitInterruptedException {
		return awaitMultiple(timeoutMillis, TimeUnit.MILLISECONDS, true, operations);
	}

	/**
	 * @see #awaitMultiple(long, TimeUnit, boolean, Iterator)
	 */
	static boolean awaitMultiple(long timeout, TimeUnit unit, Awaitable.WithUnit... operations)
			throws AwaitInterruptedException {
		return awaitMultiple(timeout, unit, true, (Awaitable[]) operations);
	}

	/**
	 * @see #awaitMultiple(long, TimeUnit, boolean, Iterator)
	 */
	static boolean awaitMultiple(long timeoutMillis, Awaitable.WithUnit... operations)
			throws AwaitInterruptedException {
		return awaitMultiple(timeoutMillis, TimeUnit.MILLISECONDS, true, (Awaitable[]) operations);
	}
}
