// Copyright (c) Piotr Morgwai Kotarbinski, Licensed under the Apache License, Version 2.0
package pl.morgwai.base.concurrent;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.google.common.collect.Comparators;
import org.junit.Test;

import pl.morgwai.base.concurrent.Awaitable.AwaitInterruptedException;

import static org.junit.Assert.*;



public class AwaitableTest {



	@Test
	public void testAwaitableOfJoinThread() throws InterruptedException {
		final var threads = new Thread[3];
		for (int i = 0; i < 3; i++) {
			// it would be cool to create anonymous subclass of Thread that verifies params of
			// join(...), unfortunately join(...) is final...
			threads[i] = new Thread(
				() -> {
					try {
						Thread.sleep(10l);
					} catch (InterruptedException ignored) {}
				}
			);
			threads[i].start();
		}

		final var failed = Awaitable.awaitMultiple(
				100_495l,
				TimeUnit.MICROSECONDS,
				(thread) -> Awaitable.ofJoin(thread),
				Arrays.asList(threads));
		assertTrue("all tasks should be marked as completed", failed.isEmpty());
	}



	@Test
	public void testNotAllTasksCompleted() throws InterruptedException {
		final var NUMBER_OF_TASKS = 20;
		final var tasksToFail = new TreeSet<Integer>();
		tasksToFail.add(7);
		tasksToFail.add(9);
		tasksToFail.add(14);
		assertTrue("test data integrity check", tasksToFail.last() < NUMBER_OF_TASKS);

		final List<Integer> failed = Awaitable.awaitMultiple(
				5l,
				TimeUnit.DAYS,
				(taskNumber) -> (Awaitable.WithUnit) (timeout, unit) -> {
					if (tasksToFail.contains(taskNumber)) return false;
					return true;
				},
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
		final long FIRST_DURATION = 10l;
		final long COMBINED_TIMEOUT = FIRST_DURATION + 30l;
		final long MAX_INACCURACY = 5l;  // 1ms is enough in 99.9% cases. See message below.

		final var allCompleted = Awaitable.awaitMultiple(
			COMBINED_TIMEOUT,
			TimeUnit.MILLISECONDS,
			(timeout, unit) -> {
				assertEquals("1st task should get the full timeout",
						COMBINED_TIMEOUT, TimeUnit.MILLISECONDS.convert(timeout, unit));
				Thread.sleep(FIRST_DURATION);
				return true;
			},
			(timeout, unit) -> {
				final var timeoutMillis = TimeUnit.MILLISECONDS.convert(timeout, unit);
				assertTrue("timeouts of subsequent tasks should be correctly adjusted",
						COMBINED_TIMEOUT - FIRST_DURATION >= timeoutMillis);
				assertTrue("timeout adjustment accuracy should be within range (this may fail if "
						+ "another process was using much CPU or VM was warming up, so try again)",
						COMBINED_TIMEOUT - FIRST_DURATION - timeoutMillis <= MAX_INACCURACY);
				Thread.sleep(TimeUnit.MILLISECONDS.convert(timeout, unit) + MAX_INACCURACY);
				return true;
			},
			(timeout, unit) -> {
				assertEquals("after timeout has been exceeded subsequent task should get 1ns",
						1l, TimeUnit.NANOSECONDS.convert(timeout, unit));
				return true;
			}
		);
		assertTrue("all tasks should be marked as completed", allCompleted);
	}



	@Test
	public void testNoTimeout() throws InterruptedException {
		final var allCompleted = Awaitable.awaitMultiple(
			0l,
			(timeout) -> {
				assertEquals("there should be no timeout", 0l, timeout);
				return true;
			},
			(timeout) -> {
				assertEquals("there should be no timeout", 0l, timeout);
				return true;
			},
			(timeout) -> {
				assertEquals("there should be no timeout", 0l, timeout);
				return true;
			}
		);
		assertTrue("all tasks should be marked as completed", allCompleted);
	}



	public void testInterruptAndContinue(boolean noTimeout) throws InterruptedException {
		final long combinedTimeout = noTimeout ? 0l : 100l;
		final AssertionError[] errorHolder = {null};
		final boolean[] taskExecuted = {false, false, false, false};
		final var awaitingThread = new Thread(
			() -> {
				try {
					try {
						Awaitable.awaitMultiple(
							combinedTimeout,
							TimeUnit.MILLISECONDS,
							Map.entry(0, (timeoutMillis) -> {
								taskExecuted[0] = true;
								assertEquals("task-0 should get the full timeout",
										combinedTimeout, timeoutMillis);
								return true;
							}),
							Map.entry(1, (timeoutMillis) -> {
								taskExecuted[1] = true;
								Thread.sleep(100l);
								fail("InterruptedException should be thrown");
								return true;
							}),
							Map.entry(2, (timeoutMillis) -> {
								taskExecuted[2] = true;
								assertEquals("after an interrupt tasks should get 1ms timeout",
										1l, timeoutMillis);
								return true;
							}),
							Map.entry(3, (timeoutMillis) -> {
								taskExecuted[3] = true;
								assertEquals("after an interrupt tasks should get 1ms timeout",
										1l, timeoutMillis);
								return false;
							})
						);
						fail("InterruptedException should be thrown");
					} catch (AwaitInterruptedException e) {
						final var failed = e.getFailed();
						final var interrupted = e.getInterrupted();
						assertEquals("1 task should fail", 1, failed.size());
						assertEquals("1 task should be interrupted", 1, interrupted.size());
						assertFalse("all tasks should be exexcuted", e.getUnexecuted().hasNext());
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
		Thread.sleep(5l);
		awaitingThread.interrupt();
		awaitingThread.join(100l);
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
		final long TIMEOUT = 100l;
		final AssertionError[] errorHolder = {null};
		final boolean[] taskExecuted = {false, false, false};
		final Awaitable.WithUnit[] tasks = {
			(timeout, unit) -> {
				taskExecuted[0] = true;
				assertEquals("task-0 should get the full timeout",
						TIMEOUT, unit.toMillis(timeout));
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
								TIMEOUT,
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
						assertTrue("not all tasks should be exexcuted",
								e.getUnexecuted().hasNext());
						assertEquals("task-2 should not be exeucted", 2,
								unexecuted.next().getKey());
						assertFalse("only 1 task should not be exexcuted",
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
		Thread.sleep(5l);
		awaitingThread.interrupt();
		awaitingThread.join(100l);
		if (awaitingThread.isAlive()) fail("awaitingThread should terminate");
		if (errorHolder[0] != null)  throw errorHolder[0];
	}
}
