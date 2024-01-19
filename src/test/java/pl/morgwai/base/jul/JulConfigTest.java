// Copyright (c) Piotr Morgwai Kotarbinski, Licensed under the Apache License, Version 2.0
package pl.morgwai.base.jul;

import java.io.IOException;
import java.util.*;
import java.util.logging.*;

import org.junit.*;

import static org.junit.Assert.*;
import static pl.morgwai.base.jul.JulConfig.LEVEL_SUFFIX;



public class JulConfigTest {



	static final String EXAMPLE_DOMAIN = "com.example";



	@Test
	public void testNamesFromProperty() throws IOException {
		System.setProperty(JulConfig.OVERRIDE_LEVEL_PROPERTY,
				"," + ConsoleHandler.class.getName() + "," + EXAMPLE_DOMAIN);
		System.setProperty(ConsoleHandler.class.getName() + LEVEL_SUFFIX, Level.SEVERE.toString());
		System.setProperty(EXAMPLE_DOMAIN + LEVEL_SUFFIX, Level.SEVERE.toString());
		System.setProperty(LEVEL_SUFFIX, Level.SEVERE.toString());
		System.setProperty(JulConfig.JUL_CONFIG_CLASS_PROPERTY, JulConfig.class.getName());

		LogManager.getLogManager().readConfiguration();

		assertEquals("ConsoleHandler should have level as in the property",
				System.getProperty(ConsoleHandler.class.getName() + LEVEL_SUFFIX),
				new ConsoleHandler().getLevel().toString());
		assertEquals(EXAMPLE_DOMAIN + " logger should have level as in the property",
				System.getProperty(EXAMPLE_DOMAIN + LEVEL_SUFFIX),
				Logger.getLogger(EXAMPLE_DOMAIN).getLevel().toString());
		assertEquals("root logger should have level as in the property",
				System.getProperty(LEVEL_SUFFIX), Logger.getLogger("").getLevel().toString());
	}



	@Test
	public void testNamesFromParams() {
		System.setProperty(ConsoleHandler.class.getName() + LEVEL_SUFFIX, Level.SEVERE.toString());
		System.setProperty(EXAMPLE_DOMAIN + LEVEL_SUFFIX, Level.SEVERE.toString());
		System.setProperty(LEVEL_SUFFIX, Level.SEVERE.toString());

		JulConfig.overrideLogLevelsWithSystemProperties(
				ConsoleHandler.class.getName(), EXAMPLE_DOMAIN, "");

		assertEquals("ConsoleHandler should have level as in the property",
				System.getProperty(ConsoleHandler.class.getName() + LEVEL_SUFFIX),
				new ConsoleHandler().getLevel().toString());
		assertEquals(EXAMPLE_DOMAIN + " logger should have level as in the property",
				System.getProperty(EXAMPLE_DOMAIN + LEVEL_SUFFIX),
				Logger.getLogger(EXAMPLE_DOMAIN).getLevel().toString());
		assertEquals("root logger should have level as in the property",
				System.getProperty(LEVEL_SUFFIX), Logger.getLogger("").getLevel().toString());
	}



	@Test
	public void testNamesFromBothPropertyAndParams() {
		System.setProperty(JulConfig.OVERRIDE_LEVEL_PROPERTY, ConsoleHandler.class.getName());
		System.setProperty(ConsoleHandler.class.getName() + LEVEL_SUFFIX, Level.SEVERE.toString());
		System.setProperty(EXAMPLE_DOMAIN + LEVEL_SUFFIX, Level.SEVERE.toString());

		JulConfig.overrideLogLevelsWithSystemProperties(EXAMPLE_DOMAIN);

		assertEquals("ConsoleHandler should have level as in the property",
				System.getProperty(ConsoleHandler.class.getName() + LEVEL_SUFFIX),
				new ConsoleHandler().getLevel().toString());
		assertEquals(EXAMPLE_DOMAIN + " logger should have level as in the property",
				System.getProperty(EXAMPLE_DOMAIN + LEVEL_SUFFIX),
				Logger.getLogger(EXAMPLE_DOMAIN).getLevel().toString());
	}



	@Test
	public void testLogManagerUpdateConfigurationCallsLogMapperEvenWithEmptyUpdates() {
		boolean[] mapperCalledHolder = {false};

		JulConfig.logManagerUpdateConfiguration(
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
			ConsoleHandler.class.getName() + LEVEL_SUFFIX, Level.SEVERE.toString(),
			EXAMPLE_DOMAIN + LEVEL_SUFFIX, Level.SEVERE.toString(),
			LEVEL_SUFFIX, Level.SEVERE.toString()
		);

		JulConfig.addOrReplaceLoggingConfigProperties(loggingConfigUpdates);

		assertEquals("ConsoleHandler should have level as in the property",
				loggingConfigUpdates.get(ConsoleHandler.class.getName() + LEVEL_SUFFIX),
				new ConsoleHandler().getLevel().toString());
		assertEquals(EXAMPLE_DOMAIN + " logger should have level as in the property",
				loggingConfigUpdates.get(EXAMPLE_DOMAIN + LEVEL_SUFFIX),
				Logger.getLogger(EXAMPLE_DOMAIN).getLevel().toString());
		assertEquals("root logger should have level as in the property",
				loggingConfigUpdates.get(LEVEL_SUFFIX), Logger.getLogger("").getLevel().toString());
	}



	Map<String, String> systemPropertiesBackup;

	@Before
	public void backupSystemProperties() {
		systemPropertiesBackup = new HashMap<>();
		backupSystemProperty(JulConfig.JUL_CONFIG_CLASS_PROPERTY);
		backupSystemProperty(JulConfig.OVERRIDE_LEVEL_PROPERTY);
		backupSystemProperty(ConsoleHandler.class.getName() + LEVEL_SUFFIX);
		backupSystemProperty(EXAMPLE_DOMAIN + LEVEL_SUFFIX);
		backupSystemProperty(LEVEL_SUFFIX);
	}

	void backupSystemProperty(String name) {
		systemPropertiesBackup.put(name, System.getProperty(name));
	}

	@After
	public void restoreSystemProperties() {
		for(var key: systemPropertiesBackup.keySet()) {
			String value = systemPropertiesBackup.get(key);
			if (value != null) {
				System.setProperty(key, systemPropertiesBackup.get(key));
			} else {
				System.clearProperty(key);
			}
		}
	}



	@Before
	public void prepareJulConfig() throws IOException {
		LogManager.getLogManager().updateConfiguration(
			(key) -> (oldVal, newVal) ->
					key.equals(LEVEL_SUFFIX) ? Level.INFO.toString() : newVal
		);
	}

	@After
	public void restoreJulConfig() throws IOException {
		LogManager.getLogManager().readConfiguration();
	}
}
