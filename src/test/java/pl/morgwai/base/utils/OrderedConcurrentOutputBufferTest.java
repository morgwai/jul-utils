/*
 * Copyright (c) Piotr Morgwai Kotarbinski
 */
package pl.morgwai.base.utils;

import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

import com.google.common.collect.Comparators;

import pl.morgwai.base.utils.OrderedConcurrentOutputBuffer.OutputStream;

import static org.junit.Assert.*;



public class OrderedConcurrentOutputBufferTest {



	OrderedConcurrentOutputBuffer<Message> buffer;

	OutputStream<Message> outputStream;
	List<Message> outputData;
	volatile int closeCount;



	int[] bucketMessageNumbers;

	Message nextMessage(int bucketNumber) {
		return new Message(bucketNumber, ++bucketMessageNumbers[bucketNumber - 1]);
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
		buffer.signalLastBucket();
		bucket4.close();

		assertEquals("stream should be closed 1 time", 1, closeCount);
		assertTrue("messages should be written in order",
				Comparators.isInStrictOrder(outputData, new MessageComparator()));
	}



	@Test
	public void test1000Threds10kPerThread() throws InterruptedException {
		test1000Threds(10_000);
	}

	@Test
	public void test1000Threds1PerThread() throws InterruptedException {
		test1000Threds(1, 1, 1, 1, 0);
	}

	private void test1000Threds(int... messagesPerThread) throws InterruptedException {
		bucketCount = 0;
		int targetBucketNumber = 1000;
		bucketMessageNumbers = new int[targetBucketNumber];
		Thread[] threads = new Thread[targetBucketNumber];
		for (int i = 0; i < threads.length; i++) {
			threads[i] = newBucketThread(messagesPerThread[i % messagesPerThread.length]);
		}
		for (int i = 0; i < threads.length; i++) threads[i].start();
		for (int i = 0; i < threads.length; i++) threads[i].join();
		buffer.signalLastBucket();

		assertEquals("stream should be closed 1 time", 1, closeCount);
		assertTrue("messages should be written in order",
				Comparators.isInStrictOrder(outputData, new MessageComparator()));
	}

	int bucketCount;

	private Thread newBucketThread(int messageCount) {
		return new Thread(() -> {
			int bucketNumber;
			OutputStream<Message> bucket;
			synchronized (OrderedConcurrentOutputBufferTest.this) {
				bucketNumber = ++bucketCount;
				bucket = buffer.addBucket();
			}
			if (bucketNumber % 17 == 0) {
				// make some threads a bit slower to start
				try {
					Thread.sleep(100);
				} catch (InterruptedException e) {}
			}
			for (int i = 0; i < messageCount; i++) {
				bucket.write(nextMessage(bucketNumber));
			}
			bucket.close();
		});
	}



	@Test
	public void testWriteMessageToClosedBucket() {
		OutputStream<Message> bucket = buffer.addBucket();
		bucket.close();
		try {
			bucket.write(new Message(666, 666));;
			fail("IllegalStateException should be thrown");
		} catch (IllegalStateException e) {}
	}



	@Test
	public void testAddBucketAfterLastBucketSignaled() {
		buffer.addBucket();
		buffer.signalLastBucket();
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



	static class MessageComparator implements Comparator<Message> {

		@Override
		public int compare(Message o1, Message o2) {
			int bucketCompare = Integer.compare(o1.bucket, o2.bucket);
			if (bucketCompare != 0) return bucketCompare;
			return Integer.compare(o1.number, o2.number);
		}
	}



	@Before
	public void setup() {
		outputData = new LinkedList<>();
		closeCount = 0;
		outputStream = new OutputStream<>() {

			@Override
			public void write(Message value) {
				if (closeCount > 0) throw new IllegalStateException("output already closed");
				outputData.add(value);
			}

			@Override
			public void close() {
				closeCount++;
			}
		};
		buffer = new OrderedConcurrentOutputBuffer<>(outputStream);
	}
}
