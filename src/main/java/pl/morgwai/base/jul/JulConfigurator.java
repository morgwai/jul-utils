// Copyright 2021 Piotr Morgwai Kotarbinski, Licensed under the Apache License, Version 2.0
package pl.morgwai.base.jul;

import java.io.*;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.LogManager;

import static java.util.stream.Collectors.toSet;



/**
 * Utilities to manipulate {@code java.util.logging} config, among others allows to override log
 * levels with system properties in existing java apps without rebuilding: see
 * {@link #overrideLogLevelsWithSystemProperties(String...)} and {@link #JulConfigurator()}.
 * <p>
 * Note: as {@link LogManager#reset()} does <b>not</b> remove {@link java.util.logging.Handler}s of
 * the root {@link java.util.logging.Logger}, manipulating properties related to
 * {@link java.util.logging.Handler}s (like {@code "java.util.logging.ConsoleHandler.level"}) will
 * <b>only</b> have effect if no log entry has been made yet. Otherwise root
 * {@link java.util.logging.Handler}s can only be modified programmatically via
 * {@link java.util.logging.Logger#getHandlers() rootLogger.getHandlers()}. See
 * {@link LogManager#updateConfiguration(InputStream, Function)} for details.</p>
 */
public class JulConfigurator {



	/**
	 * Overrides {@link Level}s of {@code java.util.logging} {@link java.util.logging.Logger}s and
	 * {@link java.util.logging.Handler}s with values obtained from system properties.
	 * Fully-qualified names of {@link java.util.logging.Logger Logger}s and
	 * {@link java.util.logging.Handler Handler}s whose {@link Level}s should be overridden, can be
	 * provided as {@code loggerAndHandlerNames} arguments and/or comma separated on
	 * {@value #OVERRIDE_LEVEL_PROPERTY} system property.
	 * <p>
	 * Name of the system property containing a new {@link Level} for a given
	 * {@link java.util.logging.Logger}/{@link java.util.logging.Handler} is constructed by
	 * appending {@value #LEVEL_SUFFIX} to the given
	 * {@link java.util.logging.Logger}'s&nbsp;/&nbsp;{@link java.util.logging.Handler}'s
	 * fully-qualified name (coherently with {@link LogManager}'s convention).<br/>
	 * If a system property with a new {@link Level} is missing, it is simply ignored.</p>
	 * <p>
	 * <b>Example:</b><br/>
	 * Output all entries from <code>com.example</code> name-space with level <code>FINE</code> or
	 * higher to the console. Entries from other name-spaces will be logged only if they have at
	 * least level <code>WARNING</code> (unless configured otherwise in the default
	 * <code>logging.properties</code> file) :</p>
	 * <pre>{@code
	 * java -cp ${CLASSPATH}:/path/to/jul-utils.jar \
	 *      -Djava.util.logging.config.class=pl.morgwai.base.jul.JulConfigurator \
	 *      -Djava.util.logging.overrideLevel=,com.example,java.util.logging.ConsoleHandler \
	 *      -D.level=WARNING \
	 *      -Dcom.example.level=FINE \
	 *      -Djava.util.logging.ConsoleHandler.level=FINE \
	 *      com.example.someproject.MainClass}</pre>
	 * <p>
	 * (Name of the root {@link java.util.logging.Logger} is an empty string, hence the value of
	 * {@value #OVERRIDE_LEVEL_PROPERTY} starts with a comma, so that the first element of the list
	 * is an empty string, while {@code -D.level} provides the new {@link Level} for the root
	 * {@link java.util.logging.Logger}).</p>
	 * <p>
	 * Note: overriding can be applied to existing java apps at startup without rebuilding: just add
	 * {@code jul-utils.jar} to the command-line class-path and define
	 * {@value #JUL_CONFIG_CLASS_PROPERTY} similarly as in the example above.</p>
	 */
	public static void overrideLogLevelsWithSystemProperties(String... loggerAndHandlerNames) {
		final var combinedNames = new HashSet<String>();  // both from the param & the sys property
		Collections.addAll(combinedNames, loggerAndHandlerNames);
		final var namesFromProperty = System.getProperty(OVERRIDE_LEVEL_PROPERTY);
		if (namesFromProperty != null) {
			Collections.addAll(combinedNames, namesFromProperty.split(","));
		}
		if (combinedNames.isEmpty()) return;
		overrideLogLevelsWithSystemProperties(combinedNames);
	}

