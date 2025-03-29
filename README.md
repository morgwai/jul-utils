# JUL utils

`java.util.logging` utilities.<br/>
Copyright 2021 Piotr Morgwai Kotarbinski, Licensed under the Apache License, Version 2.0.<br/>
<br/>
**latest release: [4.2](https://search.maven.org/artifact/pl.morgwai.base/jul-utils/4.2/jar)**
([javadoc](https://javadoc.io/doc/pl.morgwai.base/jul-utils/4.2))<br/>
<br/>
See [CHANGES](CHANGES.md) for the summary of changes between releases. If the major version of a subsequent release remains unchanged, it is supposed to be backwards compatible in terms of API and behaviour with previous ones with the same major version (meaning that it should be safe to just blindly update in dependent projects and things should not break under normal circumstances).


## MAIN USER CLASSES

### [JulConfigurator](https://javadoc.io/doc/pl.morgwai.base/jul-utils/latest/pl/morgwai/base/jul/JulConfigurator.html)
Utilities to manipulate `java.util.logging` config, including overriding log `Level`s with system properties in existing apps without rebuilding.

### [JulFormatter](https://javadoc.io/doc/pl.morgwai.base/jul-utils/latest/pl/morgwai/base/jul/JulFormatter.html)
Text log `Formatter` similar to `SimpleFormatter` that additionally allows to format stack-trace elements and add log sequence id and `Thread` id to log entries.

### [JulManualResetLogManager](https://javadoc.io/doc/pl.morgwai.base/jul-utils/latest/pl/morgwai/base/jul/JulManualResetLogManager.html)
`LogManager` that does not get `reset()` automatically at JVM shutdown to avoid losing logs from user shutdown hooks. See a usage example [here](https://github.com/morgwai/grpc-scopes/blob/v12.2/sample/src/main/java/pl/morgwai/samples/grpc/scopes/grpc/RecordStorageServer.java#L138) (notice the static initializer a [few lines below](https://github.com/morgwai/grpc-scopes/blob/v12.2/sample/src/main/java/pl/morgwai/samples/grpc/scopes/grpc/RecordStorageServer.java#L169-L174)).
