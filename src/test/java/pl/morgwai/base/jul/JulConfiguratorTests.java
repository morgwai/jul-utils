// Copyright 2021 Piotr Morgwai Kotarbinski, Licensed under the Apache License, Version 2.0
package pl.morgwai.base.jul;

import java.io.IOException;
import java.util.*;
import java.util.logging.*;
import org.junit.*;

import static java.util.logging.Level.*;
import static org.junit.Assert.*;

import static pl.morgwai.base.jul.JulConfigurator.*;



public class JulConfiguratorTests {



	static final String EXAMPLE_DOMAIN = "com.example";



	public void testOverrideViaConstructor(boolean defineOverrideProperty) throws IOException {
		if (defineOverrideProperty) {
			System.setProperty(
				OVERRIDE_LEVEL_PROPERTY,
				"," + ConsoleHandler.class.getName()
			);
		}
		System.setProperty(ConsoleHandler.class.getName() + LEVEL_SUFFIX, SEVERE.toString());
		System.setProperty(EXAMPLE_DOMAIN + LEVEL_SUFFIX, FINE.toString());
		System.setProperty(LEVEL_SUFFIX, SEVERE.toString());
		System.setProperty(JUL_CONFIG_CLASS_PROPERTY, JulConfigurator.class.getName());

		LogManager.getLogManager().readConfiguration();
		assertEquals(
			"ConsoleHandler should have its Level as in the property",
			System.getProperty(ConsoleHandler.class.getName() + LEVEL_SUFFIX),
			new ConsoleHandler().getLevel().toString()
		);
		if (defineOverrideProperty) {
			assertNull(
				'"' + EXAMPLE_DOMAIN + "\" Logger should have the default Level",
				Logger.getLogger(EXAMPLE_DOMAIN).getLevel()
			);
		} else {
			assertEquals(
				'"' + EXAMPLE_DOMAIN + "\" Logger should have its Level as in the property",
				System.getProperty(EXAMPLE_DOMAIN + LEVEL_SUFFIX),
				Logger.getLogger(EXAMPLE_DOMAIN).getLevel().toString()
			);
		}
		assertEquals(
			"root Logger should have its Level as in the property",
			System.getProperty(LEVEL_SUFFIX),
			Logger.getLogger("").getLevel().toString()
		);
		assertEquals(
			'"' + JUL_CONFIG_CLASS_PROPERTY + "\" property should be preserved",
			JulConfigurator.class.getName(),
			System.getProperty(JUL_CONFIG_CLASS_PROPERTY)
		);
	}

	@Test
	public void testOverrideViaConstructorWithDefinedProperty() throws IOException {
		testOverrideViaConstructor(true);
	}

	@Test
	public void testOverrideViaConstructorWithUndefinedProperty() throws IOException {
		testOverrideViaConstructor(false);
	}



	@Test
	public void testNamesFromParams() {
		final var MISSING_LEVEL_LOGGER = "missing.level.logger";
		System.setProperty(ConsoleHandler.class.getName() + LEVEL_SUFFIX, SEVERE.toString());
		System.setProperty(EXAMPLE_DOMAIN + LEVEL_SUFFIX, SEVERE.toString());
		System.setProperty(LEVEL_SUFFIX, SEVERE.toString());
		System.setProperty(MISSING_LEVEL_LOGGER, WARNING.toString());

		overrideLogLevelsWithSystemProperties(
			ConsoleHandler.class.getName(),
			EXAMPLE_DOMAIN,
			MISSING_LEVEL_LOGGER,
			""
		);
		assertEquals(
			"ConsoleHandler should have its Level as in the property",
			System.getProperty(ConsoleHandler.class.getName() + LEVEL_SUFFIX),
			new ConsoleHandler().getLevel().toString()
		);
		assertEquals(
			'"' + EXAMPLE_DOMAIN + "\" Logger should have its Level as in the property",
			System.getProperty(EXAMPLE_DOMAIN + LEVEL_SUFFIX),
			Logger.getLogger(EXAMPLE_DOMAIN).getLevel().toString()
		);
		assertEquals(
			"root Logger should have its Level as in the property",
			System.getProperty(LEVEL_SUFFIX),
			Logger.getLogger("").getLevel().toString()
		);
	}



