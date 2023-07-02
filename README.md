# jul utils

`java.util.logging` utilities.<br/>
<br/>
**latest release: [2.0](https://search.maven.org/artifact/pl.morgwai.base/jul-utils/2.0/jar)**
([javadoc](https://javadoc.io/doc/pl.morgwai.base/jul-utils/2.0))


## MAIN USER CLASSES

### [JulConfigurator](src/main/java/pl/morgwai/base/jul/JulConfigurator.java)
Utilities to manipulate `java.util.logging` config, among others allows to override log levels of `Logger`s and `Handler`s with values from system properties at startup in existing java apps without rebuilding: just add jul-utils.jar to command-line class-path and define desired system properties.

### [JulFormatter](src/main/java/pl/morgwai/base/jul/JulFormatter.java)
A text log formatter similar to `SimpleFormatter` that additionally allows to format stack trace elements and to add log sequence id and thread id to log entries.

### [JulManualResetLogManager](src/main/java/pl/morgwai/base/jul/JulManualResetLogManager.java)
A LogManager that does not get reset automatically at JVM shutdown. Useful if logs from user shutdown hooks are important. See a usage example [here](https://github.com/morgwai/grpc-scopes/blob/v9.0/sample/src/main/java/pl/morgwai/samples/grpc/scopes/grpc/RecordStorageServer.java#L90) (notice the static initializer a few lines below).
