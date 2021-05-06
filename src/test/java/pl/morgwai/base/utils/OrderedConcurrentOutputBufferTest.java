/*
 * Copyright (c) Piotr Morgwai Kotarbinski
 */
package pl.morgwai.base.utils;

import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import com.google.common.collect.Comparators;

import pl.morgwai.base.utils.OrderedConcurrentOutputBuffer.OutputStream;

import static org.junit.Assert.*;



public class OrderedConcurrentOutputBufferTest {



	OrderedConcurrentOutputBuffer<Message> buffer;

	OutputStream<Message> outputStream;
	List<Message> outputData;  // outputStream.write(message) will add message to this list
	volatile int closeCount;  // outputStream.close() will increase this counter



	int[] bucketMessageNumbers;

	Message nextMessage(int bucketNumber) {
		return new Message(bucketNumber, ++bucketMessageNumbers[bucketNumber - 1]);
	}

	int sumUpMessageCount() {
		int messageCount = 0;
		for (int i = 0; i < bucketMessageNumbers.length; i++) {
			messageCount += bucketMessageNumbers[i];
		}
		return messageCount;
	}



	@Test
	public void testSingleThread() {
		bucketMessageNumbers = new int[5];
		OutputStream<Message> bucket1 = buffer.addBucket();
		OutputStream<Message> bucket2 = buffer.addBucket();
		bucket2.write(nextMessage(2));
		bucket2.write(nextMessage(2));
		bucket1.write(nextMessage(1));
		bucket2.write(nextMessage(2));
		bucket1.write(nextMessage(1));
		bucket2.write(nextMessage(2));
		OutputStream<Message> bucket3 = buffer.addBucket();
		bucket2.write(nextMessage(2));
		bucket3.write(nextMessage(3));
		bucket1.write(nextMessage(1));
		bucket3.write(nextMessage(3));
		bucket2.write(nextMessage(2));
		bucket3.write(nextMessage(3));
		bucket2.close();
		bucket1.write(nextMessage(1));
		bucket1.write(nextMessage(1));
		bucket3.write(nextMessage(3));
		bucket1.write(nextMessage(1));
		OutputStream<Message> bucket4 = buffer.addBucket();
		bucket1.close();
		bucket3.close();
		OutputStream<Message> bucket5 = buffer.addBucket();
		bucket5.write(nextMessage(5));
		bucket5.close();
		buffer.signalNoMoreBuckets();
		bucket4.close();

		assertEquals("all messages should be written", sumUpMessageCount(), outputData.size());
		assertTrue("messages should be written in order",
				Comparators.isInStrictOrder(outputData, messageComparator));
		assertEquals("stream should be closed 1 time", 1, closeCount);
	}



	@Test
	public void test1000Threds1000MessagesPerThread() throws InterruptedException {
		testSeveralThreads(1000, 1000, 1000, 1000, 0, 0, 1000, 1000, 10);
	}

	@Test
	public void test1000Threds1MessagePerThread() throws InterruptedException {
		testSeveralThreads(1000, 1, 1, 1, 1, 0);
	}

	/**
	 * Creates <code>numberOfBucketThreads</code> threads, each of which adds 1 bucket and writes
	 * <code>messagesPerThread[bucketNumber % length]</code> to it.
	 */
	void testSeveralThreads(int numberOfBucketThreads, int... messagesPerThread)
			throws InterruptedException {
		bucketCount = 0;
		int expectedMessageCount = 0;
		bucketMessageNumbers = new int[numberOfBucketThreads];
		Thread[] bucketThreads = new Thread[numberOfBucketThreads];
		for (int i = 0; i < bucketThreads.length; i++) {
			int numberOfMessages = messagesPerThread[i % messagesPerThread.length];
			bucketThreads[i] = newBucketThread(numberOfMessages);
			expectedMessageCount += numberOfMessages;
		}
		for (int i = 0; i < bucketThreads.length; i++) bucketThreads[i].start();
		for (int i = 0; i < bucketThreads.length; i++) bucketThreads[i].join();
		buffer.signalNoMoreBuckets();

		assertEquals("all messages should be written", expectedMessageCount, outputData.size());
		assertTrue("messages should be written in order",
				Comparators.isInStrictOrder(outputData, messageComparator));
		assertEquals("stream should be closed 1 time", 1, closeCount);
	}

	int bucketCount;

	private Thread newBucketThread(int numberOfMessages) {
		return new Thread(() -> {
			int bucketNumber;
			OutputStream<Message> bucket;
			synchronized (OrderedConcurrentOutputBufferTest.this) {
				bucketNumber = ++bucketCount;
				if (log.isLoggable(Level.FINER)) log.finer("adding bucket " + bucketNumber);
				bucket = buffer.addBucket();
			}
			if (bucketNumber % 17 == 0) {
				// make some threads a bit slower to start
				try {
					Thread.sleep(100);
				} catch (InterruptedException e) {}
			}
			for (int i = 0; i < numberOfMessages; i++) {
				bucket.write(nextMessage(bucketNumber));
			}
			if (log.isLoggable(Level.FINER)) log.finer("closing bucket " + bucketNumber);
			bucket.close();
		});
	}



	@Test
	public void testSignalConcurrentlyWithFlushingLastBucket() throws InterruptedException {
		// tries to trigger a race condition that was causing output to be closed 2 times
		for (int i = 0; i < 5000; i++) {
			setup();
			var bucket = buffer.addBucket();
			var t1 = new Thread(() -> bucket.close());
			var t2 = new Thread(() -> buffer.signalNoMoreBuckets());
			t1.start();
			t2.start();
			t1.join();
			t2.join();

			assertEquals("stream should be closed 1 time", 1, closeCount);
		}
	}