	static void overrideLogLevelsWithSystemProperties(Set<String> loggerAndHandlerNames) {
		final var newLogLevels = new Properties();  // loggerName.level -> newLevel
		int characterCount = PROPERTIES_STORE_HEADER_LENGTH;
		for (final var loggerOrHandlerName: loggerAndHandlerNames) {
			// read a system property with the new level and put it into newLogLevels
			final var newLevelProperty = loggerOrHandlerName + LEVEL_SUFFIX;
			final var newLevel = System.getProperty(newLevelProperty);
			if (newLevel == null) {
				if (System.getProperty(loggerOrHandlerName) != null) {
					System.err.println("WARNING: expected property \"" + newLevelProperty + "\" not"
							+ " set, but \"" + loggerOrHandlerName + "\" present: have you "
							+  "forgotten to add \".level\" suffix maybe?"
					);
				}
				continue;
			}
			newLogLevels.put(newLevelProperty, newLevel);
			characterCount += newLevelProperty.length();
			characterCount += newLevel.length();
			characterCount += PROPERTY_OVERHEAD_LENGTH;
		}
		if (newLogLevels.isEmpty()) return;

		logManagerUpdateConfiguration(
			LogManager.getLogManager(),
			newLogLevels,
			characterCount * 2,  // *2 is to account for escaping Properties.store() does
			addOrReplaceMapper
		);
	}

	/** Combined length of {@code EOL} and {@code '='} characters. */
	static final int PROPERTY_OVERHEAD_LENGTH = System.lineSeparator().length() + 1;
	/** {@link Properties#store(OutputStream, String)} date comment header length. */
	static final int PROPERTIES_STORE_HEADER_LENGTH =
			new Date().toString().length() + System.lineSeparator().length() + 1;  // +1 is for '#'



	/**
	 * Reads logging config normally and then calls
	 * {@link #overrideLogLevelsWithSystemProperties(String...)}.
	 * For use with {@value #JUL_CONFIG_CLASS_PROPERTY} system property: when this property is set
	 * to the {@link Class#getName() fully-qualified name} of this class, then {@link LogManager}
	 * will call this constructor instead of reading its configuration the normal way.
	 * <p>
	 * If {@value #OVERRIDE_LEVEL_PROPERTY} system property is unset, this constructor will use all
	 * defined system properties whose names end with {@value #LEVEL_SUFFIX} to override log
	 * {@link Level}s.</p>
	 * <p>
	 * Note: overriding can be applied to existing java apps without rebuilding: just add
	 * {@code jul-utils.jar} to command-line class-path. See the example in
	 * {@link #overrideLogLevelsWithSystemProperties(String...)} documentation.</p>
	 */
	public JulConfigurator() throws IOException {
		final var julConfigClassPropertyBackup = System.getProperty(JUL_CONFIG_CLASS_PROPERTY);
		System.clearProperty(JUL_CONFIG_CLASS_PROPERTY);
		try {
			LogManager.getLogManager().readConfiguration();
			if (System.getProperty(OVERRIDE_LEVEL_PROPERTY) != null) {
				overrideLogLevelsWithSystemProperties();
				return;
			}

			final var loggerAndHandlerNames = System.getProperties()
				.stringPropertyNames()
				.stream()
				.filter((property) -> property.endsWith(LEVEL_SUFFIX))
				.map((property) -> property.substring(0, property.length() - LEVEL_SUFFIX.length()))
				.collect(toSet());
			if (loggerAndHandlerNames.isEmpty()) return;
			overrideLogLevelsWithSystemProperties(loggerAndHandlerNames);
		} finally {
			System.setProperty(JUL_CONFIG_CLASS_PROPERTY, julConfigClassPropertyBackup);
		}
	}



