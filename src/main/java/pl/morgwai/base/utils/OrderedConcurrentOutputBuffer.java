/*
 * Copyright (c) Piotr Morgwai Kotarbinski
 */
package pl.morgwai.base.utils;

import java.util.ConcurrentModificationException;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;



/**
 * Buffers messages until all of those that should be written before to the output are available,
 * so that they all can be written in the correct order.
 * Useful for processing input streams in several concurrent threads when order of response messages
 * should reflect the order of request messages.<br/>
 * A buffer consists of ordered buckets. Each bucket gets flushed automatically when all previous
 * buckets were flushed. A user can {@link #addBucket() add a new bucket at the end of the buffer}
 * and {@link #append(MessageT, int) append messages to it}. Within each bucket, messages are
 * written to the output in the order they were appended.<br/>
 * All methods are thread-safe <b>except {@link #append(MessageT, int)}</b>.
 */
public class OrderedConcurrentOutputBuffer<MessageT> {



	public interface OutputStream<MessageT> {
		void write(MessageT message);
		void close();
	}
	OutputStream<MessageT> stream;

	AtomicInteger bucketCount;
	volatile int currentBucket;  // the first bucket that has not yet been flushed

	ConcurrentHashMap<Integer, List<MessageT>> bucketBuffers;
	ConcurrentHashMap<Integer, Object> bucketGuards;  // non-null => being processed
	Set<Integer> closedBuckets;

	volatile boolean lastBucketSignaled;



	/**
	 * Adds a new empty bucket at the end of this buffer.
	 * @return bucket number
	 */
	public int addBucket() {
		if (lastBucketSignaled) {
			throw new IllegalStateException("last bucket has been already signaled");
		}
		return bucketCount.incrementAndGet();
	}



	/**
	 * Appends <code>message</code> to the end of the bucket specified by <code>bucketNumber</code>.
	 * A single bucket is thread-compatible, but <b>not thread-safe</b>: if multiple threads append
	 * to a single bucket concurrently, they must be properly synchronized. Violation may result in
	 * <code>ConcurrentModificationException</code>, but it is not guaranteed.
	 */
	public void append(MessageT message, int bucketNumber) {
		guardBucket(bucketNumber);
		try {
			// it doesn't matter if currentBucket is increased by the thread handling the previous
			// bucket right after the below check: a thread handling this bucket will flush the
			// buffer upon next call to addMessage(...) or closeBucket(...)
			if (currentBucket == bucketNumber) {
				// if the previous bucket was just closed, then flush our buffer first
				var bufferedMessages = bucketBuffers.remove(currentBucket);
				if (bufferedMessages != null) {
					for (MessageT bufferedMessage: bufferedMessages) {
						stream.write(bufferedMessage);
					}
				}

				stream.write(message);
				return;
			}

			// only 1 concurrent thread can add to a bucket: no need to use putIfAbsent(...) here
			var bucketBuffer = bucketBuffers.get(bucketNumber);
			if (bucketBuffer == null) {
				bucketBuffer = new LinkedList<>();
				bucketBuffers.put(bucketNumber, bucketBuffer);
			}
			bucketBuffer.add(message);
		} finally {
			bucketGuards.remove(bucketNumber);
		}
	}



	/**
	 * Closes the bucket specified by <code>bucketNumber</code>. If all the previous buckets are
	 * already flushed, then this bucket will be also flushed automatically. If this is the last
	 * bucket (indicated by {@link #signalLastBucket()}, then the underlying output stream will be
	 * closed.
	 */
	public synchronized void closeBucket(int bucketNumber) {
		guardBucket(bucketNumber);
		// do not release guard at the end: no more messages can be added

		closedBuckets.add(bucketNumber);
		if (bucketNumber > currentBucket) return;

		// flush completed buckets from the beginning of the buffer (including this one: if it was
		// closed immediately after the previous, without any messages added, it is not flushed yet)
		while (closedBuckets.contains(currentBucket)) {
			var bufferedMessages = bucketBuffers.remove(currentBucket);
			if (bufferedMessages != null) {
				for (MessageT bufferedMessage: bufferedMessages) {
					stream.write(bufferedMessage);
				}
			}
			currentBucket++;
		}
		// beginning of the new currentBucket cannot be flushed here as this would introduce a
		// race between thread handling bucketNumber and the one handling the new currentBucket.
		// This unnecessarily buffers messages previously appended to the new currentBucket until a
		// next message is appended or the bucket is closed, but otherwise append(...) would require
		// synchronization with the whole buffer.

		if (lastBucketSignaled && currentBucket > bucketCount.get()) {
			stream.close();
		}
	}



	/**
	 * Attempts to prevent concurrent appends and checks some basic preconditions.
	 */
	private void guardBucket(int bucketNumber) {
		if (bucketNumber > bucketCount.get() || bucketNumber < 1) {
			throw new IllegalArgumentException("invalid bucket " + bucketNumber);
		}
		Object guard = new Object();
		bucketGuards.putIfAbsent(bucketNumber, guard);
		if (guard != bucketGuards.get(bucketNumber)) {
			if (closedBuckets.contains(bucketNumber)) {
				throw new IllegalStateException(
						"bucket " + bucketNumber + " has been already closed");
			} else {
				throw new ConcurrentModificationException(
						"more than 1 thread operate on bucket " + bucketNumber);
			}
		}
	}



	/**
	 * Indicates that no more new buckets will be added. If all buckets are already flushed, then
	 * the underlying stream will closed.
	 */
	public synchronized void signalLastBucket() {
		lastBucketSignaled = true;
		if (currentBucket > bucketCount.get()) {
			stream.close();
		}
	}



	public OrderedConcurrentOutputBuffer(OutputStream<MessageT> stream) {
		this.stream = stream;

		bucketCount = new AtomicInteger(0);
		currentBucket = 1;

		bucketBuffers = new ConcurrentHashMap<>();
		bucketGuards = new ConcurrentHashMap<>();
		closedBuckets = new HashSet<>();

		lastBucketSignaled = false;
	}
}
