// Copyright (c) Piotr Morgwai Kotarbinski, Licensed under the Apache License, Version 2.0
package pl.morgwai.base.concurrent;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.google.common.collect.Comparators;
import org.junit.*;
import pl.morgwai.base.concurrent.Awaitable.AwaitInterruptedException;

import static org.junit.Assert.*;
import static pl.morgwai.base.concurrent.Awaitable.entry;



public class AwaitableTest {



	/**
	 * Maximum tolerable inaccuracy when verifying time adjustments. Inaccuracy is a result of
	 * processing delay and is below 1ms in 99% of cases, however if another process was using
	 * much CPU or the VM was warming up, then on rare occasions it may get higher. If tests fail
	 * because of this, rerunning right away is usually sufficient. If not, then this probably
	 * indicates some bug.
	 */
	final long MAX_INACCURACY_MILLIS = 2L;



	@Test
	public void testNotAllTasksCompleted() throws InterruptedException {
		final var NUMBER_OF_TASKS = 20;
		final var tasksToFail = new TreeSet<Integer>();
		tasksToFail.add(7);
		tasksToFail.add(9);
		tasksToFail.add(14);
		assertTrue("test data integrity check", tasksToFail.last() < NUMBER_OF_TASKS);

		final List<Integer> failed = Awaitable.awaitMultiple(
				5L,
				TimeUnit.DAYS,
				(taskNumber) -> (Awaitable.WithUnit)
						(timeout, unit) -> !tasksToFail.contains(taskNumber),
				IntStream.range(0, 20).boxed().collect(Collectors.toList()));
		assertEquals("number of failed tasks should match",
				tasksToFail.size(), failed.size());
		for (var task: failed) {
			assertTrue("failed task should be one of those expected to fail ",
					tasksToFail.contains(task));
		}
		assertTrue("uncompleted tasks should be in order", Comparators.isInStrictOrder(
				failed, Integer::compare));
	}



	@Test
	public void testRemainingTimeoutAdjusting() throws InterruptedException {
		final long FIRST_TASK_DURATION_MILLIS = 10L;
		final long TOTAL_TIMEOUT_MILLIS = FIRST_TASK_DURATION_MILLIS + 30L;

		assertTrue("all tasks should be marked as completed", Awaitable.awaitMultiple(
			TOTAL_TIMEOUT_MILLIS,
			TimeUnit.MILLISECONDS,
			(timeout, unit) -> {
				assertEquals("1st task should get the full timeout",
						TOTAL_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS.convert(timeout, unit));
				Thread.sleep(FIRST_TASK_DURATION_MILLIS);
				return true;
			},
			(timeout, unit) -> {
				final var adjustedTimeoutMillis = TimeUnit.MILLISECONDS.convert(timeout, unit);
				assertTrue("timeouts of subsequent tasks should be correctly adjusted",
						TOTAL_TIMEOUT_MILLIS - FIRST_TASK_DURATION_MILLIS >= adjustedTimeoutMillis);
				assertTrue("timeout adjustment accuracy should be below " + MAX_INACCURACY_MILLIS
						+ "ms (this may fail if another process was using much CPU or if the VM was"
						+ " warming up, so try again)",
						TOTAL_TIMEOUT_MILLIS - FIRST_TASK_DURATION_MILLIS - adjustedTimeoutMillis
						<= MAX_INACCURACY_MILLIS);
				Thread.sleep(TimeUnit.MILLISECONDS.convert(timeout, unit) + MAX_INACCURACY_MILLIS);
				return true;
			},
			(timeout, unit) -> {
				assertEquals("after timeout has been exceeded subsequent task should get 1ns",
						1L, TimeUnit.NANOSECONDS.convert(timeout, unit));
				return true;
			}
		));
	}



	@Test
	public void testZeroTimeout() throws InterruptedException {
		final var allCompleted = Awaitable.awaitMultiple(
			0L,
			(timeout) -> {
				assertEquals("there should be no timeout", 0L, timeout);
				return true;
			},
			(timeout) -> {
				assertEquals("there should be no timeout", 0L, timeout);
				return true;
			},
			(timeout) -> {
				assertEquals("there should be no timeout", 0L, timeout);
				return true;
			}
		);
		assertTrue("all tasks should be marked as completed", allCompleted);
	}



