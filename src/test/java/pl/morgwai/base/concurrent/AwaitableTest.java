// Copyright (c) Piotr Morgwai Kotarbinski, Licensed under the Apache License, Version 2.0
package pl.morgwai.base.concurrent;

import java.util.concurrent.TimeUnit;

import org.junit.Test;

import static org.junit.Assert.*;



public class AwaitableTest {



	@Test
	public void testAwaitableOfJoinThread() throws InterruptedException {
		final var threads = new Thread[3];
		for (int i = 0; i < 3; i++) {
			// it would be cool to create anonymous subclass of Thread that examines params of
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

		final var unompleted = Awaitable.awaitMultiple(
				100_495l,
				TimeUnit.MICROSECONDS,
				true,
				Awaitable.ofJoin(threads[0]),
				Awaitable.ofJoin(threads[1]),
				Awaitable.ofJoin(threads[2]));
		assertTrue("all tasks should be marked as completed", unompleted.isEmpty());
	}



	@Test
	public void testNotAllTasksCompleted() throws InterruptedException {
		final var unompleted = Awaitable.awaitMultiple(
			20l,
			(timeout) -> true,
			(timeout) -> true,
			(timeout) -> false,
			(timeout) -> true
		);
		assertFalse("result should indicate not all tasks were completedd", unompleted.isEmpty());
	}



	@Test
	public void testRemainingTimeoutAdjusting() throws InterruptedException {
		final long FIRST_DUARATION = 10l;
		final long COMBINED_TIMEOUT = FIRST_DUARATION + 30;
		final long MAX_INACCURACY = 5l;  // 1ms is enough in 99.9% cases. See message below.

		final var unompleted = Awaitable.awaitMultiple(
			COMBINED_TIMEOUT,
			TimeUnit.MILLISECONDS,
			true,
			(timeout, unit) -> {
				assertEquals("1st task should get the full timeout",
						COMBINED_TIMEOUT, TimeUnit.MILLISECONDS.convert(timeout, unit));
				Thread.sleep(FIRST_DUARATION);
				return true;
			},
			(timeout, unit) -> {
				final var timeoutMillis = TimeUnit.MILLISECONDS.convert(timeout, unit);
				assertTrue("timeouts of subsequent tasks should be correctly adjusted",
						COMBINED_TIMEOUT - FIRST_DUARATION >= timeoutMillis);
				assertTrue("timeout adjustment accuracy should be within range (this may sometimes"
						+ " fail if another process was using much CPU, so just try again)",
						COMBINED_TIMEOUT - FIRST_DUARATION - timeoutMillis <= MAX_INACCURACY);
				Thread.sleep(TimeUnit.MILLISECONDS.convert(timeout, unit) + MAX_INACCURACY);
				return true;
			},
			(timeout, unit) -> {
				assertEquals("after timeout has been exceeded subsequent task should get 1ns",
						1l, TimeUnit.NANOSECONDS.convert(timeout, unit));
				return true;
			}
		);
		assertTrue("all tasks should be marked as completed", unompleted.isEmpty());
	}



	@Test
	public void testNoTimeout() throws InterruptedException {
		final var unompleted = Awaitable.awaitMultiple(
			0l,
			TimeUnit.MILLISECONDS,
			true,
			(timeout, unit) -> {
				assertEquals("there should be no timeout", 0l, timeout);
				return true;
			},
			(timeout, unit) -> {
				assertEquals("there should be no timeout", 0l, timeout);
				return true;
			},
			(timeout, unit) -> {
				assertEquals("there should be no timeout", 0l, timeout);
				return true;
			}
		);
		assertTrue("all tasks should be marked as completed", unompleted.isEmpty());
	}



	public void testInterruptAndContinue(boolean noTimeout) throws InterruptedException {
		final long combinedTimeout = noTimeout ? 0l : 100l;
		final AssertionError[] errorHolder = {null};
		final boolean[] secondTaskCompletedHolder = {false, false};

		final var awaitingThread = new Thread(
			() -> {
				try {
					try {
						Awaitable.awaitMultiple(
							combinedTimeout,
							(timeoutMillis) -> {
								assertEquals("1st task should get the full timeout",
										combinedTimeout, timeoutMillis);
								Thread.sleep(100l);
								fail("InterruptedException should be thrown");
								return true;
							},
							(timeoutMillis) -> {
								assertEquals("after an interrupt tasks should get 1ms timeout",
										1l, timeoutMillis);
								secondTaskCompletedHolder[0] = true;
								return true;
							},
							(timeoutMillis) -> {
								assertEquals("after an interrupt tasks should get 1ms timeout",
										1l, timeoutMillis);
								secondTaskCompletedHolder[1] = true;
								return true;
							}
						);
						fail("InterruptedException should be thrown");
					} catch (InterruptedException e) {  // expected
					}
					assertTrue("2nd task should complete", secondTaskCompletedHolder[0]);
					assertTrue("3rd task should complete", secondTaskCompletedHolder[1]);
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
		final AssertionError[] errorHolder = {null};

		final var awaitingThread = new Thread(
			() -> {
				try {
					try {
						Awaitable.awaitMultiple(
							100l,
							TimeUnit.MILLISECONDS,
							false,
							(timeout, unit) -> {
								Thread.sleep(TimeUnit.MILLISECONDS.convert(timeout, unit));
								fail("InterruptedException should be thrown");
								return true;
							},
							(timeout, unit) -> {
								fail("2nd task should not be awaited for");
								return true;
							}
						);
						fail("InterruptedException should be thrown");
					} catch (InterruptedException e) {  // expected
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
