// Copyright (c) Piotr Morgwai Kotarbinski, Licensed under the Apache License, Version 2.0
package pl.morgwai.base.concurrent;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;



/**
 * An object performing {@link #await(long) timed blocking operation}, such as
 * {@link Thread#join(long)}, {@link Object#wait(long)},
 * {@link ExecutorService#awaitTermination(long, TimeUnit)} etc.
 * Useful for awaiting for multiple such operations: see
 * {@link #awaitMultiple(long, TimeUnit, boolean, Iterator) awaitMultiple(...) method family}.
 */
@FunctionalInterface
public interface Awaitable {



	/**
	 * A timed blocking operation}, such as {@link Thread#join(long)}, {@link Object#wait(long)},
	 * {@link ExecutorService#awaitTermination(long, TimeUnit)} etc.
	 * @return {@code true} if operation succeeds before {@code timeoutMillis} passes, {@code false}
	 *     otherwise.
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
		return (timeout, unit) -> await(timeout == 0L ? 0L : Math.max(1L, unit.toMillis(timeout)));
	}



	/**
	 * A more precise and flexible {@link Awaitable}.
	 */
	@FunctionalInterface
	interface WithUnit extends Awaitable {

		/**
		 * A version of {@link #await(long)} method with additional {@link TimeUnit} param.
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
	 * The result is based on {@link Thread#isAlive()}.
	 */
	static Awaitable.WithUnit ofJoin(Thread thread) {
		return (timeout, unit) -> {
			final var timeoutMillis = unit.toMillis(timeout);
			if (timeout == 0L || unit.ordinal() >= TimeUnit.MILLISECONDS.ordinal()) {
				thread.join(timeoutMillis);
			} else {
				thread.join(timeoutMillis, (int) (unit.toNanos(timeout) % 1_000_000L));
			}
			return !thread.isAlive();
		};
	}



	/**
	 * Creates {@link Awaitable.WithUnit} of
	 * {@link ExecutorService#awaitTermination(long, TimeUnit) termination} of {@code executor}.
	 */
	static Awaitable.WithUnit ofTermination(ExecutorService executor) {
		return (timeout, unit) -> {
			executor.shutdown();
			return executor.awaitTermination(timeout, unit);
		};
	}



	/**
	 * Creates {@link Awaitable.WithUnit} of
	 * {@link ExecutorService#awaitTermination(long, TimeUnit) termination} of {@code executor}.
	 * If {@code executor} fails to terminate, {@link ExecutorService#shutdownNow()} is called.
	 */
	static Awaitable.WithUnit ofEnforcedTermination(ExecutorService executor) {
		return (timeout, unit) -> {
			try {
				executor.shutdown();
				return executor.awaitTermination(timeout, unit);
			} finally {
				if ( !executor.isTerminated()) executor.shutdownNow();
			}
		};
	}