	/**
	 * Name of the system property that can contain comma separated,
	 * {@link Class#getName() fully-qualified names} of {@link java.util.logging.Logger}s and
	 * {@link java.util.logging.Handler}s whose {@link java.util.logging.Level}s will be overridden
	 * by {@link #overrideLogLevelsWithSystemProperties(String...)}.
	 */
	public static final String OVERRIDE_LEVEL_PROPERTY = "java.util.logging.overrideLevel";
	/** {@value #LEVEL_SUFFIX} (see {@link #overrideLogLevelsWithSystemProperties(String...)}). */
	public static final String LEVEL_SUFFIX = ".level";
	/**
	 * {@value #JUL_CONFIG_CLASS_PROPERTY} (see {@link #JulConfigurator()} and {@link LogManager}).
	 */
	public static final String JUL_CONFIG_CLASS_PROPERTY = "java.util.logging.config.class";
	/**
	 * For use with {@link #logManagerUpdateConfiguration(LogManager, Properties, int, Function)}
	 * and {@link LogManager#updateConfiguration(InputStream, Function)}.
	 */
	public static final Function<String, BiFunction<String,String,String>> addOrReplaceMapper =
			(key) -> (oldVal, newVal) -> newVal != null ? newVal : oldVal;



	/**
	 * Similar to {@link LogManager#updateConfiguration(InputStream, Function)}, but takes a
	 * {@link Properties} argument instead of an {@link InputStream}.
	 * This is somewhat a low-level method: in most situations
	 * {@link #addOrReplaceLoggingConfigProperties(Properties)} or
	 * {@link #addOrReplaceLoggingConfigProperties(Map)} will be more convenient.
	 * @param estimatedLoggingConfigUpdatesByteSize estimated size of loggingConfigUpdates in bytes.
	 *     It will be passed to {@link ByteArrayOutputStream#ByteArrayOutputStream(int)}.
	 */
	public static void logManagerUpdateConfiguration(
		LogManager logManager,
		Properties loggingConfigUpdates,
		int estimatedLoggingConfigUpdatesByteSize,
		Function<String, BiFunction<String,String,String>> mapper
	) {
		try {
			final var outputBytes =
					new NoCopyByteArrayOutputStream(estimatedLoggingConfigUpdatesByteSize);
			try (outputBytes) { loggingConfigUpdates.store(outputBytes, null); }
			try (
				final var inputBytes =
						new ByteArrayInputStream(outputBytes.getBuffer(), 0, outputBytes.size())
			) {
				logManager.updateConfiguration(inputBytes, mapper);
			}
		} catch (IOException neverHappens) {  // byte array streams don't throw
			throw new RuntimeException(neverHappens);
		}
	}

	static class NoCopyByteArrayOutputStream extends ByteArrayOutputStream {
		public byte[] getBuffer() { return buf; }
		public NoCopyByteArrayOutputStream(int initialSize) { super(initialSize); }
	}



	/**
	 * Adds to or replaces logging config properties with entries from {@code loggingConfigUpdates}.
	 * <p>
	 * Note: this method does not bother to calculate the byte size of the buffer needed to store
	 * {@code loggingConfigUpdates} and just estimates {@value #DEFAULT_PROPERTY_BYTE_SIZE} bytes
	 * per property. If more accurate allocation is needed, then use instead
	 * {@link #logManagerUpdateConfiguration(LogManager, Properties, int, Function)} with
	 * {@link #addOrReplaceMapper} directly.</p>
	 */
	public static void addOrReplaceLoggingConfigProperties(Properties loggingConfigUpdates) {
		logManagerUpdateConfiguration(
			LogManager.getLogManager(),
			loggingConfigUpdates,
			DEFAULT_PROPERTY_BYTE_SIZE * loggingConfigUpdates.size(),
			addOrReplaceMapper
		);
	}

	/**
	 * Used by {@link #addOrReplaceLoggingConfigProperties(Properties)} to estimate the buffer size.
	 */
	public static final int DEFAULT_PROPERTY_BYTE_SIZE = 80;



	/**
	 * Variant of {@link #addOrReplaceLoggingConfigProperties(Properties)} that takes a {@link Map}
	 * as an argument.
	 * This allows to use {@link Map#of(Object, Object) Map.of(...)} function family inline, for
	 * example:
	 * <pre>{@code
	 * addOrReplaceLoggingConfigProperties(Map.of(
	 *     ".level", "FINE",
	 *     "java.util.logging.ConsoleHandler.level", "FINEST",
	 *     "com.example.level", "FINEST",
	 *     "com.thirdparty.level", "WARNING"
	 * ));}</pre>
	 */
	public static void addOrReplaceLoggingConfigProperties(Map<String, String> loggingConfigUpdates)
	{
		final var propertiesUpdates = new Properties(loggingConfigUpdates.size());
		propertiesUpdates.putAll(loggingConfigUpdates);
		addOrReplaceLoggingConfigProperties(propertiesUpdates);
	}
}
