// Copyright (c) Piotr Morgwai Kotarbinski, Licensed under the Apache License, Version 2.0
package pl.morgwai.base.logging;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.logging.Formatter;
import java.util.logging.LogManager;
import java.util.logging.LogRecord;



/**
 * A text log formatter similar to {@link java.util.logging.SimpleFormatter} that additionally
 * allows to format stack trace elements and to add log sequence id and thread id to log entries.
 */
public class JulFormatter extends Formatter {



	/**
	 * Name of the logging or system property containing the main format for each record.
	 * @see #format(LogRecord)
	 */
	public static final String FORMAT_PROPERTY_NAME = JulFormatter.class.getName() + ".format";
	public static final String DEFAULT_FORMAT =
			"%7$5d %8$3d %4$7s %1$tF %1$tT.%1$tL %3$s %5$s %6$s%n";
	final String format;

	/**
	 * Name of the logging or system property containing a format for stack frames of logged
	 * throwables.
	 * @see #format(LogRecord)
	 */
	public static final String STACKFRAME_FORMAT_PROPERTY_NAME =
			JulFormatter.class.getName() + ".stackFrameFormat";
	final String stackFrameFormat;



	/**
	 * Creates a new formatter configured using supplied params.
	 * If {@code format} is {@code null} then {@link #DEFAULT_FORMAT} is used.
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
	 * If {@link #FORMAT_PROPERTY_NAME} is not present in either logging or system properties, then
	 * {@link #JUL_SIMPLE_FORMAT_PROPERTY_NAME} property is read and if present, its value is
	 * prepended with {@code "%7$5d %8$3d "} and used instead. if it is also absent, then
	 * {@link #DEFAULT_FORMAT} is used.
	 *
	 * @see #format(LogRecord)
	 */
	public JulFormatter() {
		this(getFormatFromProperties(), getStackFrameFormatFromProperties());
	}

	static String getFormatFromProperties() {
		var format = System.getProperty(FORMAT_PROPERTY_NAME);
		if (format == null) format = LogManager.getLogManager().getProperty(FORMAT_PROPERTY_NAME);
		if (format == null) {
			var simpleFormat = System.getProperty(JUL_SIMPLE_FORMAT_PROPERTY_NAME);
			if (simpleFormat == null) {
				simpleFormat = LogManager.getLogManager().getProperty(
						JUL_SIMPLE_FORMAT_PROPERTY_NAME);
			}
			if (simpleFormat != null) {
				format = "%7$5d %8$3d " + simpleFormat;
			}
		}
		return format;
	}

	public static final String JUL_SIMPLE_FORMAT_PROPERTY_NAME =
			"java.util.logging.SimpleFormatter.format";

	static String getStackFrameFormatFromProperties() {
		var stackFrameFormat = System.getProperty(STACKFRAME_FORMAT_PROPERTY_NAME);
		if (stackFrameFormat != null) return stackFrameFormat;
		return LogManager.getLogManager().getProperty(STACKFRAME_FORMAT_PROPERTY_NAME);
	}



	/**
	 * Formats the given {@code record}.
	 * <p>
	 * The result is obtained by running<br/>
	 * {@link String#format(String, Object...)
	 * String.format(format, timestamp, source, loggerName, level, message, formattedThrown, logId,
	 * threadId)}<br/>
	 * where {@code format} is obtained from either {@link #FORMAT_PROPERTY_NAME} property or the
	 * first param of {@link #JulFormatter(String, String)}.</p>
	 * <p>
	 * {@code formattedThrown} is obtained
	 * by calling {@code record.getThrown().toString()} and appending<br/>
	 * {@link String#format(String, Object...) String.format(stackFrameFormat, logId, className,
	 * methodName, FileName, lineNumber, moduleName, moduleVersion, classLoaderName)}<br/>
	 * where {@code stackFrameFormat} is obtained from either
	 * {@link #STACKFRAME_FORMAT_PROPERTY_NAME} property or the second param of
	 * {@link #JulFormatter(String, String)}.<br/>
	 * If {@code stackFrameFormat} is {@code null} then
	 * {@link Throwable#printStackTrace(java.io.PrintStream)} is used instead.</p>
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

		String formattedThrown = "";
		final Throwable thrown = record.getThrown();
		if (thrown != null) {
			if (stackFrameFormat == null) {
				StringWriter sw = new StringWriter();
				PrintWriter pw = new PrintWriter(sw);
				pw.println();
				thrown.printStackTrace(pw);
				pw.close();
				formattedThrown = sw.toString();
			} else {
				StringBuilder throwableStringBuilder = new StringBuilder(thrown.toString());
				for (var stackFrame: thrown.getStackTrace()) {
					throwableStringBuilder.append(String.format(stackFrameFormat,
							record.getSequenceNumber(),
							record.getThreadID(),
							stackFrame.getClassName(),
							stackFrame.getMethodName(),
							stackFrame.getFileName(),
							stackFrame.getLineNumber(),
							stackFrame.getModuleName(),
							stackFrame.getModuleVersion(),
							stackFrame.getClassLoaderName()));
				}
				formattedThrown = throwableStringBuilder.toString();
			}
		}

		return String.format(format,
				timestamp,
				source,
				record.getLoggerName(),
				record.getLevel().getLocalizedName(),
				formatMessage(record),
				formattedThrown,
				record.getSequenceNumber(),
				record.getThreadID());
	}
}
