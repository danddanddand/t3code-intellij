# CLAUDE.md

Guidance for agents working on this plugin.

## Commands

- `./gradlew buildPlugin` builds the plugin zip into `build/distributions/`
- `./gradlew runIde` starts a sandbox IDE with the plugin installed
- `./gradlew verifyPlugin` validates the descriptor and binary compatibility
- `sandbox/run-sandbox.sh [minutes]` reproduces embedded-browser failures in an isolated IDE

## Guidelines

- Modern idiomatic Kotlin; prefer `Foo.instance` for services
- Use IntelliJ platform APIs over raw Swing or hand-rolled scheduling
- JCEF lives in the bundled `com.intellij.modules.jcef` plugin on 2026.2; keep the
  dependency declared in both `plugin.xml` and `gradle.properties`
- All browser state changes go through `T3CodeBrowserPanel` so the health monitor
  and the status view stay in sync
- Releases: bump `pluginVersion`, add a CHANGELOG section, tag `v<version>`; the
  Release workflow builds, attaches the zip and publishes when `PUBLISH_TOKEN` is set
