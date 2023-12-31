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
	 * fully-qualified name.<br/>
	 * If a system property with a new {@link Level} is missing, it is ignored. If it is present,
	 * its validity is verified using {@link Level#parse(String)} method.</p>
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
		final var loggerAndHandlerNamesSet = new HashSet<String>();
		Collections.addAll(loggerAndHandlerNamesSet, loggerAndHandlerNames);
		final var namesFromProperty = System.getProperty(OVERRIDE_LEVEL_PROPERTY);
		if (namesFromProperty != null) {
			Collections.addAll(loggerAndHandlerNamesSet, namesFromProperty.split(","));
		}
		if (loggerAndHandlerNamesSet.isEmpty()) return;
		overrideLogLevelsWithSystemProperties(loggerAndHandlerNamesSet);
	}

	static void overrideLogLevelsWithSystemProperties(Set<String> loggerAndHandlerNames) {
		final var newLogLevels = new Properties();  // loggerName.level -> newLevel
		int characterCount = 30;  // first line date comment character length
		for (final var loggerOrHandlerName: loggerAndHandlerNames) {
			// read a system property with the new level and put it into newLogLevels
			final var newLevelPropertyName = loggerOrHandlerName + LEVEL_SUFFIX;
			final var newLevel = System.getProperty(newLevelPropertyName);
			if (newLevel == null) continue;
			Level.parse(newLevel);
			newLogLevels.put(newLevelPropertyName, newLevel);
			characterCount += newLevelPropertyName.length();
			characterCount += newLevel.length();
			characterCount += 2;  // '=' and '\n'
		}
		if (newLogLevels.isEmpty()) return;

		logManagerUpdateConfiguration(
			LogManager.getLogManager(),
			newLogLevels,
			characterCount * 2,  // *2 is for UTF characters
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
		LogManager.getLogManager().readConfiguration();
		overrideLogLevelsWithSystemProperties();
		System.setProperty(JUL_CONFIG_CLASS_PROPERTY, julConfigClassPropertyBackup);
	}



	/**
	 * Name of the system property that can contain comma separated,
	 * {@link Class#getName() fully-qualified names} of {@link java.util.logging.Logger}s and
	 * {@link java.util.logging.Handler}s whose {@link java.util.logging.Level}s will be overridden
	 * by {@link #overrideLogLevelsWithSystemProperties(String...)}.
	 */
	public static final String OVERRIDE_LEVEL_PROPERTY = "java.util.logging.overrideLevel";
	/** {@value #LEVEL_SUFFIX} */
	public static final String LEVEL_SUFFIX = ".level";
	/** {@value #JUL_CONFIG_CLASS_PROPERTY} */
	public static final String JUL_CONFIG_CLASS_PROPERTY = "java.util.logging.config.class";
	static final Function<String, BiFunction<String,String,String>> addOrReplaceMapper =
			(key) -> (oldVal, newVal) -> newVal != null ? newVal : oldVal;



	/**
	 * Convenient version of {@link LogManager#updateConfiguration(InputStream, Function)} that
	 * takes a {@link Properties} argument instead of an {@link InputStream}.
	 * This is somewhat a low-level method: in most situations
	 * {@link #addOrReplaceLoggingConfigProperties(Properties)} or
	 * {@link #addOrReplaceLoggingConfigProperties(Map)} will be more convenient.
	 * @param estimatedLoggingConfigUpdatesByteSize estimated size of loggingConfigUpdates in bytes.
	 *     It will be passed as an argument to
	 *     {@link ByteArrayOutputStream#ByteArrayOutputStream(int)}.
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
	 * Adds to or replaces logging config properties with values from {@code loggingConfigUpdates}.
	 */
	public static void addOrReplaceLoggingConfigProperties(Properties loggingConfigUpdates) {
		logManagerUpdateConfiguration(
			LogManager.getLogManager(),
			loggingConfigUpdates,
			80 * loggingConfigUpdates.size(),  // in most cases more efficient than calculating
			addOrReplaceMapper
		);
	}



	/**
	 * Convenient version of {@link #addOrReplaceLoggingConfigProperties(Properties)} that takes a
	 * {@link Map} as an argument.
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
		final var loggingConfigUpdateProperties = new Properties(loggingConfigUpdates.size());
		loggingConfigUpdateProperties.putAll(loggingConfigUpdates);
		addOrReplaceLoggingConfigProperties(loggingConfigUpdateProperties);
	}
}
