// Copyright (c) Piotr Morgwai Kotarbinski, Licensed under the Apache License, Version 2.0
package pl.morgwai.base.jul;

import java.io.IOException;
import java.util.logging.*;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;



/**
 * These test require {@value JulManualResetLogManager#JUL_LOG_MANAGER_PROPERTY} system property
 * to be set to the fully qualified name of the {@link JulManualResetLogManager} class <b>before</b>
 * JUL is initialized. See maven surefire plugin config in pom.xml file.
 */
public class JulManualResetLogManagerTests {



	boolean handlerWasClosed = false;
	final Logger testLogger = Logger.getLogger("testLogger");
	final JulManualResetLogManager logManager =
			(JulManualResetLogManager) LogManager.getLogManager();



	@Before
	public void setHandler() {
		testLogger.addHandler(new ConsoleHandler() {
			@Override public void close() {
				handlerWasClosed = true;
				super.close();
			}
		});
	}



	@Test
	public void testResetIsPerformedOnReadConfiguration() throws IOException {
		logManager.readConfiguration();
		assertTrue("testHandler should be closed", handlerWasClosed);
	}



	@Test
	public void testResetDoesNothing() {
		logManager.reset();
		assertFalse("testHandler should not be closed", handlerWasClosed);
	}



	@Test
	public void testManualReset() {
		logManager.manualReset();
		assertTrue("testHandler should be closed", handlerWasClosed);
	}
}
