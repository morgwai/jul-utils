# Java utils

Some utility classes.<br/>
<br/>
**latest release: [1.16](https://search.maven.org/artifact/pl.morgwai.base/java-utils/1.16/jar)**
([javadoc](https://javadoc.io/doc/pl.morgwai.base/java-utils/1.16))


## MAIN USER CLASSES

### [OrderedConcurrentOutputBuffer](src/main/java/pl/morgwai/base/concurrent/OrderedConcurrentOutputBuffer.java)
Buffers messages sent to some output stream until all of those that should be written before are available, so that they all can be written in the correct order. Useful for processing input streams in several concurrent threads when order of response messages must reflect the order of request messages. See a usage example [here](https://github.com/morgwai/grpc-utils/blob/v3.1/src/main/java/pl/morgwai/base/grpc/utils/OrderedConcurrentInboundObserver.java).

### [ConcurrentUtils](src/main/java/pl/morgwai/base/concurrent/ConcurrentUtils.java)
Some helper functions.

### [Awaitable](src/main/java/pl/morgwai/base/concurrent/Awaitable.java)
Utilities to await for multiple timed blocking operations, such as `Thread.join(timeout)`, `ExecutorService.awaitTermination(...)` etc. See a usage example [here](https://github.com/morgwai/grpc-utils/blob/v3.1/sample/src/main/java/pl/morgwai/samples/grpc/utils/SqueezedServer.java#L502).

### [JulFormatter](src/main/java/pl/morgwai/base/logging/JulFormatter.java)
A text log formatter similar to `SimpleFormatter` that additionally allows to format stack trace elements and to add log sequence id and thread id to log entries.

### [JulConfig](src/main/java/pl/morgwai/base/logging/JulConfig.java)
Utilities to manipulate `java.util.logging` config, among others allows to override log levels of `Logger`s and `Handler`s with values from system properties at startup in existing java apps without rebuilding: just add java-utils.jar to command-line class-path and define desired system properties.

### [JulManualResetLogManager](src/main/java/pl/morgwai/base/logging/JulManualResetLogManager.java)
A LogManager that does not get reset automatically at JVM shutdown. Useful if logs from user shutdown hooks are important. See a usage example [here](https://github.com/morgwai/grpc-scopes/blob/v9.0/sample/src/main/java/pl/morgwai/samples/grpc/scopes/grpc/RecordStorageServer.java#L90).

### [NoCopyByteArrayOutputStream](src/main/java/pl/morgwai/base/util/NoCopyByteArrayOutputStream.java)
A `ByteArrayOutputStream` that allows to directly access its underlying buffer after the stream was closed.