	/**
	 * Awaits for multiple timed blocking operations ({@link Awaitable}) specified by
	 * {@code operationEntries}. Each {@link Entry Entry} maps an {@link Entry#getObject() object}
	 * on which an operation should be performed (for example a {@link Thread}
	 * to be {@link Thread#join(long) joined} or an {@link ExecutorService executor} to be
	 * {@link ExecutorService#awaitTermination(long, TimeUnit) terminated})
	 * to a {@link Entry#getOperation() closure performing this operation}.
	 * <p>
	 * If {@code timeout} passes before all operations are completed, continues to perform the
	 * remaining ones with {@code 1} nanosecond timeout.<br/>
	 * If {@code continueOnInterrupt} is {@code true}, does so also in case
	 * {@link InterruptedException} is thrown by any of the operations.<br/>
	 * If {@code timeout} argument is {@code 0} then all operations will receive {@code 0} timeout.
	 * Note that different methods may interpret it in different ways: <i>"return false if cannot
	 * complete operation immediately"</i> like
	 * {@link ExecutorService#awaitTermination(long, TimeUnit)} or <i>"block without a timeout until
	 * operation is completed"</i> like {@link Thread#join(long)}.</p>
	 * <p>
	 * Note: internally all time measurements are done in nanoseconds, hence this function is not
	 * suitable for timeouts spanning several decades (not that it would make much sense, but I'm
	 * just sayin...&nbsp;;-)&nbsp;&nbsp;).</p>
	 * <p>
	 * Note: this is a "low-level" core version: there are several "frontend" functions defined in
	 * this class with more convenient API divided into 3 families:</p>
	 * <ul>
	 *   <li>a family that accepts varargs of {@link Entry operationEntries} that map objects to
	 *     {@link Awaitable Awaitable operations} to be performed. This family returns a
	 *     {@link List} of objects for which their respective {@link Awaitable operations} failed
	 *     (returned {@code false}).</li>
	 *   <li>a family that accepts a {@link List} of objects and an {@link Function adapter
	 *     Function} that returns {@link Awaitable Awaitable operations} for supplied objects.
	 *     Similarly to the previous family, this one returns a {@link List} of objects for which
	 *     their respective {@link Awaitable operations} failed.</li>
	 *   <li>a family that accepts varargs of {@link Awaitable Awaitable operations}. This family
	 *     returns {@code true} if all {@code operations} succeeded, {@code false} otherwise.</li>
	 * </ul>
	 * <p>
	 * Within each family there are variants that either accept {@code (long timeout, TimeUnit
	 * unit)} params or a single {@code long timeoutMillis} param and variants that either accept
	 * {@code boolean continueOnInterrupt} param or always pass {@code true}.</p>
	 * @return an empty {@link List} if all {@link Awaitable operations} completed, otherwise a
	 * {@link List} of object whose operations failed.
	 * @throws AwaitInterruptedException if any of the operations throws
	 *     {@link InterruptedException}.
	 */
	static <T> List<T> awaitMultiple(
		long timeout,
		TimeUnit unit,
		boolean continueOnInterrupt,
		Iterator<Entry<T>> operationEntries
	) throws AwaitInterruptedException {
		final var startNanos = System.nanoTime();
		var remainingNanos =  unit.toNanos(timeout);
		final var failedTasks = new LinkedList<T>();
		final var interruptedTasks = new LinkedList<T>();
		boolean interrupted = false;
		while (operationEntries.hasNext()) {
			final var operationEntry = operationEntries.next();
			try {
				if ( !operationEntry.operation.toAwaitableWithUnit()
						.await(remainingNanos, TimeUnit.NANOSECONDS)) {
					failedTasks.add(operationEntry.object);
				}
				if (remainingNanos > 1L) {
					remainingNanos -= System.nanoTime() - startNanos;
					if (remainingNanos < 1L) remainingNanos = 1L;
				}
			} catch (InterruptedException e) {
				interruptedTasks.add(operationEntry.object);
				if ( !continueOnInterrupt) {
					throw new AwaitInterruptedException(
							failedTasks, interruptedTasks, operationEntries);
				}
				remainingNanos = 1L;
				interrupted = true;
			}
		}
		if (interrupted) {
			throw new AwaitInterruptedException(failedTasks, interruptedTasks, operationEntries);
		}
		return failedTasks;
	}



	/**
	 * Maps {@link #getObject() object} to an {@link #getOperation() Awaitable operation} that one
	 * of {@link Awaitable#awaitMultiple(long, TimeUnit, boolean, Iterator) awaitMultiple(...)}
	 * functions will perform.
	 */
	class Entry<T> {

		final T object;
		public T getObject() { return object; }

		final Awaitable operation;
		public Awaitable getOperation() { return operation; }

		public Entry(T object, Awaitable operation) {
			this.object = object;
			this.operation = operation;
		}
	}

	static <T> Entry<T> entry(T object, Awaitable operation) {
		return new Entry<>(object, operation);
	}



	/**
	 * An {@link InterruptedException} that contains results of
	 * {@link Awaitable Awaitable operations} passed to one of
	 * {@link Awaitable#awaitMultiple(long, TimeUnit, boolean, Iterator) awaitMultipe(...)
	 * functions} that was interrupted.
	 */
	class AwaitInterruptedException extends InterruptedException {

		final List<?> failed;
		public List<?> getFailed() { return failed; }

		final List<?> interrupted;
		public List<?> getInterrupted() { return interrupted; }

		final Iterator<Entry<?>> unexecuted;
		public Iterator<Entry<?>> getUnexecuted() { return unexecuted; }

		public <T> AwaitInterruptedException(
				List<T> failed, List<T> interrupted, Iterator<Entry<T>> unexecuted) {
			this.failed = failed;
			this.interrupted = interrupted;
			@SuppressWarnings("unchecked") final Iterator<Entry<?>> tmp =
					(Iterator<Entry<?>>) (Iterator<?>) unexecuted;
			this.unexecuted = tmp;
		}

		private static final long serialVersionUID = 4840433122434594416L;
	}



	/**
	 * See {@link #awaitMultiple(long, TimeUnit, boolean, Iterator)}.
	 */
	@SafeVarargs
	static <T> List<T> awaitMultiple(
		long timeout, TimeUnit unit, boolean continueOnInterrupt, Entry<T>... operationEntries
	) throws AwaitInterruptedException {
		return awaitMultiple(
				timeout, unit, continueOnInterrupt, Arrays.asList(operationEntries).iterator());
	}

	/**
	 * See {@link #awaitMultiple(long, TimeUnit, boolean, Iterator)}.
	 */
	@SafeVarargs
	static <T> List<T> awaitMultiple(
		long timeoutMillis, boolean continueOnInterrupt, Entry<T>... operationEntries
	) throws AwaitInterruptedException {
		return awaitMultiple(
				timeoutMillis,
				TimeUnit.MILLISECONDS,
				continueOnInterrupt,
				Arrays.asList(operationEntries).iterator());
	}

