# Changelog

## [Unreleased]

## [0.1.5] - 2026-11-07

### Changed

- Built for IntelliJ Platform 2026.3 (build 263.*). The 0.1.4 build was limited to 2026.2 and the IDE refused to load it after the 2026.3 update.

## [0.1.4] - 2026-11-07

### Fixed

- Clicking a link to another site replaced T3 Code in the pane with no way back. Links that leave the T3 Code server, including ones that open a new window, now open in the system browser, as they do in the T3 Code desktop app.
- A page that failed to load part of itself (an ad frame on the JetBrains Marketplace) could leave the pane blank for good: JCEF reported the error with a missing value, the plugin's handler threw, and the exception broke JCEF's link to its Chromium server. Every browser callback now tolerates missing values and keeps its own errors.
- **Restart Browser** now relaunches Chromium when the page has stopped answering, instead of reopening it on the same broken link.
- **Restart Browser** no longer logs an "Already disposed" IDE error while closing the old browser.

## [0.1.3] - 2026-11-07

### Added

- Drag files from Finder or another app into the T3 Code pane. Images and other files attach to the composer as they do in the T3 Code desktop app, folders are inserted as path mentions, and images dragged from a browser or Preview attach as PNG. The IDE's out-of-process browser does not pass drags to the page, so the plugin catches the drop and hands the files to T3 Code itself. Files over 50 MB are skipped with a notification.

### Fixed

- The embedded browser no longer dies about two minutes after the pane opens on IntelliJ 2026.2 (JetBrains JBR-10027). JCEF treats any idle handler thread expiring as a lost connection to its Chromium server and shuts Chromium down; the plugin now keeps those threads alive, so the false disconnect never happens. This fixes Markdown preview and other embedded browsers in the IDE too.
- IntelliJ IDEA 2026.2.4 (build 262.11879) crashed at startup when the T3 Code pane reopened with JCEF in in-process mode, the mode this plugin used to recommend. From that build on, in-process Chromium hits a native check as it loads and takes the IDE down. The pane now refuses to start the browser in that mode and offers **Switch Back and Restart IDE**, which restores the registry key `ide.browser.jcef.out-of-process.enabled` to its default.
- **Restart Browser** could not bring the pane back after the Chromium server exited, because the IDE only registers its own restart action in internal mode. The plugin now relaunches JCEF itself, and does so once automatically if the server exits.

- Notes on the settings page now wrap instead of running off the edge and adding a horizontal scrollbar in narrow Settings windows.

### Removed

- The prompt to switch JCEF to in-process mode. It only comes back, inside the error card, if the JBR-10027 fix cannot be applied on an IDE build older than 2026.2.4.

## [0.1.2] - 2026-10-27

### Changed

- Compile against the platform's interface default methods directly, so the plugin verifier no longer reports deprecated or experimental API usage from generated stubs.
- The settings page shows the diagnostics log path relative to the home directory.

## [0.1.1] - 2026-10-27

### Changed

- Vendor contact email in the plugin descriptor.

## [0.1.0] - 2026-10-27

### Added

- T3 Code tool window that embeds the T3 Code web UI in the IDE's bundled Chromium (JCEF).
- Server reachability check before loading, with a Retry card when the server is down.
- One-click pairing that runs `t3 pair` and loads the pairing URL.
- Health monitor: JavaScript ping, renderer-crash and load-error handlers, and a watchdog on the out-of-process Chromium server, each surfaced as a status card plus a notification.
- Advisor for IntelliJ 2026.2's out-of-process JCEF, which exits about two minutes after the first browser is created (JetBrains JBR-10027). Offers to switch the IDE to in-process JCEF and restart.
- Settings under Tools, T3 Code: server URL, `t3` CLI path, pairing TTL, health-check timing, logging.
- Sandbox harness (`sandbox/run-sandbox.sh`) that reproduces browser failures in an isolated IDE.
