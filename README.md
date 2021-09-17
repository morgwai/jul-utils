# Java utils

Some utility classes.<br/>
<br/>
**latest release: [1.0](https://search.maven.org/artifact/pl.morgwai.base/java-utils/1.0/jar)**


## MAIN USER CLASSES

### [OrderedConcurrentOutputBuffer](src/main/java/pl/morgwai/base/utils/OrderedConcurrentOutputBuffer.java)

Buffers messages sent to some output stream until all of those that should be written before are available, so that they all can be written in the correct order. Useful for processing input streams in several concurrent threads when order of response messages must reflect the order of request messages.


### [JulFormatter](src/main/java/pl/morgwai/base/logging/JulFormatter.java)

A text log formatter similar to `SimpleFormatter` that additionally allows to format stack trace elements and to add log sequence id and thread id to log entries.


### [JulConfig](src/main/java/pl/morgwai/base/logging/JulConfig.java)

Updates logging levels of `java.util.logging` `Logger`s `Handler`s with values obtained from system properties.