	@Test
	public void testNamesFromBothPropertyAndParams() {
		System.setProperty(OVERRIDE_LEVEL_PROPERTY, ConsoleHandler.class.getName());
		System.setProperty(ConsoleHandler.class.getName() + LEVEL_SUFFIX, SEVERE.toString());
		System.setProperty(EXAMPLE_DOMAIN + LEVEL_SUFFIX, SEVERE.toString());

		overrideLogLevelsWithSystemProperties(EXAMPLE_DOMAIN);
		assertEquals(
			"ConsoleHandler should have its Level as in the property",
			System.getProperty(ConsoleHandler.class.getName() + LEVEL_SUFFIX),
			new ConsoleHandler().getLevel().toString()
		);
		assertEquals(
			'"' + EXAMPLE_DOMAIN + "\" Logger should have its Level as in the property",
			System.getProperty(EXAMPLE_DOMAIN + LEVEL_SUFFIX),
			Logger.getLogger(EXAMPLE_DOMAIN).getLevel().toString()
		);
	}



	@Test
	public void testLogManagerUpdateConfigurationCallsLogMapperEvenWithEmptyUpdates() {
		boolean[] mapperCalledHolder = {false};

		logManagerUpdateConfiguration(
			LogManager.getLogManager(),
			new Properties(),
			40,
			(key) -> (oldVal, newVal) -> {
				mapperCalledHolder[0] = true;
				assertNull("there should be no config updates", newVal);
				return oldVal;
			}
		);
		assertTrue("mapper should be called at least once", mapperCalledHolder[0]);
	}



	@Test
	public void testAddOrReplaceLoggingConfigProperties() {
		final var loggingConfigUpdates = Map.of(
			ConsoleHandler.class.getName() + LEVEL_SUFFIX, SEVERE.toString(),
			EXAMPLE_DOMAIN + LEVEL_SUFFIX, SEVERE.toString(),
			LEVEL_SUFFIX, SEVERE.toString()
		);

		addOrReplaceLoggingConfigProperties(loggingConfigUpdates);
		assertEquals(
			"ConsoleHandler should have its Level as in the property",
			loggingConfigUpdates.get(ConsoleHandler.class.getName() + LEVEL_SUFFIX),
			new ConsoleHandler().getLevel().toString()
		);
		assertEquals(
			EXAMPLE_DOMAIN + " logger should have level as in the property",
			loggingConfigUpdates.get(EXAMPLE_DOMAIN + LEVEL_SUFFIX),
			Logger.getLogger(EXAMPLE_DOMAIN).getLevel().toString()
		);
		assertEquals(
			"root Logger should have its Level as in the property",
			loggingConfigUpdates.get(LEVEL_SUFFIX),
			Logger.getLogger("").getLevel().toString()
		);
	}



	@Test
	public void testThrowingOverridingPreservesJulConfigClassProperty() {
		System.setProperty(JUL_CONFIG_CLASS_PROPERTY, JulConfigurator.class.getName());
		System.setProperty(
			JUL_CONFIG_FILE_PROPERTY,
			JulConfigurator.class.getName() + ".nonexistent"
		);
		try {
			LogManager.getLogManager().readConfiguration();
			fail("LogManager should throw when pointed to a nonexistent file");
		} catch (IOException expected) {}
		assertEquals(
			'"' + JUL_CONFIG_CLASS_PROPERTY + "\" property should be preserved",
			JulConfigurator.class.getName(),
			System.getProperty(JUL_CONFIG_CLASS_PROPERTY)
		);
	}

	static final String JUL_CONFIG_FILE_PROPERTY = "java.util.logging.config.file";



	Map<String, String> systemPropertiesBackup;



	@Before
	public void backupSystemPropertiesAndPrepareJulConfig() throws IOException {
		systemPropertiesBackup = new HashMap<>();
		backupSystemProperty(JUL_CONFIG_FILE_PROPERTY);
		backupSystemProperty(JUL_CONFIG_CLASS_PROPERTY);
		backupSystemProperty(OVERRIDE_LEVEL_PROPERTY);
		backupSystemProperty(ConsoleHandler.class.getName() + LEVEL_SUFFIX);
		backupSystemProperty(EXAMPLE_DOMAIN + LEVEL_SUFFIX);
		backupSystemProperty(LEVEL_SUFFIX);
		LogManager.getLogManager().updateConfiguration(
			(key) -> (oldVal, newVal) ->
					key.equals(LEVEL_SUFFIX) ? INFO.toString() : newVal
		);
	}

	void backupSystemProperty(String name) {
		systemPropertiesBackup.put(name, System.getProperty(name));
	}



	@After
	public void restoreSystemPropertiesAndJulConfig() throws IOException {
		for(var key: systemPropertiesBackup.keySet()) {
			String value = systemPropertiesBackup.get(key);
			if (value != null) {
				System.setProperty(key, systemPropertiesBackup.get(key));
			} else {
				System.clearProperty(key);
			}
		}
		LogManager.getLogManager().readConfiguration();
	}
}
