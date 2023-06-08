// Copyright (c) Piotr Morgwai Kotarbinski, Licensed under the Apache License, Version 2.0
package pl.morgwai.base.logging;

import java.io.*;
import java.util.Properties;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.LogManager;

import pl.morgwai.base.util.NoCopyByteArrayOutputStream;



/**
 * Utilities to manipulate {@code java.util.logging} config, among others allows to override log
 * levels with values from system properties at startup in existing java apps without rebuilding:
 * see {@link #overrideLogLevelsWithSystemProperties(String...)} and {@link #JulConfig()}.
 */
public class JulConfig {



	/**
	 * Overrides {@link Level}s of {@code java.util.logging} {@link java.util.logging.Logger}s and
	 * {@link java.util.logging.Handler}s with values obtained from system properties.
	 * Fully qualified names of {@link java.util.logging.Logger Logger}s and
	 * {@link java.util.logging.Handler Handler}s whose {@link Level}s will be overridden by this
	 * method can be provided as {@code loggerAndHandlerNames} arguments and/or comma separated on
	 * {@value #OVERRIDE_LEVEL_PROPERTY} system property.
	 * <p>
	 * Name of the system property containing a new {@link Level} for a given
	 * {@link java.util.logging.Logger}/{@link java.util.logging.Handler} is constructed by
	 * appending {@value #LEVEL_SUFFIX} to the given
	 * {@link java.util.logging.Logger}'s&nbsp;/&nbsp;{@link java.util.logging.Handler}'s
	 * fully-qualified-name.<br/>
	 * If a system property with a new {@link Level} is missing, it is ignored. If it is present,
	 * its validity is verified using {@link Level#parse(String)} method.</p>
	 * <p>
	 * <b>Example:</b><br/>
	 * Output all entries from <code>com.example</code> name-space with level <code>FINE</code> or
	 * higher to the console. Entries from other name-spaces will be logged only if they have at
	 * least level <code>WARNING</code> (unless configured otherwise in the default
	 * <code>logging.properties</code> file) :</p>
	 * <pre>
	 * java -cp ${CLASSPATH}:/path/to/pl/morgwai/base/java-utils.jar \
	 *      -Djava.util.logging.config.class=pl.morgwai.base.logging.JulConfig \
	 *      -Djava.util.logging.overrideLevel=,com.example,java.util.logging.ConsoleHandler \
	 *      -D.level=WARNING \
	 *      -Dcom.example.level=FINE \
	 *      -Djava.util.logging.ConsoleHandler.level=FINE \
	 *      com.example.someproject.MainClass</pre>
	 * <p>
	 * (Name of the root logger is an empty string, hence the value of
	 * {@value #OVERRIDE_LEVEL_PROPERTY} starts with a comma (so that the first element of the list
	 * is an empty string), while {@code -D.level} provides the new {@link Level} for the root
	 * {@link java.util.logging.Logger}).</p>
	 * <p>
	 * Note: overriding can be applied to existing java apps at startup without rebuilding: just add
	 * {@code java-utils.jar} to command-line class-path and define
	 * {@value #JUL_CONFIG_CLASS_PROPERTY} as in the example above.</p>
	 */
	public static void overrideLogLevelsWithSystemProperties(String... loggerAndHandlerNames) {
		final var newLogLevels = new Properties();  // loggerName.level -> newLevel

		// store into newLogLevels levels from system properties for loggers & handlers enlisted
		// on loggerAndHandlerNames param or on the system property
		int characterCount = 30;  // 30 is date comment character length
		if (loggerAndHandlerNames.length > 0) {
			characterCount += readNewLogLevels(newLogLevels, loggerAndHandlerNames);
		}
		final var loggerNamesFromProperty = System.getProperty(OVERRIDE_LEVEL_PROPERTY);
		if (loggerNamesFromProperty != null) {
			characterCount += readNewLogLevels(newLogLevels, loggerNamesFromProperty.split(","));
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
	 * Name of the system property that can contain comma separated, fully qualified names of
	 * {@link java.util.logging.Logger}s and {@link java.util.logging.Handler}s whose
	 * {@link java.util.logging.Level}s will be overridden by
	 * {@link #overrideLogLevelsWithSystemProperties(String...)}.
	 */
	public static final String OVERRIDE_LEVEL_PROPERTY = "java.util.logging.overrideLevel";

	/**
	 * Reads system properties containing overridden levels for {@code loggerNames} and puts them
	 * into {@code newLogLevels}.
	 * @return number of characters put into {@code newLogLevels}.
	 */
	private static int readNewLogLevels(Properties newLogLevels, String[] loggerNames) {
		int characterCount = 0;
		for (final var loggerName: loggerNames) {
			final var loggerLevelPropertyName = loggerName + LEVEL_SUFFIX;
			final var level = System.getProperty(loggerLevelPropertyName);
			if (level == null) continue;
			Level.parse(level);
			newLogLevels.put(loggerLevelPropertyName, level);
			characterCount += loggerLevelPropertyName.length();
			characterCount += level.length();
			characterCount += 2;  // '=' and '\n'
		}
		return characterCount;
	}

	/**
	 * {@value #LEVEL_SUFFIX}
	 */
	public static final String LEVEL_SUFFIX = ".level";

	/**
	 * Convenient version of {@link LogManager#updateConfiguration(InputStream, Function)} that
	 * takes a {@link Properties} argument instead of an {@link InputStream}.
	 * This is a bit low-level method: in most situations
	 * {@link #addOrReplaceLoggingConfigProperties(Properties)} will be more convenient.
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
		} catch (IOException ignored) {}  // this will never happen as byte array streams are used
	}

	private static final Function<String, BiFunction<String,String,String>> addOrReplaceMapper =
			(key) -> (oldVal, newVal) -> newVal != null ? newVal : oldVal;



	/**
	 * Reads logging config normally and then calls
	 * {@link #overrideLogLevelsWithSystemProperties(String...)}. For use with
	 * {@value #JUL_CONFIG_CLASS_PROPERTY} system property: when this property is set to the fully
	 * qualified name of this class, then {@link LogManager} will call this constructor instead of
	 * reading the configuration the normal way.
	 * <p>
	 * Note: overriding can be applied to existing java apps at startup without rebuilding: just add
	 * {@code java-utils.jar} to command-line class-path. See the example in
	 * {@link #overrideLogLevelsWithSystemProperties(String...)} documentation.</p>
	 * @see LogManager
	 */
	public JulConfig() throws IOException {
		System.clearProperty(JUL_CONFIG_CLASS_PROPERTY);
		LogManager.getLogManager().readConfiguration();
		overrideLogLevelsWithSystemProperties();
	}

	/**
	 * {@value #JUL_CONFIG_CLASS_PROPERTY}
	 */
	public static final String JUL_CONFIG_CLASS_PROPERTY = "java.util.logging.config.class";



	/**
	 * Adds to logging config properties those properties from {@code loggingConfigUpdates} that
	 * were not present before, replaces values of those already present with corresponding ones
	 * from {@code loggingConfigUpdates} if present there.
	 */
	public static void addOrReplaceLoggingConfigProperties(Properties loggingConfigUpdates) {
		logManagerUpdateConfiguration(
			LogManager.getLogManager(),
			loggingConfigUpdates,
			200,  // probably more efficient than calculating manually in most cases
			addOrReplaceMapper
		);
	}



	/**
	 * @deprecated use {@link #overrideLogLevelsWithSystemProperties(String...)} instead.
	 */
	@Deprecated(forRemoval = true)
	public static void overrideLogLevels(String... loggerAndHandlerNames) {
		overrideLogLevelsWithSystemProperties(loggerAndHandlerNames);
	}
}