	public void testInterruptAndContinue(boolean zeroTimeout) throws InterruptedException {
		final long combinedTimeout = zeroTimeout ? 0L : 100L;
		final AssertionError[] errorHolder = {null};
		final boolean[] taskExecuted = {false, false, false, false};
		final var awaitingThread = new Thread(
			() -> {
				try {
					try {
						Awaitable.awaitMultiple(
							combinedTimeout,
							TimeUnit.MILLISECONDS,
							entry(0, (timeoutMillis) -> {
								taskExecuted[0] = true;
								assertEquals("task-0 should get the full timeout",
										combinedTimeout, timeoutMillis);
								return true;
							}),
							entry(1, (timeoutMillis) -> {
								taskExecuted[1] = true;
								Thread.sleep(100L);
								fail("InterruptedException should be thrown");
								return true;
							}),
							entry(2, (timeoutMillis) -> {
								taskExecuted[2] = true;
								assertEquals("after an interrupt tasks should get 1ms timeout",
										1L, timeoutMillis);
								return true;
							}),
							entry(3, (timeoutMillis) -> {
								taskExecuted[3] = true;
								assertEquals("after an interrupt tasks should get 1ms timeout",
										1L, timeoutMillis);
								return false;
							})
						);
						fail("InterruptedException should be thrown");
					} catch (AwaitInterruptedException e) {
						final var failed = e.getFailed();
						final var interrupted = e.getInterrupted();
						assertEquals("1 task should fail", 1, failed.size());
						assertEquals("1 task should be interrupted", 1, interrupted.size());
						assertFalse("all tasks should be executed", e.getUnexecuted().hasNext());
						assertEquals("task-1 should be interrupted", 1, interrupted.get(0));
						assertEquals("task-3 should fail", 3, failed.get(0));
					}
					for (int i = 0; i < taskExecuted.length; i++) {
						assertTrue("task-" + i + " should be executed", taskExecuted[i]);
					}
				} catch (AssertionError e) {
					errorHolder[0] = e;
				}
			}
		);

		awaitingThread.start();
		Thread.sleep(5L);
		awaitingThread.interrupt();
		awaitingThread.join(100L);
		if (awaitingThread.isAlive()) fail("awaitingThread should terminate");
		if (errorHolder[0] != null)  throw errorHolder[0];
	}

	@Test
	public void testInterruptAndContinueNoTimeout() throws InterruptedException {
		testInterruptAndContinue(true);
	}

	@Test
	public void testInterruptAndContinueWithTimeout() throws InterruptedException {
		testInterruptAndContinue(false);
	}



	@Test
	public void testInterruptAndAbort() throws InterruptedException {
		final long TOTAL_TIMEOUT_MILLIS = 100L;
		final AssertionError[] errorHolder = {null};
		final boolean[] taskExecuted = {false, false, false};
		final Awaitable.WithUnit[] tasks = {
			(timeout, unit) -> {
				taskExecuted[0] = true;
				assertEquals("task-0 should get the full timeout",
						TOTAL_TIMEOUT_MILLIS, unit.toMillis(timeout));
				return true;
			},
			(timeout, unit) -> {
				taskExecuted[1] = true;
				Thread.sleep(unit.toMillis(timeout));
				fail("InterruptedException should be thrown");
				return true;
			},
			(timeout, unit) -> {
				taskExecuted[2] = true;
				fail("task-2 should not be executed");
				return true;
			}
		};
		final var awaitingThread = new Thread(
			() -> {
				try {
					try {
						Awaitable.awaitMultiple(
								TOTAL_TIMEOUT_MILLIS,
								false,
								(i) -> tasks[i],
								IntStream.range(0, tasks.length).boxed()
										.collect(Collectors.toList()));
						fail("InterruptedException should be thrown");
					} catch (AwaitInterruptedException e) {
						final var interrupted = e.getInterrupted();
						final var unexecuted = e.getUnexecuted();
						assertTrue("no task should fail", e.getFailed().isEmpty());
						assertEquals("1 task should be interrupted", 1, interrupted.size());
						assertEquals("task-1 should be interrupted", 1, interrupted.get(0));
						assertTrue("not all tasks should be executed",
								e.getUnexecuted().hasNext());
						assertEquals("task-2 should not be executed", 2,
								unexecuted.next().getObject());
						assertFalse("only 1 task should not be executed",
								e.getUnexecuted().hasNext());

						for (int i = 0; i < taskExecuted.length - 1; i++) {
							assertTrue("task-" + i + " should be executed", taskExecuted[i]);
						}
						assertFalse("the last task should NOT be executed",
								taskExecuted[taskExecuted.length - 1]);
					}
				} catch (AssertionError e) {
					errorHolder[0] = e;
				}
			}
		);

		awaitingThread.start();
		Thread.sleep(5L);
		awaitingThread.interrupt();
		awaitingThread.join(100L);
		if (awaitingThread.isAlive()) fail("awaitingThread should terminate");
		if (errorHolder[0] != null)  throw errorHolder[0];
	}



