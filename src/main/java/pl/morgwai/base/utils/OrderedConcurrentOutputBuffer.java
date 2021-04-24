/*
 * Copyright (c) Piotr Morgwai Kotarbinski
 */
package pl.morgwai.base.utils;

import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;



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

	ConcurrentLinkedQueue<Bucket> buckets;

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
	 * @throws IllegalStateException if last bucket has been already signaled by a call to
	 *     {@link #signalLastBucket()}
	 */
	public Bucket addBucket() {
		if (lastBucketSignaled) {
			throw new IllegalStateException("last bucket has been already signaled");
		}
		var bucket = new Bucket();
		buckets.add(bucket);
		return bucket;
	}



	/**
	 * A list of messages that will have a well defined position relatively to other buckets within
	 * the {@link OrderedConcurrentOutputBuffer#output output stream}.
	 */
	public class Bucket implements OutputStream<MessageT> {

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
			if (closed) throw new IllegalStateException(ALREADY_CLOSED_MESSAGE);

			// SAFE RACE:
			// if the head of buckets queue is advanced right after the below check by the thread,
			// that flushed the previous bucket, it will also synchronize on this bucket and will
			// flush it right after this thread appends the message to it.
			// (in case other threads also write to this bucket, one of them may also acquire its
			// monitor first and will flush it as queue's head was already advanced: see the else
			// branch below).
			if (this != buckets.peek()) {
				buffer.add(message);
			} else {
				// SAFE RACE:
				// the thread, that flushed the previous bucket and advanced the head, will try to
				// flush this bucket also, but flush is idempotent.
				// flush must be called here, as write(...) can be called after the other thread
				// advanced the head, but before acquired this bucket's monitor.
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
				if (closed) throw new IllegalStateException(ALREADY_CLOSED_MESSAGE);
				closed = true;
			}

			synchronized (OrderedConcurrentOutputBuffer.this) {
				if (this != buckets.peek()) return;

				// flush all closed buckets from the beginning of the queue
				var headBucket = buckets.peek();
				while (headBucket != null && headBucket.closed) {
					headBucket.flush();
					buckets.remove();  // safe race with write(), see above
					headBucket = buckets.peek();
				}

				if (headBucket != null) {
					// flush the new head bucket, so its buffered messages are not retained until
					// the next write or close (safe race with write(), see above)
					headBucket.flush();
				} else if (lastBucketSignaled) {
					// all buckets flushed (queue empty) and no more coming
					output.close();
				}
			}
		}



		public Bucket() {
			buffer = new LinkedList<>();
			closed = false;
		}



		private static final String ALREADY_CLOSED_MESSAGE = "bucket already closed";
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
		if (buckets.isEmpty()) output.close();
	}



	public OrderedConcurrentOutputBuffer(OutputStream<MessageT> outputStream) {
		this.output = outputStream;
		buckets = new ConcurrentLinkedQueue<>();
		lastBucketSignaled = false;
	}
}
