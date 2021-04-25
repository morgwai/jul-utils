/*
 * Copyright (c) Piotr Morgwai Kotarbinski
 */
package pl.morgwai.base.utils;

import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;



/**
 * Buffers messages until all of those that should be written before to the output are available,
 * so that they all can be written in the correct order.
 * Useful for processing input streams in several concurrent threads when order of response messages
 * should reflect the order of request messages.<br/>
 * A buffer consists of ordered {@link Bucket}s. Each bucket gets flushed automatically after all
 * previous buckets are flushed. A user can {@link #addBucket() add a new bucket at the end of a
 * buffer} and {@link Bucket#write(Object) write messages to it}. Within each bucket, messages are
 * written to the output in the order they were buffered.<br/>
 * Bucket methods and {@link #signalLastBucket()} are all thread-safe. {@link #addBucket()} is
 * <b>not</b> thread-safe and concurrent invocations must be synchronized by the user (in case of
 * websocket and gRPC, it is usually not a problem as endpoints and request observers are guaranteed
 * to be called by only 1 thread at a time).
 */
public class OrderedConcurrentOutputBuffer<MessageT> {



	public interface OutputStream<MessageT> {
		void write(MessageT message);
		void close();
	}



	OutputStream<MessageT> output;
	AtomicBoolean outputClosed;

	Bucket last;

	volatile boolean lastBucketSignaled;



	/**
	 * Adds a new empty bucket at the end of this buffer. <b>Not</b> thread-safe.
	 * @return newly added bucket
	 * @throws IllegalStateException if last bucket has been already signaled by a call to
	 *     {@link #signalLastBucket()}
	 */
	public Bucket addBucket() {
		if (lastBucketSignaled) {
			throw new IllegalStateException("last bucket has been already signaled");
		}
		var bucket = new Bucket();
		last.next = bucket;
		// SAFE RACE:
		// a thread that has flushed last right before the below check, will try to flush this new
		// bucket also, but flush() is idempotent
		if (last.closed && last.buffer == null) {
			bucket.flush();
		}
		last = bucket;
		return bucket;
	}



	/**
	 * A list of messages that will have a well defined position relatively to other buckets within
	 * the {@link OrderedConcurrentOutputBuffer#output output stream}. All methods are thread-safe.
	 */
	public class Bucket implements OutputStream<MessageT> {

		// the below 3 are volatile for addBucket()
		volatile List<MessageT> buffer;  // (buffer == null && ! closed) <=> this is the head bucket
		volatile boolean closed;
		volatile Bucket next;



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
			if (buffer == null) {
				output.write(message);
			} else {
				buffer.add(message);
			}
		}



		/**
		 * Marks this bucket as closed. The marking is synchronized on this bucket.<br/>
		 * If this was the head bucket (the first unclosed one), then flushes all buffered messages
		 * from subsequent buckets that can be sent now. Specifically, a continuous chain of
		 * subsequent closed buckets and the first unclosed one will be flushed. Each flushing is
		 * synchronized on the given bucket.<br/>
		 * The first unclosed bucket becomes the new head: its messages will be written directly to
		 * the underlying output stream.<br/>
		 * If all buckets until the last one (indicated by {@link #signalLastBucket()}) are flushed,
		 * then the underlying output stream will be closed.<br/>
		 * This method is not idempotent.
		 * @throws IllegalStateException if the bucket is already closed
		 */
		@Override
		public void close() {
			synchronized (this) {
				if (closed) throw new IllegalStateException(ALREADY_CLOSED_MESSAGE);
				closed = true;
				if (buffer != null) return;
			}

			// flush all closed buckets from the beginning of the queue
			Bucket headBucket = next;
			while (headBucket != null && headBucket.closed) {
				headBucket.flush();
				headBucket = headBucket.next;
			}

			if (headBucket != null) {
				// flush the new head bucket. it is still unclosed and its subsequent messages will
				// be written directly to the output now (safe race with addBucket(), see above)
				headBucket.flush();
			} else if (lastBucketSignaled && outputClosed.compareAndSet(false, true)) {
				// all buckets flushed (queue empty) and no more coming
				output.close();
			}
		}



		public Bucket() {
			buffer = new LinkedList<>();
			closed = false;
			next = null;
		}



		private static final String ALREADY_CLOSED_MESSAGE = "bucket already closed";
	}



	/**
	 * Indicates that no more new buckets will be added. If all buckets are already flushed, then
	 * the underlying stream will be closed.
	 */
	public void signalLastBucket() {
		synchronized (last) {
			lastBucketSignaled = true;
			if (last.closed && last.buffer == null && outputClosed.compareAndSet(false, true)) {
				output.close();
			}
		}
	}



	public OrderedConcurrentOutputBuffer(OutputStream<MessageT> outputStream) {
		this.output = outputStream;
		outputClosed = new AtomicBoolean(false);
		last = new Bucket();
		last.buffer = null;
		last.closed = true;
		lastBucketSignaled = false;
	}
}