	@Test
	public void testConcurrentCloseOfSubequentBucketsFollowedByClosedBuckets()
			throws InterruptedException {
		// tries to trigger a race condition that was suppressing flushing sequence
		for (int i = 0; i < 5000; i++) {
			setup();
			var bucket1 = buffer.addBucket();
			var bucket2 = buffer.addBucket();
			var bucket3 = buffer.addBucket();
			bucket3.write(new Message(3, 1));
			bucket3.close();
			var bucket4 = buffer.addBucket();
			bucket4.write(new Message(4, 1));
			bucket4.close();
			buffer.signalNoMoreBuckets();
			var t1 = new Thread(() -> bucket1.close());
			var t2 = new Thread(() -> bucket2.close());
			t1.start();
			t2.start();
			t1.join();
			t2.join();

			assertEquals("all messages should be written", 2, outputData.size());
			assertTrue("messages should be written in order",
					Comparators.isInStrictOrder(outputData, messageComparator));
			assertEquals("stream should be closed 1 time", 1, closeCount);
		}
	}



	@Test
	public void testAddBucketAndSignalWhileClosingTail()
			throws InterruptedException {
		// tries to trigger a race condition that was causing output to be closed too early
		for (int i = 0; i < 100; i++) {
			setup();
			var bucket1 = buffer.addBucket();
			bucket1.write(new Message(1, 1));
			Exception[] t2exceptionHolder = {null};
			var t1 = new Thread(() -> bucket1.close());
			var t2 = new Thread(() -> {
				try {
					var bucket2 = buffer.addBucket();
					buffer.signalNoMoreBuckets();
					Thread.sleep(3);
					bucket2.write(new Message(2, 1));
					bucket2.close();
				} catch (Exception e) {
					t2exceptionHolder[0] = e;
				}
			});
			t1.start();
			t2.start();
			t1.join();
			t2.join();

			if (t2exceptionHolder[0] != null) fail(t2exceptionHolder[0].toString());
			assertEquals("all messages should be written", 2, outputData.size());
			assertTrue("messages should be written in order",
					Comparators.isInStrictOrder(outputData, messageComparator));
			assertEquals("stream should be closed 1 time", 1, closeCount);
		}
	}



	@Test
	public void testAddBucketAndSignalWhileFlushingTail()
			throws InterruptedException {
		// tries to trigger a race condition that was causing output to be closed too early
		for (int i = 0; i < 100; i++) {
			setup();
			var bucket1 = buffer.addBucket();
			bucket1.write(new Message(1, 1));
			var bucket2 = buffer.addBucket();
			bucket2.close();
			Exception[] t2exceptionHolder = {null};
			var t1 = new Thread(() -> bucket1.close());
			var t2 = new Thread(() -> {
				try {
					var bucket3 = buffer.addBucket();
					buffer.signalNoMoreBuckets();
					Thread.sleep(3);
					bucket3.write(new Message(3, 1));
					bucket3.close();
				} catch (Exception e) {
					t2exceptionHolder[0] = e;
				}
			});
			t1.start();
			t2.start();
			t1.join();
			t2.join();

			if (t2exceptionHolder[0] != null) fail(t2exceptionHolder[0].toString());
			assertEquals("all messages should be written", 2, outputData.size());
			assertTrue("messages should be written in order",
					Comparators.isInStrictOrder(outputData, messageComparator));
			assertEquals("stream should be closed 1 time", 1, closeCount);
		}
	}



	@Test
	public void testWriteMessageToClosedBucket() {
		OutputStream<Message> bucket = buffer.addBucket();
		bucket.close();
		try {
			bucket.write(new Message(666, 666));
			fail("IllegalStateException should be thrown");
		} catch (IllegalStateException e) {}
	}



	@Test
	public void testAddBucketAfterLastBucketSignaled() {
		buffer.addBucket();
		buffer.signalNoMoreBuckets();
		try {
			buffer.addBucket();
			fail("IllegalStateException should be thrown");
		} catch (IllegalStateException e) {}
	}



	static class Message {

		int bucket;
		int number;

		public Message(int bucket, int number) {
			this.bucket = bucket;
			this.number = number;
		}

		@Override
		public String toString() {
			return "msg-" + bucket + '-' + number;
		}
	}



	static Comparator<Message> messageComparator = (msg1, msg2) -> {
		int bucketCompare = Integer.compare(msg1.bucket, msg2.bucket);
		if (bucketCompare != 0) return bucketCompare;
		return Integer.compare(msg1.number, msg2.number);
	};



	@Before
	public void setup() {
		outputData = new LinkedList<>();
		closeCount = 0;
		outputStream = new OutputStream<>() {

			@Override
			public void write(Message message) {
				if (closeCount > 0) throw new IllegalStateException("output already closed");
				outputData.add(message);
				if (log.isLoggable(Level.FINEST)) log.finest(message.toString());
			}

			@Override
			public void close() {
				closeCount++;
				if (log.isLoggable(Level.FINER)) log.finer("closing output stream");
			}
		};
		buffer = new OrderedConcurrentOutputBuffer<>(outputStream);
	}



	// change the below value if you need logging
	// FINER will log adding/closing buckets and closing the output stream
	// FINEST will additionally log every message written to the output stream
	static final Level LOG_LEVEL = Level.OFF;

	static final Logger log = Logger.getLogger(OrderedConcurrentOutputBufferTest.class.getName());

	@BeforeClass
	public static void setupLogging() {
		var handler = new ConsoleHandler();
		handler.setLevel(LOG_LEVEL);
		log.addHandler(handler);
		log.setLevel(LOG_LEVEL);
	}
}
