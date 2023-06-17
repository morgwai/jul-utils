// Copyright (c) Piotr Morgwai Kotarbinski, Licensed under the Apache License, Version 2.0
package pl.morgwai.base.jul;

import java.io.IOException;
import java.io.InputStream;
import java.util.logging.LogManager;



/**
 * A LogManager that does not get reset automatically at JVM shutdown. Useful if logs from user
 * shutdown hooks are important.
 * <p>
 * To use this class, define system property named
 * {@value #JUL_LOG_MANAGER_PROPERTY} with fully qualified name of this class as the value:</p>
 * <pre>
 * java -Djava.util.logging.manager=pl.morgwai.base.logging.JulManualResetLogManager \
 *     -cp ${CLASSPATH} MyMainClass</pre>
 * <p>
 * It is then user's responsibility to call {@link #manualReset()} at the end of his shutdown
 * hook:</p>
 * <pre>
 * ((pl.morgwai.base.logging.JulManualResetLogManager) LogManager.getLogManager()).manualReset();
 * </pre>
 */
public class JulManualResetLogManager extends LogManager {



	/**
	 * {@value #JUL_LOG_MANAGER_PROPERTY}
	 */
	public static final String JUL_LOG_MANAGER_PROPERTY = "java.util.logging.manager";



	final Object lock = new Object();
	boolean readingConfiguration = false;



	/**
	 * Has no effect. Use {@link #manualReset()} instead.
	 */
	@Override
	public void reset() throws SecurityException {
		synchronized (lock) {
			if (readingConfiguration) super.reset();
		}
	}



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



	/**
	 * Calls {@link LogManager#reset() super.reset()}.
	 */
	public void manualReset() throws SecurityException {
		super.reset();
	}
}
