// Copyright (c) Piotr Morgwai Kotarbinski, Licensed under the Apache License, Version 2.0
package pl.morgwai.base.utils;

import java.util.LinkedList;
import java.util.List;



/**
 * Buffers messages until all of those that should be written before to the output are available,
 * so that they all can be written in the correct order.
 * Useful for processing input streams in several concurrent threads when order of response messages
 * must reflect the order of request messages.
 * <p>
 * A buffer consists of ordered buckets implementing {@link OutputStream} just as the underlying
 * output stream passed to {@link #OrderedConcurrentOutputBuffer(OutputStream) the constructor}.
 * Each bucket gets flushed automatically to the output stream after all the previous buckets are
 * {@link OutputStream#close() closed}. A user can {@link #addBucket() add a new bucket} at the end
 * of the buffer, {@link OutputStream#write(Object) write messages} to it and finally
 * {@link OutputStream#close() close it} to indicate that no more messages will be written to it and
 * trigger flushing of subsequent bucket(s).<br/>
 * Within each bucket, messages are written to the output in the order they were buffered.</p>
 * <p>
 * All bucket methods and {@link #signalNoMoreBuckets()} are thread-safe. {@link #addBucket()} is
 * <b>not</b> thread-safe and concurrent invocations must be synchronized (in case of websockets and
 * gRPC, it is usually not a problem as endpoints and request observers are guaranteed to be called
 * by only 1 thread at a time).</p>
 * <p>
 * Note: this class should only be used if the response messages order requirement cannot be
 * dropped: if you control a given stream API, then it's more efficient to add some unique id to
 * request messages, include it in response messages and send them as soon as they are produced,
 * so nothing needs to be buffered.</p>
 */
public class OrderedConcurrentOutputBuffer<MessageT> {



	public static interface OutputStream<MessageT> {
		void write(MessageT message);
		void close();
	}



	public OrderedConcurrentOutputBuffer(OutputStream<MessageT> outputStream) {
		this.output = outputStream;
		tailGuard = new Bucket();
		tailGuard.buffer = null;
		noMoreBuckets = false;
	}



	/**
	 * Adds a new empty bucket at the end of this buffer. <b>Not</b> thread-safe.
	 * @return bucket placed right after the one returned by a previous call to this method (or the
	 *     first one if this is the first call). All methods of the returned bucket are thread-safe.
	 * @throws IllegalStateException if {@link #signalNoMoreBuckets()} have been already called.
	 */
	public OutputStream<MessageT> addBucket() {
		synchronized (tailGuard.lock) {
			if (noMoreBuckets) {
				throw new IllegalStateException("noMoreBuckets has been already signaled");
			}

			// return the current tailGuard after adding a new one after it and
			// updating the pointer
			Bucket result = tailGuard;
			tailGuard.next = new Bucket();
			tailGuard = tailGuard.next;
			return result;
		}
	}



	/**
	 * Indicates that no more new buckets will be added. If all buckets are already closed and
	 * flushed, then the underlying output stream will be closed. Thread-safe.
	 */
	public void signalNoMoreBuckets() {
		synchronized (tailGuard.lock) {
			noMoreBuckets = true;
			// if all buckets are closed & flushed, then close the output stream
			if (tailGuard.buffer == null) output.close();
		}
	}



	OutputStream<MessageT> output;

	boolean noMoreBuckets;

	// A buffer always has a preallocated guard bucket at the tail of the queue. As addBucket() is
	// synchronized on the tail, having a guard, prevents addBucket() to be delayed if the last
	// bucket handed to user has a huge number of buffered messages and is just being flushed.
	Bucket tailGuard;



	// A list of messages that will have a well defined position relatively to other buckets within
	// the output stream. All methods are thread-safe.
	class Bucket implements OutputStream<MessageT> {

		Object lock = new Object();

		List<MessageT> buffer = new LinkedList<>();// null <=> flushed <=> all previous also flushed
		boolean closed;
		Bucket next;  // null <=> this is the tailGuard
		// (buffer == null && ! closed) <=> this is the head bucket (first unclosed one)



		// Appends *message* to the end of this bucket. Synchronized on this bucket.
		// If this is the head bucket (first unclosed one), then the message will be written
		// directly to the output stream. Otherwise it will be buffered in this bucket until all the
		// previous buckets are closed and flushed.
		@Override
		public void write(MessageT message) {
			synchronized (lock) {
				if (closed) throw new IllegalStateException(BUCKET_CLOSED_MESSAGE);
				if (buffer == null) {
					output.write(message);
				} else {
					buffer.add(message);
				}
			}
		}



		// Marks this bucket as closed. Synchronized on this bucket. Idempotent.
		// If this is the head bucket (the first unclosed one), then flushes all buffered messages
		// from subsequent buckets that can be sent now. Specifically, a continuous chain of
		// subsequent closed buckets and the first unclosed one will be flushed.
		// Each flushing is synchronized on the given bucket.
		// The first unclosed bucket becomes the new head: its messages will be written directly to
		// the underlying output stream from now on.
		// If all buckets are closed & flushed and signalNoMoreBuckets() has already been called,
		// then the underlying output stream will be closed.
		@Override
		public void close() {
			synchronized (lock) {
				if (closed) throw new IllegalStateException(BUCKET_CLOSED_MESSAGE);
				closed = true;
				// if this is the head bucket, then flush subsequent continuous closed chain
				if (buffer == null) next.flush();
			}
		}



		// Flushes this bucket and if it is already closed, then recursively flushes the next one.
		// If there is no next one (meaning this is tailGuard) and signalNoMoreBuckets() has been
		// already called, then the underlying output stream will be closed.
		private void flush() {
			synchronized (lock) {
				for (MessageT bufferedMessage: buffer) output.write(bufferedMessage);
				buffer = null;
				if (next != null) {
					if (closed) next.flush();
				} else {  // this is tailGuard, so all "real" buckets are closed & flushed
					if (noMoreBuckets) output.close();
				}
			}
		}
	}



	static final String BUCKET_CLOSED_MESSAGE = "bucket already closed";
}
