// Copyright (c) Piotr Morgwai Kotarbinski, Licensed under the Apache License, Version 2.0
package pl.morgwai.base.concurrent;

import java.util.concurrent.*;

import org.junit.Test;

import static org.junit.Assert.*;



public class ConcurrentUtilsTest {



	@Test
	public void testCompletableFutureSupplyAsync() throws InterruptedException {
		final var thrown = new Exception();
		final Throwable[] caughtHolder = new Throwable[1];
		final var completionLatch = new CountDownLatch(1);

		ConcurrentUtils.completableFutureSupplyAsync(
			() -> { throw thrown; }
		).handle(
			(result, caught) -> {
				if (result == null) caughtHolder[0] = caught;
				completionLatch.countDown();
				return result;
			}
		);

		assertTrue("task should complete", completionLatch.await(50L, TimeUnit.MILLISECONDS));
		assertTrue(
				"CompletionException should be caught",
				caughtHolder[0] instanceof CompletionException);
		assertSame(
				"cause should be the exception thrown by task",
				thrown,
				caughtHolder[0].getCause());
	}
}
