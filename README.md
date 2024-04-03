# JUL utils

`java.util.logging` utilities.<br/>
Copyright 2021 Piotr Morgwai Kotarbinski, Licensed under the Apache License, Version 2.0.<br/>
<br/>
**latest release: [4.1](https://search.maven.org/artifact/pl.morgwai.base/jul-utils/4.1/jar)**
([javadoc](https://javadoc.io/doc/pl.morgwai.base/jul-utils/4.1))


## MAIN USER CLASSES

### [JulConfigurator](https://javadoc.io/doc/pl.morgwai.base/jul-utils/latest/pl/morgwai/base/jul/JulConfigurator.html)
Utilities to manipulate `java.util.logging` config, among others allows to override log levels with system properties in existing java apps without rebuilding: just add `jul-utils.jar` to your command-line class-path and define your desired properties.

### [JulFormatter](https://javadoc.io/doc/pl.morgwai.base/jul-utils/latest/pl/morgwai/base/jul/JulFormatter.html)
A text log formatter similar to `SimpleFormatter` that additionally allows to format stack trace elements and to add log sequence id and thread id to log entries.

### [JulManualResetLogManager](https://javadoc.io/doc/pl.morgwai.base/jul-utils/latest/pl/morgwai/base/jul/JulManualResetLogManager.html)
A LogManager that does not get reset automatically at JVM shutdown. Useful if logs from user shutdown hooks are important. See a usage example [here](https://github.com/morgwai/grpc-scopes/blob/v11.0/sample/src/main/java/pl/morgwai/samples/grpc/scopes/grpc/RecordStorageServer.java#L116) (notice the static initializer a [few lines below](https://github.com/morgwai/grpc-scopes/blob/v11.0/sample/src/main/java/pl/morgwai/samples/grpc/scopes/grpc/RecordStorageServer.java#L143-L149)).
