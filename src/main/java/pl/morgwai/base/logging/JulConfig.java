// Copyright (c) Piotr Morgwai Kotarbinski, Licensed under the Apache License, Version 2.0
package pl.morgwai.base.logging;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.LogManager;



public class JulConfig {



	/**
	 * Updates {@link Level}s of <code>java.util.logging</code> Loggers and Handlers with values
	 * obtained from system properties.
	 * Fully qualified names of Loggers and Handlers whose {@link Level}s should be updated by this
	 * method are provided as arguments to this method or comma-separated on
	 * {@link #OVERRIDE_LEVEL_PROPERTY_NAME} system property.
	 * Name of the system property containing the new {@link Level} for a given Logger/Handler is
	 * constructed by appending {@link #LEVEL_SUFFIX} to its fully-qualified-name.
	 * If a system property with a new {@link Level} is missing, it is ignored. If it is present,
	 * the validity of the value is verified using {@link Level#parse(String)} method.
	 */
	public static void updateLogLevels(String... names) {
		final var props = new Properties();
		int estimatedByteSize = 0;

		if (names.length > 0) estimatedByteSize += readLogLevels(props, names);

		final var loggersProperty = System.getProperty(OVERRIDE_LEVEL_PROPERTY_NAME);
		if (loggersProperty != null) {
			estimatedByteSize += readLogLevels(props, loggersProperty.split(","));
		}

		if (props.size() == 0) return;
		var out = new ByteArrayOutputStream(estimatedByteSize * 2);
		try {
			props.store(out, "");
			LogManager.getLogManager().updateConfiguration(
					new ByteArrayInputStream(out.toByteArray()),
					(key) -> (oldVal, newVal) -> newVal != null ? newVal : oldVal);
		} catch (IOException e) {  // this is probably impossible to happen...
			throw new RuntimeException(e);
		}
	}



	private static int readLogLevels(Properties props, String[] loggerNames) {
		int estimatedByteSize = 0;
		for (var loggerName: loggerNames) {
			final var loggerLevelPropertyName = loggerName + ".level";
			final var level = System.getProperty(loggerLevelPropertyName);
			if (level == null) continue;
			Level.parse(level);
			props.put(loggerLevelPropertyName, level);
			estimatedByteSize += loggerLevelPropertyName.length();
			estimatedByteSize += level.length();
		}
		return estimatedByteSize;
	}



	public static final String LEVEL_SUFFIX = ".level";
	public static final String OVERRIDE_LEVEL_PROPERTY_NAME = "java.util.logging.overrideLevel";
}
