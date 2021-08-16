// Copyright (c) Piotr Morgwai Kotarbinski, Licensed under the Apache License, Version 2.0
package pl.morgwai.base.utils;

import java.util.LinkedList;
import java.util.List;



/**
 * Buffers messages until all of those that should be written before to the output are available,
 * so that they all can be written in the correct order.
 * Useful for processing input streams in several concurrent threads when order of response messages
 * must reflect the order of request messages.<br/>
 * Note: this class should only be used if the response messages order requirement cannot be
 * dropped: if you control a given stream API, then it's more efficient to add some unique id to
 * request messages, include it in response messages and send them as soon as they are produced,
 * so nothing needs to be buffered.<br/>
 * <br/>
 * A buffer consists of ordered {@link Bucket Buckets}. Each bucket gets flushed automatically after
 * all previous buckets are flushed. A user can {@link #addBucket() add a new bucket at the end of a
 * buffer} and {@link Bucket#write(Object) write messages to it}. Within each bucket, messages are
 * written to the output in the order they were buffered.<br/>
 * <br/>
 * Bucket methods and {@link #signalNoMoreBuckets()} are all thread-safe. {@link #addBucket()} is
 * <b>not</b> thread-safe and concurrent invocations must be synchronized by the user (in case of
 * websockets and gRPC, it is usually not a problem as endpoints and request observers are
 * guaranteed to be called by only 1 thread at a time).
 */
public class OrderedConcurrentOutputBuffer<MessageT> {



	public static interface OutputStream<MessageT> {
		void write(MessageT message);
		void close();
	}



	public OrderedConcurrentOutputBuffer(OutputStream<MessageT> outputStream) {
		this.output = outputStream;
		preallocatedTailBucket = new Bucket();
		preallocatedTailBucket.buffer = null;
		noMoreBuckets = false;
	}



	/**
	 * Adds a new empty bucket at the end of this buffer. <b>Not</b> thread-safe.
	 * @return bucket placed right after the one returned by a previous call to this method (or the
	 *     first one if this is the first call)
	 * @throws IllegalStateException if {@link #signalNoMoreBuckets()} have been already called
	 */
	public OutputStream<MessageT> addBucket() {
		synchronized (preallocatedTailBucket) {
			if (noMoreBuckets) {
				throw new IllegalStateException("noMoreBuckets has been already signaled");
			}

			// return the current preallocatedTailBucket after adding a new one after it and
			// updating the pointer
			Bucket result = preallocatedTailBucket;
			preallocatedTailBucket.next = new Bucket();
			preallocatedTailBucket = preallocatedTailBucket.next;
			return result;
		}
	}



	/**
	 * Indicates that no more new buckets will be added. If all buckets are already closed and
	 * flushed, then the underlying output stream will be closed.
	 */
	public void signalNoMoreBuckets() {
		synchronized (preallocatedTailBucket) {
			noMoreBuckets = true;
			// if all buckets are closed & flushed, then close the output stream
			if (preallocatedTailBucket.buffer == null) output.close();
		}
	}



	OutputStream<MessageT> output;

	boolean noMoreBuckets;

	// A buffer always keeps a preallocated bucket at the tail of the queue. As addBucket() is
	// synchronized on the tail bucket, having a preallocated one there, prevents addBucket()
	// to be delayed if the last bucket handed to user has a huge number of buffered messages and
	// is just being flushed.
	Bucket preallocatedTailBucket;



	/**
	 * A list of messages that will have a well defined position relatively to other buckets within
	 * the {@link OrderedConcurrentOutputBuffer#output output stream}. All methods are thread-safe.
	 */
	class Bucket implements OutputStream<MessageT> {

		List<MessageT> buffer; // null <=> flushed <=> all previous buckets are closed & flushed
		boolean closed;
		Bucket next;  // null <=> this is the preallocatedTailBucket
		// (buffer == null && ! closed) <=> this is the head bucket (first unclosed & unflushed one)



		/**
		 * Appends <code>message</code> to the end of this bucket. If this is the head bucket (first
		 * unclosed one), then the message will be written directly to the output stream. Otherwise
		 * it will be buffered in this bucket until all the previous buckets are closed and flushed.
		 * Synchronized on this bucket.
		 * @throws IllegalStateException if the bucket is already closed
		 */
		@Override
		public synchronized void write(MessageT message) {
			if (closed) throw new IllegalStateException(BUCKET_CLOSED_MESSAGE);
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
			if (closed) throw new IllegalStateException(BUCKET_CLOSED_MESSAGE);
			closed = true;
			// if this is the head bucket, then flush subsequent continuous closed chain
			if (buffer == null) next.flush();
		}



		// Flushes this bucket and if it is already closed, then recursively flushes the next one.
		// If there is no next one (meaning this is preallocatedTailBucket) and
		// signalNoMoreBuckets() has been already called, then the underlying output stream will be
		// closed.
		private synchronized void flush() {
			for (MessageT bufferedMessage: buffer) output.write(bufferedMessage);
			buffer = null;
			if (next != null) {
				if (closed) next.flush();
			} else {  // this is preallocatedTailBucket, so all "real" buckets are closed & flushed
				if (noMoreBuckets) output.close();
			}
		}



		Bucket() {
			buffer = new LinkedList<>();
			closed = false;
			next = null;
		}
	}



	static final String BUCKET_CLOSED_MESSAGE = "bucket already closed";
}