	@Test
	public void testAwaitableOfExecutorTermination() throws InterruptedException {
		final var executor = new ThreadPoolExecutor(
				2, 2, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingDeque<>());
		final var latch = new CountDownLatch(1);
		executor.execute(
			() -> {
				try {
					latch.await();
				} catch (InterruptedException ignored) {}
			}
		);
		final var termination = Awaitable.ofTermination(executor);
		assertFalse("executor should not be shutdown until termination is being awaited",
				executor.isShutdown());
		assertFalse("termination should fail before latch is lowered", termination.await(20L));
		assertTrue("executor should be shutdown", executor.isShutdown());
		assertFalse("termination should fail before latch is lowered", executor.isTerminated());
		latch.countDown();
		assertTrue("termination should succeed after latch is lowered", termination.await(20L));
		assertTrue("termination should succeed after latch is lowered", executor.isTerminated());
	}



	@Test
	public void testAwaitableOfExecutorEnforcedTermination() throws InterruptedException {
		final var executor = new ThreadPoolExecutor(
				2, 2, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingDeque<>());
		final var latch = new CountDownLatch(1);
		executor.execute(
			() -> {
				try {
					Thread.sleep(100_000L);
				} catch (InterruptedException e) {
					try {
						latch.await();
					} catch (InterruptedException ignored) {}
				}
			}
		);
		final var enforcedTermination = Awaitable.ofEnforcedTermination(executor);
		assertFalse("executor should not be shutdown until termination is being awaited",
				executor.isShutdown());
		assertFalse("termination should fail", enforcedTermination.await(20L));
		assertFalse("termination should fail", executor.isTerminated());
		assertTrue("executor should be shutdown", executor.isShutdown());
		latch.countDown();
		assertTrue("finally executor should terminate successfully",
				executor.awaitTermination(50L, TimeUnit.MILLISECONDS));
	}



	@Test
	public void testAwaitableOfThreadJoin() throws InterruptedException {
		final var latch = new CountDownLatch(1);
		final var thread = new Thread(
			() -> {
				try {
					latch.await();
				} catch (InterruptedException ignore) {}
			}
		);
		thread.start();
		final var joining = Awaitable.ofJoin(thread);
		assertTrue("thread should be alive before latch is lowered", thread.isAlive());
		assertFalse("thread should not be interrupted", thread.isInterrupted());
		final long timeoutMicros = 20_999_999L;
		final long startMillis = System.currentTimeMillis();
		assertFalse("joining should fail before latch is lowered",
				joining.await(timeoutMicros, TimeUnit.NANOSECONDS));
		assertTrue("timeout should be correctly converted",
				System.currentTimeMillis() - startMillis > timeoutMicros / 1_000_000L);
		assertTrue("attempt to join should not interrupt thread", thread.isAlive());
		assertFalse("attempt to join should not interrupt thread", thread.isInterrupted());
		latch.countDown();
		assertTrue("joining should succeed after lowering latch",
				joining.await(990L, TimeUnit.NANOSECONDS));
		assertFalse("thread should terminate after lowering latch", thread.isAlive());
	}



	@Test
	public void testAwaitMultipleThreadJoin() throws InterruptedException {
		final int NUMBER_OF_THREADS = 5;
		final var threads = new Thread[NUMBER_OF_THREADS];
		for (int i = 0; i < NUMBER_OF_THREADS; i++) {
			// it would be cool to create anonymous subclass of Thread that verifies params of
			// join(...), unfortunately join(...) is final...
			threads[i] = new Thread(
				() -> {
					try {
						Thread.sleep(10L);
					} catch (InterruptedException ignored) {}
				}
			);
			threads[i].start();
		}

		final var failed = Awaitable.awaitMultiple(
			100,  // on rare occasions threads take long to start
			Awaitable::ofJoin,
			Arrays.asList(threads));
		assertTrue("all tasks should be marked as completed", failed.isEmpty());
	}
}
