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
 * A buffer consists of ordered {@link Bucket}s. Each bucket gets flushed automatically after all
 * previous buckets are flushed. A user can {@link #addBucket() add a new bucket at the end of a
 * buffer} and {@link Bucket#write(Object) write messages to it}. Within each bucket, messages are
 * written to the output in the order they were buffered.<br/>
 * All methods are thread-safe, except that {@link #addBucket()} and {@link #signalLastBucket()}
 * must not be called concurrently with each other: see their respective documentations.
 */
public class OrderedConcurrentOutputBuffer<MessageT> {



	public interface OutputStream<MessageT> {
		void write(MessageT message);
		void close();
	}



	OutputStream<MessageT> output;

	AtomicInteger bucketCount;
	volatile int currentBucketNumber;  // the first bucket that has not yet been flushed/closed
	ConcurrentHashMap<Integer, Bucket> buckets;

	volatile boolean lastBucketSignaled;  // volatile only for sanity check in addBucket() below



	/**
	 * Adds a new empty bucket at the end of this buffer. Although not synchronized, this method is
	 * thread safe: it is safe to call this method from multiple concurrent threads, but the order
	 * of added buckets will be undefined in such case.<br/>
	 * This method <b>must not</b> be called concurrently with {@link #signalLastBucket()}
	 * (websocket endpoints and gRPC request observers are guaranteed to be called by only 1
	 * concurrent thread, so it's always safe if {@link #addBucket()} is called in
	 * <code>onNext(...) / onMessage(...)</code>, while {@link #signalLastBucket()} in
	 * <code>onCompleted() / onClose(...)</code> by the thread that originally invoked these
	 * methods).
	 * @return newly added bucket
	 * @throws IllegalStateException if last bucket has been already signaled
	 */
	public Bucket addBucket() {
		if (lastBucketSignaled) {
			throw new IllegalStateException("last bucket has been already signaled");
		}
		var bucket = new Bucket(bucketCount.incrementAndGet());
		// safe race with bucket.close(), see below
		buckets.put(bucket.bucketNumber, bucket);
		return bucket;
	}



	/**
	 * A list of messages that will have a well defined position relatively to other buckets within
	 * the {@link OrderedConcurrentOutputBuffer#output output stream}.
	 */
	public class Bucket implements OutputStream<MessageT> {

		int bucketNumber;
		List<MessageT> buffer;
		boolean closed;



		private synchronized void flush() {
			if (buffer != null) {
				for (MessageT bufferedMessage: buffer) output.write(bufferedMessage);
				buffer = null;
			}
		}



		/**
		 * Appends <code>message</code> to the end of this bucket. Synchronized on this bucket.
		 * @throws IllegalStateException if the bucket is already closed
		 */
		@Override
		public synchronized void write(MessageT message) {
			if (closed) throwAlreadyClosedException(bucketNumber);

			// SAFE RACE:
			// if currentBucket is increased by the thread that closed the previous bucket right
			// after the below check, it will synchronize on this bucketBuffer also and either will
			// flush it right after this thread appends a message to it, or another thread that
			// appends to this bucket will acquire bufferBucket's monitor first and flush it (see
			// else branch below) since volatile currentBucket was already increased.
			if (bucketNumber != currentBucketNumber) {
				buffer.add(message);
			} else {
				// SAFE RACE:
				// if the previous bucket has been just closed, the thread that done it will try
				// also to flush this bucket (which is now the new current one), but this thread
				// may acquire this bucket's monitor first (if the other thread yielded right after
				// increasing currentBucketNumber but before acquiring this bucket's monitor), so
				// we need to try to flush here as well.
				flush();
				output.write(message);
			}
		}



		/**
		 * Closes this bucket. If all the previous buckets are already flushed, then this bucket
		 * will also be automatically flushed together with all subsequent closed buckets. Such
		 * sequence of flushing is synchronized on the whole buffer. If all buckets until the last
		 * one (indicated by {@link #signalLastBucket()} are flushed, then the underlying output
		 * stream will be closed.<br/>
		 * This method is <b>not</b> idempotent.
		 * @throws IllegalStateException if the bucket is already closed
		 */
		@Override
		public void close() {
			synchronized (this) {
				if (closed) throwAlreadyClosedException(bucketNumber);
				closed = true;
			}

			synchronized (OrderedConcurrentOutputBuffer.this) {
				if (bucketNumber != currentBucketNumber) return;

				// flush completed buckets from the beginning of the buffer (including this one:
				// if it was closed immediately after the previous, without any messages added,
				// it is not flushed yet)
				var currentBucket = buckets.get(currentBucketNumber);
				// SAFE RACE:
				// if currentBucket is just being added in addBucket(), its object may not have
				// been created yet (but bucketCount may have been already increased).
				// If it gets closed after this check, then the flushing will continue.
				while (currentBucket != null && currentBucket.closed) {
					currentBucket.flush();
					currentBucketNumber++;  // safe race with write(...), see above
					currentBucket = buckets.get(currentBucketNumber);
				}

				if (currentBucketNumber <= bucketCount.get()) {
					// SAFE RACE:
					// if currentBucket is just being added in addBucket(), its object may not have
					// been created yet (but bucketCount may have been already increased).
					// nothing to flush anyway and subsequent write to currentBucket will not be
					// buffered and will go directly to the stream as currentBucket is volatile
					if (currentBucket != null) {
						// flush the new current bucket (safe race with write(...), see above)
						currentBucket.flush();
					}
				} else if (lastBucketSignaled) {
					// all buckets flushed, if no more coming then close the output stream
					output.close();
				}
			}
		}



		public Bucket(int bucketNumber) {
			this.bucketNumber = bucketNumber;
			buffer = new LinkedList<>();
			closed = false;
		}



		private void throwAlreadyClosedException(int bucketNumber) {
			throw new IllegalStateException("bucket " + bucketNumber + " is already closed");
		}
	}



	/**
	 * Indicates that no more new buckets will be added. If all buckets are already flushed, then
	 * the underlying stream will be closed.<br/>
	 * This method <b>must not</b> be called concurrently with {@link #addBucket()}
	 * (websocket endpoints and gRPC request observers are guaranteed to be called by only 1
	 * concurrent thread, so it's always safe if {@link #addBucket()} is called in
	 * <code>onNext(...) / onMessage(...)</code>, while {@link #signalLastBucket()} in
	 * <code>onCompleted() / onClose(...)</code> by the thread that originally invoked these
	 * methods).
	 */
	public synchronized void signalLastBucket() {
		lastBucketSignaled = true;
		if (currentBucketNumber > bucketCount.get()) {
			output.close();
		}
	}



	public OrderedConcurrentOutputBuffer(OutputStream<MessageT> outputStream) {
		this.output = outputStream;

		bucketCount = new AtomicInteger(0);
		currentBucketNumber = 1;
		buckets = new ConcurrentHashMap<>();

		lastBucketSignaled = false;
	}
}
