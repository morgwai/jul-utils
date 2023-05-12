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

		final var completableFuture = ConcurrentUtils.completableFutureSupplyAsync(
			() -> { throw thrown; },
			Executors.newSingleThreadExecutor()
		);
		completableFuture.whenComplete(
			(result, caught) -> {
				if (result == null) caughtHolder[0] = caught;
				completionLatch.countDown();
			}
		);

		assertTrue("the Callable task should complete",
				completionLatch.await(50L, TimeUnit.MILLISECONDS));
		assertTrue("completableFuture should be marked as done", completableFuture.isDone());
		assertSame("caught exception should be the same as thrown by the Callable task",
				thrown, caughtHolder[0]);
	}
}
