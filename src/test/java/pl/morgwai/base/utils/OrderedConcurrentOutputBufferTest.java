/*
 * Copyright (c) Piotr Morgwai Kotarbinski
 */
package pl.morgwai.base.utils;

import java.util.Comparator;
import java.util.ConcurrentModificationException;
import java.util.LinkedList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import com.google.common.collect.Comparators;

import pl.morgwai.base.utils.OrderedConcurrentOutputBuffer.OutputStream;

import static org.junit.Assert.*;



public class OrderedConcurrentOutputBufferTest {



	OrderedConcurrentOutputBuffer<Message> buffer;

	OutputStream<Message> stream;
	List<Message> outputData;
	boolean closed;



	int[] sequences;

	void append(int bucketNumber) {
		buffer.append(new Message(bucketNumber, ++sequences[bucketNumber-1]), bucketNumber);
	}



	@Test
	public void testSingleThread() {
		sequences = new int[5];
		int bucket1 = buffer.addBucket();
		int bucket2 = buffer.addBucket();
		append(bucket2);
		append(bucket2);
		append(bucket1);
		append(bucket2);
		append(bucket1);
		append(bucket2);
		int bucket3 = buffer.addBucket();
		append(bucket2);
		append(bucket3);
		append(bucket1);
		append(bucket3);
		append(bucket2);
		append(bucket3);
		buffer.closeBucket(bucket2);
		append(bucket1);
		append(bucket1);
		append(bucket3);
		append(bucket1);
		int bucket4 = buffer.addBucket();
		buffer.closeBucket(bucket1);
		buffer.closeBucket(bucket3);
		int bucket5 = buffer.addBucket();
		buffer.signalLastBucket();
		append(bucket5);
		buffer.closeBucket(bucket4);
		append(bucket5);
		buffer.closeBucket(bucket5);

		assertTrue("stream should be closed", closed);
		assertTrue("messages should be written in order",
				Comparators.isInStrictOrder(outputData, new MessageComparator()));
	}



	private void addBucketWith5to10kMessages() {
		int bucket = buffer.addBucket();
		if (bucket % 20 == 0) {
			// make some threads a bit slower to start
			try {
				Thread.sleep(100);
			} catch (InterruptedException e) {}
		}
		int messageCount = 5000 + (1000 * (bucket % 6));
		for (int i = 0; i < messageCount; i++) {
			append(bucket);
		}
		buffer.closeBucket(bucket);
	}



	@Test
	public void test1000Threds5to10kMessagesEach() throws InterruptedException {
		int bucketCount = 1000;
		sequences = new int[bucketCount];
		Thread[] threads = new Thread[bucketCount];
		for (int i = 0; i < threads.length; i++) {
			threads[i] = new Thread(() -> addBucketWith5to10kMessages());
		}
		for (int i = 0; i < threads.length; i++) threads[i].start();
		for (int i = 0; i < threads.length; i++) threads[i].join();
		buffer.signalLastBucket();

		assertTrue("stream should be closed", closed);
		assertTrue("messages should be written in order",
				Comparators.isInStrictOrder(outputData, new MessageComparator()));
	}



	@Test
	public void testInvalidBucketNumber() {
		buffer.addBucket();
		int bucket = buffer.addBucket();
		try {
			buffer.append(new Message(1, 1), bucket + 10);
			fail("IllegalArgumentException should be thrown");
		} catch (IllegalArgumentException e) {}
	}



	@Test
	public void testAddMessageToClosedBucket() {
		sequences = new int[1];
		int bucket = buffer.addBucket();
		buffer.closeBucket(bucket);
		try {
			append(bucket);
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



	@Test(timeout = 1000l)
	public void testConcurrentModificationOfBucket() throws InterruptedException {
		final boolean[] thrown = { false };
		// nondeterministic (ConcurrentModificationException is not guaranteed to be thrown),
		// so retry in a loop until desired result or timeout (succeeds 99% of tries)
		while ( ! thrown[0]) {
			setup();
			int bucket = buffer.addBucket();
			Thread[] threads = new Thread[100];
			for (int i = 0; i < threads.length; i++) {
				threads[i] = new Thread(() -> {
					try {
						buffer.append(new Message(0, 0), bucket);
					} catch (ConcurrentModificationException e) {
						thrown[0] = true;
					}
				});
			}
			for (int i = 0; i < threads.length; i++) threads[i].start();
			for (int i = 0; i < threads.length; i++) threads[i].join();
		}
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
		closed = false;
		stream = new OutputStream<>() {

			@Override
			public void write(Message value) {
				outputData.add(value);
			}

			@Override
			public void close() {
				closed = true;
			}
		};
		buffer = new OrderedConcurrentOutputBuffer<>(stream);
	}
}