	/**
	 * See {@link #awaitMultiple(long, TimeUnit, boolean, Iterator)}.
	 */
	@SafeVarargs
	static <T> List<T> awaitMultiple(long timeout, TimeUnit unit, Entry<T>... operationEntries)
			throws AwaitInterruptedException {
		return awaitMultiple(timeout, unit, true, Arrays.asList(operationEntries).iterator());
	}

	/**
	 * See {@link #awaitMultiple(long, TimeUnit, boolean, Iterator)}.
	 */
	@SafeVarargs
	static <T> List<T> awaitMultiple(long timeoutMillis, Entry<T>... operationEntries)
			throws AwaitInterruptedException {
		return awaitMultiple(
				timeoutMillis,
				TimeUnit.MILLISECONDS,
				true,
				Arrays.asList(operationEntries).iterator());
	}



	/**
	 * See {@link #awaitMultiple(long, TimeUnit, boolean, Iterator)}.
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
					.map((object) -> entry(object, adapter.apply(object)))
					.iterator());
	}

	/**
	 * See {@link #awaitMultiple(long, TimeUnit, boolean, Iterator)}.
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
	 * See {@link #awaitMultiple(long, TimeUnit, boolean, Iterator)}.
	 */
	static <T> List<T> awaitMultiple(
		long timeout, TimeUnit unit, Function<? super T, Awaitable> adapter, List<T> objects
	) throws AwaitInterruptedException {
		return Awaitable.awaitMultiple(timeout, unit, true, adapter, objects);
	}

	/**
	 * See {@link #awaitMultiple(long, TimeUnit, boolean, Iterator)}.
	 */
	static <T> List<T> awaitMultiple(
		long timeoutMillis, Function<? super T, Awaitable> adapter, List<T> objects
	) throws AwaitInterruptedException {
		return Awaitable.awaitMultiple(
				timeoutMillis, TimeUnit.MILLISECONDS, true, adapter, objects);
	}



	/**
	 * See {@link #awaitMultiple(long, TimeUnit, boolean, Iterator)}.
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
					.map((operation) -> entry(operation, operation))
					.iterator()
			).isEmpty()
		);
	}

	/**
	 * See {@link #awaitMultiple(long, TimeUnit, boolean, Iterator)}.
	 */
	static boolean awaitMultiple(
		long timeoutMillis, boolean continueOnInterrupt, Awaitable... operations
	) throws AwaitInterruptedException {
		return awaitMultiple(timeoutMillis, TimeUnit.MILLISECONDS, continueOnInterrupt, operations);
	}

	/**
	 * See {@link #awaitMultiple(long, TimeUnit, boolean, Iterator)}.
	 */
	static boolean awaitMultiple(
		long timeout, TimeUnit unit, boolean continueOnInterrupt, Awaitable.WithUnit... operations
	) throws AwaitInterruptedException {
		return awaitMultiple(timeout, unit, continueOnInterrupt, (Awaitable[]) operations);
	}

	/**
	 * See {@link #awaitMultiple(long, TimeUnit, boolean, Iterator)}.
	 */
	static boolean awaitMultiple(
		long timeoutMillis, boolean continueOnInterrupt, Awaitable.WithUnit... operations
	) throws AwaitInterruptedException {
		return awaitMultiple(
			timeoutMillis, TimeUnit.MILLISECONDS, continueOnInterrupt, (Awaitable[]) operations
		);
	}

	/**
	 * See {@link #awaitMultiple(long, TimeUnit, boolean, Iterator)}.
	 */
	static boolean awaitMultiple(long timeout, TimeUnit unit, Awaitable... operations)
			throws AwaitInterruptedException {
		return awaitMultiple(timeout, unit, true, operations);
	}

	/**
	 * See {@link #awaitMultiple(long, TimeUnit, boolean, Iterator)}.
	 */
	static boolean awaitMultiple(long timeoutMillis, Awaitable... operations)
			throws AwaitInterruptedException {
		return awaitMultiple(timeoutMillis, TimeUnit.MILLISECONDS, true, operations);
	}

	/**
	 * See {@link #awaitMultiple(long, TimeUnit, boolean, Iterator)}.
	 */
	static boolean awaitMultiple(long timeout, TimeUnit unit, Awaitable.WithUnit... operations)
			throws AwaitInterruptedException {
		return awaitMultiple(timeout, unit, true, (Awaitable[]) operations);
	}

	/**
	 * See {@link #awaitMultiple(long, TimeUnit, boolean, Iterator)}.
	 */
	static boolean awaitMultiple(long timeoutMillis, Awaitable.WithUnit... operations)
			throws AwaitInterruptedException {
		return awaitMultiple(timeoutMillis, TimeUnit.MILLISECONDS, true, (Awaitable[]) operations);
	}
}
