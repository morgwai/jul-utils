// Copyright 2021 Piotr Morgwai Kotarbinski, Licensed under the Apache License, Version 2.0
package pl.morgwai.base.jul;

import java.io.IOException;
import java.io.InputStream;
import java.util.logging.LogManager;



/**
 * {@link LogManager} that does not get {@link LogManager#reset() reset} automatically at JVM
 * shutdown to avoid losing logs from user {@link Runtime#addShutdownHook(Thread) shutdown hooks}.
 * <p>
 * To use this class, a {@link System#getProperty(String) system property} named
 * {@value #JUL_LOG_MANAGER_PROPERTY} must be defined to contain the
 * {@link Class#getName() fully-qualified name} of this class either on a command-line:</p>
 * <pre>{@code
 * java -Djava.util.logging.manager=pl.morgwai.base.jul.JulManualResetLogManager \
 *     -cp ${CLASSPATH} MyMainClass}</pre>
 * <p>
 * ...or in a static initializer of the main class:</p>
 * <pre>{@code
 * public class MyMainClass {
 *
 *     // at the top of the class to make sure it runs before any
 *     // other stuff that may trigger LogManager initialization
 *     static {
 *         System.setProperty(
 *             JulManualResetLogManager.JUL_LOG_MANAGER_PROPERTY,
 *             JulManualResetLogManager.class.getName()
 *         );
 *     }
 *
 *     public static void main(String[] args) {
 *         // ...
 *     }
 *
 *     // ...
 * }
 * }</pre>
 * <p>
 * It is then the user's responsibility to call {@link #manualReset()} at the end of their shutdown
 * hook:</p>
 * <pre>{@code
 * Runtime.getRuntime().addShutdownHook(new Thread(() -> {
 *     // ...
 *     log.info("this message won't be lost");
 *     ((JulManualResetLogManager) LogManager.getLogManager()).manualReset();
 * }));}</pre>
 */
public class JulManualResetLogManager extends LogManager {



	/** {@value #JUL_LOG_MANAGER_PROPERTY} (see {@link LogManager}). */
	public static final String JUL_LOG_MANAGER_PROPERTY = "java.util.logging.manager";



	final Object lock = new Object();
	boolean readingConfiguration = false;



	/** Has no effect. Use {@link #manualReset()} instead. */
	@Override
	public void reset() throws SecurityException {
		synchronized (lock) {
			if (readingConfiguration) super.reset();
		}
	}



	/**
	 * Behaves the same way as {@code super}.
	 * Properly calls {@link LogManager#reset() super.reset()} when needed.
	 */
	@Override
	public void readConfiguration(InputStream ins) throws IOException, SecurityException {
		synchronized (lock) {
			readingConfiguration = true;
			try {
				super.readConfiguration(ins);
			} finally {
				readingConfiguration = false;
			}
		}
	}



	/** Calls {@link LogManager#reset() super.reset()}. */
	public void manualReset() throws SecurityException {
		super.reset();
	}
}
