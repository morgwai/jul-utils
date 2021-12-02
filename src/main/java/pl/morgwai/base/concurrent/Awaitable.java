// Copyright (c) Piotr Morgwai Kotarbinski, Licensed under the Apache License, Version 2.0
package pl.morgwai.base.concurrent;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;



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



	class CombinedInterruptedException extends InterruptedException {

		final List<? extends Awaitable> uncompleted;
		public List<? extends Awaitable> getUncompleted() { return uncompleted; }

		public CombinedInterruptedException(List<? extends Awaitable> uncompleted) {
			this.uncompleted = uncompleted;
		}

		private static final long serialVersionUID = 1745601970917052988L;
	}



	/**
	 * Awaits for multiple timed blocking operations, such as {@link Thread#join(long)},
	 * {@link Object#wait(long)}, {@link ExecutorService#awaitTermination(long, TimeUnit)} etc.
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
	static <TaskT extends Awaitable> List<TaskT> awaitMultiple(
			long timeout, TimeUnit unit, boolean continueOnInterrupt, Stream<TaskT> tasks)
			throws CombinedInterruptedException {
		final var startTimestamp = System.nanoTime();
		var remainingTime =  unit.toNanos(timeout);
		final var uncompleted = new LinkedList<TaskT>();
		final var taskIterator = tasks.iterator();
		boolean interrupted = false;
		while (taskIterator.hasNext()) {
			final var task = taskIterator.next();
			try {
				if ( ! task.await(remainingTime, TimeUnit.NANOSECONDS)) uncompleted.add(task);
				if (timeout != 0l && ! interrupted) {
					remainingTime -= System.nanoTime() - startTimestamp;
					if (remainingTime < 1l) remainingTime = 1l;
				}
			} catch (InterruptedException e) {
				uncompleted.add(task);
				if ( ! continueOnInterrupt) {
					while (taskIterator.hasNext()) uncompleted.add(taskIterator.next());
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
	 * Calls {@link #awaitMultiple(long, TimeUnit, boolean, Stream)
	 * awaitMultiple(timeout, unit, true, tasks)}.
	 */
	static <TaskT extends Awaitable> List<TaskT> awaitMultiple(
			long timeout, TimeUnit unit, Stream<TaskT> tasks)
			throws CombinedInterruptedException {
		return awaitMultiple(timeout, unit, true, tasks);
	}

	/**
	 * Calls {@link #awaitMultiple(long, TimeUnit, boolean, Stream)
	 * awaitMultiple(timeout, unit, true, tasks.stream())}.
	 */
	static <TaskT extends Awaitable> List<TaskT> awaitMultiple(
			long timeout, TimeUnit unit, List<TaskT> tasks)
			throws CombinedInterruptedException {
		return awaitMultiple(timeout, unit, true, tasks.stream());
	}

	/**
	 * Calls {@link #awaitMultiple(long, TimeUnit, boolean, Stream)
	 * awaitMultiple(timeout, unit, continueOnInterrupt, Arrays.stream(tasks))}.
	 */
	@SafeVarargs
	static <TaskT extends Awaitable> List<TaskT> awaitMultiple(
			long timeout, TimeUnit unit, boolean continueOnInterrupt, TaskT... tasks)
			throws CombinedInterruptedException {
		return awaitMultiple(timeout, unit, continueOnInterrupt, Arrays.stream(tasks));
	}

	/**
	 * Calls {@link #awaitMultiple(long, TimeUnit, boolean, Stream)
	 * awaitMultiple(timeout, unit, true, Arrays.stream(tasks))}.
	 */
	@SafeVarargs
	static <TaskT extends Awaitable> List<TaskT> awaitMultiple(
			long timeout, TimeUnit unit, TaskT... tasks)
			throws CombinedInterruptedException {
		return awaitMultiple(timeout, unit, true, Arrays.stream(tasks));
	}



	/**
	 * A simplified {@link Awaitable} that always takes timeout in millis.
	 */
	@FunctionalInterface
	interface InMillis {
		boolean await(long timeoutMillis) throws InterruptedException;
	}



	/**
	 * Calls {@link #awaitMultiple(long, TimeUnit, boolean, List)
	 * awaitMultiple(timeoutMillis, TimeUnit.MILLISECONDS, true, tasks)} wrapping {@code tasks}
	 * with {@link InMillisAdapter}.
	 */
	static <TaskT extends Awaitable.InMillis> List<TaskT> awaitMultiple(
			long timeoutMillis, List<TaskT> tasks)
			throws CombinedInterruptedException {
		return (
			Awaitable.awaitMultiple(
				timeoutMillis,
				TimeUnit.MILLISECONDS,
				true,
				tasks.stream().map((task) -> new InMillisAdapter<>(task))
			).stream()
				.map((adapter) -> adapter.getWrapped())
				.collect(Collectors.toList())
		);
	}

	/**
	 * Calls {@link #awaitMultiple(long, List) awaitMultiple(timeout Arrays.asList(tasks))}.
	 */
	@SafeVarargs
	static <TaskT extends Awaitable.InMillis> List<TaskT> awaitMultiple(
			long timeoutMillis, TaskT... tasks)
			throws CombinedInterruptedException {
		return awaitMultiple(timeoutMillis, Arrays.asList(tasks));
	}



	/**
	 * Adapts {@link Awaitable.InMillis} to {@link Awaitable} API.
	 * <p>
	 * Timeout supplied to {@link Awaitable#await(long, TimeUnit)} is converted to millis using
	 * {@link TimeUnit#convert(long, TimeUnit)}, except when it is smaller than 1ms yet non-zero,
	 * in which case it will be rounded up to 1ms.</p>
	 */
	class InMillisAdapter<TaskT extends Awaitable.InMillis> implements Awaitable {

		TaskT wrapped;
		public TaskT getWrapped() { return wrapped; }

		public InMillisAdapter(TaskT toAdapt) { this.wrapped = toAdapt; }

		@Override
		public boolean await(long timeout, TimeUnit unit) throws InterruptedException {
			if (wrapped instanceof Awaitable) return ((Awaitable) wrapped).await(timeout, unit);
			return wrapped.await(timeout == 0l ? 0l : Math.max(1l, unit.toMillis(timeout)));
		}

		@Override
		public String toString() {
			return "InMillisAdapter { wrapped = " + wrapped + '}';
		}
	}



	/**
	 * A helper class that implements both {@link Awaitable} and {@link Awaitable.InMillis}.
	 * @see Awaitable#ofJoin(Thread)
	 * @see Awaitable#ofTermination(ExecutorService)
	 */
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



	/**
	 * Creates {@link Awaitable} of {@link Thread#join(long) joining a thread}.
	 */
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



	/**
	 * Creates {@link Awaitable} of
	 * {@link ExecutorService#awaitTermination(long, TimeUnit) termination of an executor}.
	 */
	static GenericAwaitable<ExecutorService> ofTermination(ExecutorService executor) {
		return new GenericAwaitable<>(
				executor,
				(timeout, unit) -> executor.awaitTermination(timeout, unit));
	}
}
