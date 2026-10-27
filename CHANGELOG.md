# Changelog

## [Unreleased]

## [0.1.0] - 2026-10-27

### Added

- T3 Code tool window that embeds the T3 Code web UI in the IDE's bundled Chromium (JCEF).
- Server reachability check before loading, with a Retry card when the server is down.
- One-click pairing that runs `t3 pair` and loads the pairing URL.
- Health monitor: JavaScript ping, renderer-crash and load-error handlers, and a watchdog on the out-of-process Chromium server, each surfaced as a status card plus a notification.
- Advisor for IntelliJ 2026.2's out-of-process JCEF, which exits about two minutes after the first browser is created (JetBrains JBR-10027). Offers to switch the IDE to in-process JCEF and restart.
- Settings under Tools, T3 Code: server URL, `t3` CLI path, pairing TTL, health-check timing, logging.
- Sandbox harness (`sandbox/run-sandbox.sh`) that reproduces browser failures in an isolated IDE.
