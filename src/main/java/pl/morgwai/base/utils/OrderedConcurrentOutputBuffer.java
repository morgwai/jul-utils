/*
 * Copyright (c) Piotr Morgwai Kotarbinski
 */
package pl.morgwai.base.utils;

import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;



/**
 * Buffers messages until all of those that should be written before to the output are available,
 * so that they all can be written in the correct order.
 * Useful for processing input streams in several concurrent threads when order of response messages
 * should reflect the order of request messages.<br/>
 * A buffer consists of ordered buckets. Each bucket gets flushed automatically after all previous
 * buckets are flushed. A user can {@link #addBucket() add a new bucket at the end of the buffer}
 * and {@link #append(MessageT, int) append messages to it}. Within each bucket, messages are
 * written to the output in the order they were appended.<br/>
 * All methods are thread-safe, but it is an error to append to a closed bucket, and
 * {@link #closeBucket(int)} is <b>not</b> idempotent, so if several threads append to the same
 * bucket, only 1 of them can close it and they must all synchronize before it.<br/>
 * Although bucket numbers can be easily predicted, it is an error to append to a bucket or close it
 * before {@link #addBucket()} returns.
 */
public class OrderedConcurrentOutputBuffer<MessageT> {



	public interface OutputStream<MessageT> {
		void write(MessageT message);
		void close();
	}
	OutputStream<MessageT> stream;

	AtomicInteger bucketCount;
	volatile int currentBucket;  // the first bucket that has not yet been flushed/closed

	ConcurrentHashMap<Integer, List<MessageT>> bucketBuffers;
	ConcurrentHashMap<Integer, Object> closedBuckets;  // contains(bucket) <=> isClosed(bucket)

	volatile boolean lastBucketSignaled;  // volatile only for sanity check in addBucket() below



	/**
	 * Adds a new empty bucket at the end of this buffer.
	 * @return bucket number
	 */
	public int addBucket() {
		if (lastBucketSignaled) {
			throw new IllegalStateException("last bucket has been already signaled");
		}
		int bucketNumber = bucketCount.incrementAndGet();
		bucketBuffers.put(bucketNumber, new LinkedList<>());
		return bucketNumber;
	}



	/**
	 * Appends <code>message</code> to the end of the bucket specified by <code>bucketNumber</code>.
	 */
	public void append(MessageT message, int bucketNumber) {
		checkValid(bucketNumber);
		if (closedBuckets.containsKey(bucketNumber)) throwAlreadyClosedException(bucketNumber);
		var bucketBuffer = bucketBuffers.get(bucketNumber);
		synchronized (bucketBuffer) {
			// SAFE RACE:
			// if currentBucket is increased by the thread that closed the previous bucket right
			// after the below check, it will synchronize on this bucketBuffer also and either will
			// flush it right after this thread appends a message to it, or another thread that
			// appends to this bucket will acquire bufferBucket's monitor first and flush it since
			// volatile currentBucket was already increased.
			if (bucketNumber != currentBucket) {
				bucketBuffer.add(message);
			} else {
				// if the previous bucket was just closed, then need to flush our buffer first
				for (MessageT bufferedMessage: bucketBuffer) {
					stream.write(bufferedMessage);
				}
				bucketBuffer.clear();

				stream.write(message);
			}
		}
	}



	/**
	 * Closes the bucket specified by <code>bucketNumber</code>. If all the previous buckets are
	 * already flushed, then this bucket will be also flushed automatically. If this is the last
	 * bucket (indicated by {@link #signalLastBucket()}, then the underlying output stream will be
	 * closed.
	 */
	public synchronized void closeBucket(int bucketNumber) {
		checkValid(bucketNumber);
		if (closedBuckets.putIfAbsent(bucketNumber, new Object()) != null) {
			throwAlreadyClosedException(bucketNumber);
		}
		if (bucketNumber > currentBucket) return;

		// flush completed buckets from the beginning of the buffer (including this one: if it was
		// closed immediately after the previous, without any messages added, it is not flushed yet)
		while (closedBuckets.containsKey(currentBucket)) {
			for (MessageT bufferedMessage: bucketBuffers.remove(currentBucket)) {
				stream.write(bufferedMessage);
			}
			currentBucket++;
		}

		if (currentBucket <= bucketCount.get()) {
			// flush the beginning of the new current bucket
			var bucketBuffer = bucketBuffers.get(currentBucket);
			if (bucketBuffer == null) {
				// SAFE RACE:
				// currentBucket was just added and buffer not created yet in addBucket(), so
				// nothing to flush anyway.
				// As currentBucket is volatile, the next append will write directly to the stream.
				return;
			}
			synchronized (bucketBuffer) {
				for (MessageT bufferedMessage: bucketBuffer) {
					stream.write(bufferedMessage);
				}
				bucketBuffer.clear();
			}
		} else if (lastBucketSignaled) {
			// all buckets flushed, if no more coming then close the output stream
			stream.close();
		}
	}



	private void checkValid(int bucketNumber) {
		if (bucketNumber > bucketCount.get() || bucketNumber < 1) {
			throw new IllegalArgumentException("invalid bucket " + bucketNumber);
		}
	}

	private void throwAlreadyClosedException(int bucketNumber) {
		throw new IllegalStateException(
				"bucket " + bucketNumber + " has been already closed");
	}



	/**
	 * Indicates that no more new buckets will be added. If all buckets are already flushed, then
	 * the underlying stream will be closed.
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
		closedBuckets = new ConcurrentHashMap<>();

		lastBucketSignaled = false;
	}
}
