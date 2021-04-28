/*
 * Copyright (c) Piotr Morgwai Kotarbinski
 */
package pl.morgwai.base.utils;

import java.util.LinkedList;
import java.util.List;



/**
 * Buffers messages until all of those that should be written before to the output are available,
 * so that they all can be written in the correct order.
 * Useful for processing input streams in several concurrent threads when order of response messages
 * should reflect the order of request messages.<br/>
 * A buffer consists of ordered {@link Bucket}s. Each bucket gets flushed automatically after all
 * previous buckets are flushed. A user can {@link #addBucket() add a new bucket at the end of a
 * buffer} and {@link Bucket#write(Object) write messages to it}. Within each bucket, messages are
 * written to the output in the order they were buffered.<br/>
 * Bucket methods and {@link #signalNoMoreBuckets()} are all thread-safe. {@link #addBucket()} is
 * <b>not</b> thread-safe and concurrent invocations must be synchronized by the user (in case of
 * websockets and gRPC, it is usually not a problem as endpoints and request observers are
 * guaranteed to be called by only 1 thread at a time).
 */
public class OrderedConcurrentOutputBuffer<MessageT> {



	public interface OutputStream<MessageT> {
		void write(MessageT message);
		void close();
	}



	OutputStream<MessageT> output;

	Bucket preallocatedTailBucket;  // flushed <=> all previous closed

	boolean noMoreBuckets;



	/**
	 * Adds a new empty bucket at the end of this buffer. <b>Not</b> thread-safe.
	 * @return newly added bucket
	 * @throws IllegalStateException if {@link #signalNoMoreBuckets()} have been already called
	 */
	public Bucket addBucket() {
		var newBucket = new Bucket();
		synchronized (preallocatedTailBucket) {
			if (noMoreBuckets) {
				throw new IllegalStateException("noMoreBuckets has been already signaled");
			}
			preallocatedTailBucket.next = newBucket;
			Bucket result = preallocatedTailBucket;
			preallocatedTailBucket = newBucket;
			return result;
		}
	}



	/**
	 * A list of messages that will have a well defined position relatively to other buckets within
	 * the {@link OrderedConcurrentOutputBuffer#output output stream}. All methods are thread-safe.
	 */
	public class Bucket implements OutputStream<MessageT> {

		List<MessageT> buffer;  // (buffer == null && ! closed) <=> this is the head bucket
		boolean closed;
		Bucket next;



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
		 * If this is the head bucket (the first unclosed one), then flushes all buffered messages
		 * from subsequent buckets that can be sent now. Specifically, a continuous chain of
		 * subsequent closed buckets and the first unclosed one will be flushed.
		 * Each flushing is synchronized on the given bucket.<br/>
		 * The first unclosed bucket becomes the new head: its messages will be written directly to
		 * the underlying output stream from now on.<br/>
		 * If all buckets until the last one (indicated by {@link #signalNoMoreBuckets()}) are
		 * closed and flushed, then the underlying output stream will be closed.<br/>
		 * This method is not idempotent.
		 * @throws IllegalStateException if the bucket is already closed
		 */
		@Override
		public synchronized void close() {
			if (closed) throw new IllegalStateException(ALREADY_CLOSED_MESSAGE);
			closed = true;
			if (buffer == null) next.flush(); // this was the head bucket
		}



		/**
		 * Flushes this bucket and if it is already closed, then recursively flushes the next one.
		 * If it is already closed and there is no next one and {@link #signalNoMoreBuckets()} has
		 * been already called, then the underlying output stream will be closed.
		 */
		private synchronized void flush() {
			for (MessageT bufferedMessage: buffer) output.write(bufferedMessage);
			buffer = null;
			if (next != null) {
				if (closed) next.flush();
			} else {  // this is the preallocatedTailBucket
				if (noMoreBuckets) output.close();  // all buckets flushed and no more coming
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
	 * Indicates that no more new buckets will be added. If all buckets are already closed and
	 * flushed, then the underlying output stream will be closed.
	 */
	public void signalNoMoreBuckets() {
		synchronized (preallocatedTailBucket) {
			noMoreBuckets = true;
			if (preallocatedTailBucket.buffer == null) output.close();
		}
	}



	public OrderedConcurrentOutputBuffer(OutputStream<MessageT> outputStream) {
		this.output = outputStream;
		preallocatedTailBucket = new Bucket();
		preallocatedTailBucket.buffer = null;
		preallocatedTailBucket.closed = false;
		noMoreBuckets = false;
	}
}
