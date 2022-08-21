// Copyright (c) Piotr Morgwai Kotarbinski, Licensed under the Apache License, Version 2.0
package pl.morgwai.base.logging;

import java.io.*;
import java.time.Instant;
import java.util.Date;
import java.util.logging.*;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static pl.morgwai.base.logging.JulFormatter.*;



public class JulFormatterTest {



	@Test
	public void testFormatRecordWithoutThrownUsingDefaultFormat() throws IOException {
		System.clearProperty(JUL_SIMPLE_FORMAT_PROPERTY_NAME);
		LogManager.getLogManager().updateConfiguration(
			(key) -> (oldVal, newVal) -> key.equals(JUL_SIMPLE_FORMAT_PROPERTY_NAME) ? null : newVal
		);
		final JulFormatter formatter = new JulFormatter();

		final var threadId = (int) Thread.currentThread().getId();
		final var recordId = 69L;
		final var timestampMillis = System.currentTimeMillis();
		final var message = "test message";
		final var record = new LogRecord(Level.INFO, message);
		record.setSourceClassName(JulFormatter.class.getName());
		record.setLoggerName(JulFormatter.class.getName());
		record.setSequenceNumber(recordId);
		record.setThreadID(threadId);
		record.setInstant(Instant.ofEpochMilli(timestampMillis));

		assertEquals("values formatted manually should be equal to formatter.format(record)",
				String.format(
						DEFAULT_FORMAT,
						new Date(timestampMillis),
						JulFormatter.class.getName(),
						JulFormatter.class.getName(),
						Level.INFO.getLocalizedName(),
						formatter.formatMessage(record),
						"",
						recordId,
						threadId),
				formatter.format(record));
	}



	@Test
	public void testFormatThrownWithoutStackTraceFormatProvided() throws IOException {
		final var simpleFormatterFormat = "some string";
		System.setProperty(JUL_SIMPLE_FORMAT_PROPERTY_NAME, simpleFormatterFormat);
		LogManager.getLogManager().updateConfiguration(
			(key) -> (oldVal, newVal) -> key.equals(JUL_SIMPLE_FORMAT_PROPERTY_NAME) ? "ha" : newVal
		);
		final JulFormatter formatter = new JulFormatter();
		assertEquals(JUL_SIMPLE_FORMAT_PROPERTY_NAME + " property should be used",
				"%7$5d %8$3d " + simpleFormatterFormat,
				formatter.format);

		final var record = new LogRecord(Level.INFO, "");
		final var thrown = new Exception("test exception");
		record.setThrown(thrown);

		try (
			var sw = new StringWriter();
			var pw = new PrintWriter(sw);
		) {
			pw.println();
			thrown.printStackTrace(pw);
			assertEquals("thrown should be formatted with printStackTrace()",
					sw.toString(), formatter.getFormattedThrown(record));
		} catch (IOException ignored) {}  // StringWriter.close() is no-op
	}



	@Test
	public void testFormatThrownUsingCustomStackTraceFormat() {
		final var stackFrameFormat = "%1$s;%2$s;%3$s;%4$s;%5$s;%6$s;%7$s;%8$s;%9$s";
		final JulFormatter formatter = new JulFormatter(DEFAULT_FORMAT, stackFrameFormat);

		final var threadId = (int) Thread.currentThread().getId();
		final var recordId = 69L;
		final var thrown = new Exception("test exception");
		final var record = new LogRecord(Level.INFO, "");
		record.setSequenceNumber(recordId);
		record.setThreadID(threadId);
		record.setThrown(thrown);

		final var throwableStringBuilder = new StringBuilder(thrown.toString());
		for (var stackFrame: thrown.getStackTrace()) {
			throwableStringBuilder.append(String.format(
					stackFrameFormat,
					recordId,
					threadId,
					stackFrame.getClassName(),
					stackFrame.getMethodName(),
					stackFrame.getFileName(),
					stackFrame.getLineNumber(),
					stackFrame.getModuleName(),
					stackFrame.getModuleVersion(),
					stackFrame.getClassLoaderName()));
		}

		assertEquals("thrown formatted manually should be equal to getFormattedThrown(record)",
				throwableStringBuilder.toString(), formatter.getFormattedThrown(record));
	}
}
