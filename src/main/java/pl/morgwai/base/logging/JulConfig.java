// Copyright (c) Piotr Morgwai Kotarbinski, Licensed under the Apache License, Version 2.0
package pl.morgwai.base.logging;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.LogManager;



/**
 * Utility to override {@code java.util.logging} properties with values from system properties.
 * <p>
 * Note: overriding can be applied to an existing java app at run time: just add java-utils jar to
 * the class-path and define system properties as described in {@link #JulConfig()} and
 * {@link #overrideLogLevels(String...)}.</p>
 */
public class JulConfig {



	/**
	 * Overrides {@link Level}s of <code>java.util.logging</code>
	 * {@link java.util.logging.Logger Logger}s and {@link java.util.logging.Handler Handler}s with
	 * values obtained from system properties.
	 * <p>
	 * Fully qualified names of <code>Logger</code>s and <code>Handler</code>s whose {@link Level}s
	 * should be overridden by this method are provided as arguments to this method and/or comma
	 * separated on {@value #OVERRIDE_LEVEL_PROPERTY_NAME} system property.<br/>
	 * Name of the system property containing the new {@link Level} for a given
	 * <code>Logger/Handler</code> is constructed by appending {@value #LEVEL_SUFFIX} to its
	 * fully-qualified-name.
	 * If a system property with a new {@link Level} is missing, it is ignored. If it is present,
	 * its validity is verified using {@link Level#parse(String)} method.</p>
	 * <p>
	 * <b>Example:</b><br/>
	 * Output all entries from <code>com.example</code> name-space with level <code>FINE</code> or
	 * higher to the console. Entries from other name-spaces will be logged only if they have at
	 * least level <code>WARNING</code> (unless configured otherwise in the default
	 * <code>logging.properties</code> file) :</p>
	 * <pre>
	 *java -Djava.util.logging.config.class=pl.morgwai.base.logging.JulConfig \
	 *     -Djava.util.logging.overrideLevel=,com.example,java.util.logging.ConsoleHandler \
	 *     -D.level=WARNING \
	 *     -Dcom.example.level=FINE \
	 *     -Djava.util.logging.ConsoleHandler.level=FINE \
	 *     com.example.someproject.MainClass</pre>
	 */
	public static void overrideLogLevels(String... names) {
		final var props = new Properties();
		int characterCount = 30;  // 30 is date comment character length

		if (names.length > 0) characterCount += readLogLevels(props, names);

		final var loggersProperty = System.getProperty(OVERRIDE_LEVEL_PROPERTY_NAME);
		if (loggersProperty != null) {
			characterCount += readLogLevels(props, loggersProperty.split(","));
		}

		if (props.size() == 0) return;
		var outputBytes = new ByteArrayOutputStream(characterCount * 2);// 2 is in case of utf chars
		try {
			props.store(outputBytes, null);
			var inputBytes = new ByteArrayInputStream(outputBytes.toByteArray());
			outputBytes.close();
			LogManager.getLogManager().updateConfiguration(
					inputBytes,
					(key) -> (oldVal, newVal) -> newVal != null ? newVal : oldVal);
			inputBytes.close();
		} catch (IOException e) {  // this is probably impossible to happen...
			throw new RuntimeException(e);
		}
	}

	/**
	 * Name of the system property that can contain comma separated, fully qualified names of
	 * {@link java.util.logging.Logger}s and {@link java.util.logging.Handler}s whose
	 * {@link java.util.logging.Level}s will be overridden by {@link #overrideLogLevels(String...)}.
	 */
	public static final String OVERRIDE_LEVEL_PROPERTY_NAME = "java.util.logging.overrideLevel";

	/**
	 * Reads system properties containing overridden levels for {@code loggerNames} and puts them
	 * into {@code props}.
	 * @return number of characters put into {@code props}.
	 */
	private static int readLogLevels(Properties props, String[] loggerNames) {
		int characterCount = 0;
		for (var loggerName: loggerNames) {
			final var loggerLevelPropertyName = loggerName + LEVEL_SUFFIX;
			final var level = System.getProperty(loggerLevelPropertyName);
			if (level == null) continue;
			Level.parse(level);
			props.put(loggerLevelPropertyName, level);
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
	 * Reads logging config normally and then calls {@link #overrideLogLevels(String...)}.
	 * For use with {@value #JUL_CONFIG_CLASS_PROPERTY_NAME} system property: when this property is
	 * set to the fully qualified name of this class, then {@link LogManager} will call this
	 * constructor instead of reading the configuration the normal way.
	 *
	 * @see LogManager
	 */
	public JulConfig() throws Exception {
		System.clearProperty(JUL_CONFIG_CLASS_PROPERTY_NAME);
		LogManager.getLogManager().readConfiguration();
		overrideLogLevels();
	}

	/**
	 * {@value #JUL_CONFIG_CLASS_PROPERTY_NAME}
	 */
	public static final String JUL_CONFIG_CLASS_PROPERTY_NAME = "java.util.logging.config.class";
}
