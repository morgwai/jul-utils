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

import pl.morgwai.base.concurrent.Awaitable.CombinedInterruptedException;

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

		final var uncompleted = Awaitable.awaitMultiple(
				100_495l,
				TimeUnit.MICROSECONDS,
				(thread) -> Awaitable.ofJoin(thread),
				Arrays.asList(threads));
		assertTrue("all tasks should be marked as completed", uncompleted.isEmpty());
	}



	@Test
	public void testNotAllTasksCompleted() throws InterruptedException {
		final var NUMBER_OF_TASKS = 20;
		final var taskNumbersToFail = new TreeSet<Integer>();
		taskNumbersToFail.add(7);
		taskNumbersToFail.add(9);
		taskNumbersToFail.add(14);
		assertTrue("test data integrity check", taskNumbersToFail.last() < NUMBER_OF_TASKS);

		final List<Integer> uncompletedTasks = Awaitable.awaitMultiple(
				5l,
				TimeUnit.DAYS,
				(taskNumber) -> (Awaitable.WithUnit) (timeout, unit) -> {
					if (taskNumbersToFail.contains(taskNumber)) return false;
					return true;
				},
				IntStream.range(0, 20).boxed().collect(Collectors.toList()));
		assertEquals("number of uncompleted tasks should match",
				taskNumbersToFail.size(), uncompletedTasks.size());
		for (var task: uncompletedTasks) {
			assertTrue("uncompleted task should be one of those expected to fail ",
					taskNumbersToFail.contains(task));
		}
		assertTrue("uncompleted tasks should be in order", Comparators.isInStrictOrder(
				uncompletedTasks, Integer::compare));
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
		final Awaitable[] tasks = {
			(timeoutMillis) -> {
				taskExecuted[0] = true;
				assertEquals("task-0 should get the full timeout",
						combinedTimeout, timeoutMillis);
				return true;
			},
			(timeoutMillis) -> {
				taskExecuted[1] = true;
				Thread.sleep(100l);
				fail("InterruptedException should be thrown");
				return true;
			},
			(timeoutMillis) -> {
				taskExecuted[2] = true;
				assertEquals("after an interrupt tasks should get 1ms timeout",
						1l, timeoutMillis);
				return true;
			},
			(timeoutMillis) -> {
				taskExecuted[3] = true;
				assertEquals("after an interrupt tasks should get 1ms timeout",
						1l, timeoutMillis);
				return false;
			}
		};

		final var awaitingThread = new Thread(
			() -> {
				try {
					try {
						Awaitable.awaitMultiple(
								combinedTimeout,
								TimeUnit.MILLISECONDS,
								(i) -> tasks[i],
								IntStream.range(0, tasks.length).boxed()
										.collect(Collectors.toList()));
						fail("InterruptedException should be thrown");
					} catch (CombinedInterruptedException e) {
						final var uncompleted = e.getUncompleted();
						assertEquals("2 tasks should not complete", 2, uncompleted.size());
						assertEquals("task-1 should not complete", 1, uncompleted.get(0));
						assertEquals("task-3 should not complete", 3, uncompleted.get(1));
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
								TimeUnit.MILLISECONDS,
								false,
								IntStream.range(0, tasks.length).boxed().map(
										(i) -> Map.entry(i, (Awaitable) tasks[i])).iterator());
						fail("InterruptedException should be thrown");
					} catch (CombinedInterruptedException e) {  // expected
						final var uncompleted = e.getUncompleted();
						assertEquals("2 tasks should not complete", 2, uncompleted.size());
						assertEquals("task-1 should not complete", 1, uncompleted.get(0));
						assertEquals("task-2 should not complete", 2, uncompleted.get(1));
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
