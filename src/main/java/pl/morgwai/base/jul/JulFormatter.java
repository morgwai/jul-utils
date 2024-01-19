// Copyright 2021 Piotr Morgwai Kotarbinski, Licensed under the Apache License, Version 2.0
package pl.morgwai.base.jul;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.logging.*;



/**
 * A text log formatter similar to {@link java.util.logging.SimpleFormatter} that additionally
 * allows to format stack-trace elements and to add
 * {@link LogRecord#getSequenceNumber() log sequence id} and
 * {@link LogRecord#getThreadID() thread id} to log entries.
 */
public class JulFormatter extends Formatter {



	/** {@value #FORMATTER_SUFFIX} (for constructing config property names) */
	public static final String FORMATTER_SUFFIX = ".formatter";

	/**
	 * Name of the logging or system property containing the main format for log records.
	 * @see #format(LogRecord)
	 */
	public static final String FORMAT_PROPERTY = JulFormatter.class.getName() + ".format";
	/**
	 * {@value #DEFAULT_FORMAT}<br/>
	 * "{sequenceId} {threadId} {level} {timestamp} {loggerName} {message} {thrown}"
	 */
	public static final String DEFAULT_FORMAT =
			"%7$5d %8$5d %4$7s %1$tF %1$tT.%1$tL %3$s %5$s %6$s%n";
	final String format;

	/**
	 * Name of the logging or system property containing the format for stack frames of logged
	 * {@link Throwable}s.
	 * @see #format(LogRecord)
	 */
	public static final String STACKFRAME_FORMAT_PROPERTY =
			JulFormatter.class.getName() + ".stackFrameFormat";
	final String stackFrameFormat;



	/**
	 * Creates a new formatter configured using supplied params.
	 * @param format the main format for log records.
	 *     If it's {@code null} then {@value #DEFAULT_FORMAT} is used.
	 * @param stackFrameFormat format for stack trace elements of logged {@link Throwable}s.
	 * @see #format(LogRecord)
	 */
	public JulFormatter (String format, String stackFrameFormat) {
		if (format != null) {
			this.format = format;
		} else {
			this.format = DEFAULT_FORMAT;
		}
		this.stackFrameFormat = stackFrameFormat;
	}



	/**
	 * Creates a new formatter configured using either system properties or logging properties.
	 * If both are present, system properties take precedence.
	 * <p>
	 * By default the value of {@link #FORMAT_PROPERTY} property is used as the main format
	 * for log records. If it is not present in either logging or system properties, then
	 * {@value #JUL_SIMPLE_FORMAT_PROPERTY} property is read and if present, its value is
	 * prepended with {@value #JUL_SIMPLE_FORMAT_PREFIX} and used as format. if it is also absent,
	 * then {@value #DEFAULT_FORMAT} is used.</p>
	 * <p>
	 * The value of {@link #STACKFRAME_FORMAT_PROPERTY} property is used as the format for
	 * stack trace elements. If it is not present in either logging or system properties, then
	 * {@code null} is passed.</p>
	 *
	 * @see #JulFormatter(String, String)
	 * @see #format(LogRecord)
	 */
	public JulFormatter() {
		this(getFormatFromProperties(), getStackFrameFormatFromProperties());
	}

	static String getFormatFromProperties() {
		var format = System.getProperty(FORMAT_PROPERTY);
		if (format == null) format = LogManager.getLogManager().getProperty(FORMAT_PROPERTY);
		if (format == null) {
			var simpleFormat = System.getProperty(JUL_SIMPLE_FORMAT_PROPERTY);
			if (simpleFormat == null) {
				simpleFormat = LogManager.getLogManager().getProperty(JUL_SIMPLE_FORMAT_PROPERTY);
			}
			if (simpleFormat != null) {
				format = JUL_SIMPLE_FORMAT_PREFIX + simpleFormat;
			}
		}
		return format;
	}

