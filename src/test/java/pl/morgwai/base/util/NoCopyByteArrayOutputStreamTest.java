// Copyright (c) Piotr Morgwai Kotarbinski, Licensed under the Apache License, Version 2.0
package pl.morgwai.base.util;

import java.io.IOException;

import org.junit.Test;

import static org.junit.Assert.*;



public class NoCopyByteArrayOutputStreamTest {



	static final int INITIAL_BUFFER_LEN = 32;

	NoCopyByteArrayOutputStream stream = new NoCopyByteArrayOutputStream(INITIAL_BUFFER_LEN);



	@Test
	public void testGetBufferThrowsIfStreamUnclosed() {
		try {
			stream.getBuffer();
			fail("IllegalStateException expected");
		} catch (IllegalStateException expected) {}
	}



	@Test
	public void testGetBufferDoesNotSeemToCopyTheBuffer() {
		stream.write(32);
		stream.close();
		assertEquals("returned buffer should retain its initial length",
				INITIAL_BUFFER_LEN, stream.getBuffer().length);
	}



	@Test
	public void testWriteByteThrowsIfStreamClosed() {
		stream.close();
		try {
			stream.write(32);
			fail("IllegalStateException expected");
		} catch (IllegalStateException expected) {}
	}



	@Test
	public void testWriteBufferThrowsIfStreamClosed() throws IOException {
		stream.close();
		try {
			stream.write(new byte[5]);
			fail("IllegalStateException expected");
		} catch (IllegalStateException expected) {}
	}



	@Test
	public void testWriteBufferWithOffsetThrowsIfStreamClosed() {
		stream.close();
		try {
			stream.write(new byte[5], 1, 1);
			fail("IllegalStateException expected");
		} catch (IllegalStateException expected) {}
	}



	@Test
	public void testResetThrowsIfStreamClosed() {
		stream.close();
		try {
			stream.reset();
			fail("IllegalStateException expected");
		} catch (IllegalStateException expected) {}
	}
}
