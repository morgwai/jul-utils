// Copyright (c) Piotr Morgwai Kotarbinski, Licensed under the Apache License, Version 2.0
package pl.morgwai.base.jul;

import java.io.*;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.LogManager;



/**
 * Utilities to manipulate {@code java.util.logging} config, among others allows to override log
 * levels with system properties in existing java apps without rebuilding: see
 * {@link #overrideLogLevelsWithSystemProperties(String...)} and {@link #JulConfigurator()}.
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
	 * {@value #JUL_CONFIG_CLASS_PROPERTY} as in the example above.</p>
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
		int characterCount = 30;  // first line date comment character length
		for (final var loggerOrHandlerName: loggerAndHandlerNames) {
			// read a system property with the new level and put it into newLogLevels
			final var newLevelProperty = loggerOrHandlerName + LEVEL_SUFFIX;
			final var newLevel = System.getProperty(newLevelProperty);
			if (newLevel == null) continue;
			newLogLevels.put(newLevelProperty, newLevel);
			characterCount += newLevelProperty.length();
			characterCount += newLevel.length();
			characterCount += 2;  // '=' and '\n'
		}
		if (newLogLevels.isEmpty()) return;

		logManagerUpdateConfiguration(
			LogManager.getLogManager(),
			newLogLevels,
			characterCount * 2,  // *2 is to account for escaping Properties.store() does
			addOrReplaceMapper
		);
	}



	/**
	 * Reads logging config normally and then calls
	 * {@link #overrideLogLevelsWithSystemProperties(String...)}.
	 * For use with {@value #JUL_CONFIG_CLASS_PROPERTY} system property: when this property is set
	 * to the {@link Class#getName() fully-qualified name} of this class, then {@link LogManager}
	 * will call this constructor instead of reading its configuration the normal way.
	 * <p>
	 * Note: overriding can be applied to existing java apps without rebuilding: just add
	 * {@code jul-utils.jar} to command-line class-path. See the example in
	 * {@link #overrideLogLevelsWithSystemProperties(String...)} documentation.</p>
	 * @see LogManager
	 */
	public JulConfigurator() throws IOException {
		final var julConfigClassPropertyBackup = System.getProperty(JUL_CONFIG_CLASS_PROPERTY);
		System.clearProperty(JUL_CONFIG_CLASS_PROPERTY);
		try {
			LogManager.getLogManager().readConfiguration();
			overrideLogLevelsWithSystemProperties();
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
	/** {@value #LEVEL_SUFFIX} (see {@link #overrideLogLevelsWithSystemProperties(String...)}) */
	public static final String LEVEL_SUFFIX = ".level";
	/** {@value #JUL_CONFIG_CLASS_PROPERTY} (see {@link #JulConfigurator()}) */
	public static final String JUL_CONFIG_CLASS_PROPERTY = "java.util.logging.config.class";
	static final Function<String, BiFunction<String,String,String>> addOrReplaceMapper =
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
	 * per property. If more accurate allocation is needed, use
	 * {@link #logManagerUpdateConfiguration(LogManager, Properties, int, Function)} directly
	 * instead.</p>
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