	static String getStackFrameFormatFromProperties() {
		final var stackFrameFormat = System.getProperty(STACKFRAME_FORMAT_PROPERTY);
		if (stackFrameFormat != null) return stackFrameFormat;
		return LogManager.getLogManager().getProperty(STACKFRAME_FORMAT_PROPERTY);
	}



	/** {@value #JUL_SIMPLE_FORMAT_PROPERTY} (see {@link #JulFormatter()}) */
	public static final String JUL_SIMPLE_FORMAT_PROPERTY =
			"java.util.logging.SimpleFormatter.format";
	/** {@value #JUL_SIMPLE_FORMAT_PREFIX} (see {@link #JulFormatter()}) */
	public static final String JUL_SIMPLE_FORMAT_PREFIX = "%7$5d %8$5d ";



	/**
	 * Formats {@code record}.
	 * <p>
	 * The result is obtained by running<br/>
	 * {@link String#format(String, Object...)
	 * String.format(format, timestamp, source, loggerName, level, message, formattedThrown, logId,
	 * threadId)}<br/>
	 * where:</p>
	 * <p>
	 * {@code format} is obtained from either {@link #FORMAT_PROPERTY} property or the
	 * first param of {@link #JulFormatter(String, String)}.</p>
	 * <p>
	 * {@code formattedThrown} is obtained
	 * by calling {@code record.getThrown().toString()} and appending<br/>
	 * {@link String#format(String, Object...) String.format(stackFrameFormat, logId, className,
	 * methodName, FileName, lineNumber, moduleName, moduleVersion, classLoaderName)}<br/>
	 * where {@code stackFrameFormat} is obtained from either
	 * {@link #STACKFRAME_FORMAT_PROPERTY} property or the second param of
	 * {@link #JulFormatter(String, String)}.<br/>
	 * If {@code stackFrameFormat} is {@code null} then
	 * {@link Throwable#printStackTrace(java.io.PrintStream)} is called instead of
	 * {@link String#format(String, Object...)}.</p>
	 */
	@Override
	public String format(LogRecord record) {
		final var timestamp = ZonedDateTime.ofInstant(record.getInstant(), ZoneId.systemDefault());

		String source;
		if (record.getSourceClassName() != null) {
			source = record.getSourceClassName();
			if (record.getSourceMethodName() != null) {
				source += '.' + record.getSourceMethodName();
			}
		} else {
			source = record.getLoggerName();
		}

		return String.format(
			format,
			timestamp,
			source,
			record.getLoggerName(),
			record.getLevel().getLocalizedName(),
			formatMessage(record),
			getFormattedThrown(record),
			record.getSequenceNumber(),
			record.getThreadID()
		);
	}

	String getFormattedThrown(LogRecord record) {
		final var thrown = record.getThrown();
		if (thrown == null) return "";

		if (stackFrameFormat == null) {
			try (
				final var stackTraceBuffer = new StringWriter();
				final var stackTracePrinter = new PrintWriter(stackTraceBuffer);
			) {
				stackTracePrinter.println();
				thrown.printStackTrace(stackTracePrinter);
				return stackTraceBuffer.toString();
			} catch (IOException neverHappens) {  // StringWriter.close() is no-op
				throw new RuntimeException(neverHappens);
			}
		}

		final var throwableStringBuilder = new StringBuilder(thrown.toString());
		for (var stackFrame: thrown.getStackTrace()) {
			throwableStringBuilder.append(String.format(
				stackFrameFormat,
				record.getSequenceNumber(),
				record.getThreadID(),
				stackFrame.getClassName(),
				stackFrame.getMethodName(),
				stackFrame.getFileName(),
				stackFrame.getLineNumber(),
				stackFrame.getModuleName(),
				stackFrame.getModuleVersion(),
				stackFrame.getClassLoaderName())
			);
		}
		return throwableStringBuilder.toString();
	}
}
