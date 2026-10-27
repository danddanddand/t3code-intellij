# T3 Code Tool Window for JetBrains IDEs

[![Build](https://github.com/danddanddand/t3code-intellij/actions/workflows/build.yml/badge.svg)](https://github.com/danddanddand/t3code-intellij/actions/workflows/build.yml)

Runs the [T3 Code](https://t3.gg/code) web UI inside an IntelliJ-based IDE as a tool window,
using the IDE's bundled Chromium (JCEF). The pane behaves like the T3 Code desktop client:
no URL bar, no browser chrome, just the app, with Reload, Pair and Restart in the tool window
header.

The embedded browser is monitored. A server that is down, a page that fails to load, a frozen
page, a crashed renderer or a dead Chromium process is reported with a status card, a
notification and a one-click fix instead of a blank pane.

This is an independent, community-maintained plugin. It is not affiliated with or endorsed by
T3 Tools Inc. For a native (non-web-view) take on the same idea see
[xxfast/t3-jetbrains-plugin](https://github.com/xxfast/t3-jetbrains-plugin).

<!-- Plugin description -->
Embeds the <a href="https://t3.gg/code">T3 Code</a> web UI in a tool window so you can drive
your agents without leaving the IDE. Unofficial; not affiliated with T3 Tools Inc.

<ul>
<li>Loads your local T3 Code server (default <code>http://127.0.0.1:3773/</code>) in the IDE's embedded Chromium, with no browser chrome.</li>
<li>Checks the server is reachable before loading and explains how to start it when it is not.</li>
<li>Pairs with the server for you by running <code>t3 pair</code>.</li>
<li>Monitors the page and the Chromium process and reports a frozen or crashed browser with a one-click restart.</li>
<li>Detects the IntelliJ 2026.2 out-of-process JCEF bug (JBR-10027) that kills embedded browsers after two minutes and offers to switch the IDE to in-process JCEF.</li>
</ul>
<!-- Plugin description end -->

## Install

From the JetBrains Marketplace: Settings, Plugins, search for "T3 Code Tool Window".

From a zip: download it from the [releases page](https://github.com/danddanddand/t3code-intellij/releases),
then Settings, Plugins, gear icon, Install Plugin from Disk.

Requires an IntelliJ Platform 2026.2 IDE (IntelliJ IDEA, WebStorm, PyCharm, GoLand, Rider, …).

## Usage

1. Run the T3 Code desktop app, or `t3 serve`, so the server is listening on port 3773.
2. Open the **T3 Code** tool window on the right edge.
3. The first time, press **Pair** in the gear menu. The plugin runs `t3 pair`, loads the pairing
   URL, and the browser keeps the session from then on.

Settings live under Settings, Tools, T3 Code: server URL, path to the `t3` CLI, pairing TTL,
health-check timing and diagnostic logging.

### IntelliJ 2026.2 and out-of-process JCEF

2026.2 runs embedded Chromium in a separate `cef_server` process by default. A bug in the
JCEF client ([JBR-10027](https://youtrack.jetbrains.com/issue/JBR-10027)) makes that process exit
about two minutes after the first browser is created, which blanks every embedded browser in the
IDE (this plugin, Markdown preview, and so on). The plugin detects the situation and offers
**Switch and Restart IDE**, which sets the registry key
`ide.browser.jcef.out-of-process.enabled` to `false`. You can do the same by hand from
Help, Find Action, Registry.

## Build

```
./gradlew buildPlugin     # zip in build/distributions/
./gradlew runIde          # sandbox IDE with the plugin installed
./gradlew verifyPlugin    # structure and compatibility checks
```

Needs JDK 25. The sandbox IDE is IntelliJ IDEA Ultimate (Community is no longer published
since 2025.3), so `runIde` will ask for a licence.

### Reproducing browser failures

`sandbox/run-sandbox.sh [minutes]` starts an isolated `t3 serve` on port 3799, launches a
sandbox IDE with JCEF logging forced on and the tool window auto-opened, samples the Chromium
processes every five seconds and collects every log into `sandbox/out/`.
`T3_SANDBOX_URL=<url>` points the pane at any other page; `T3_SANDBOX_INPROCESS=true` runs
JCEF in-process.

## Licence

MIT. The icons are derived from the T3 Code logo, also MIT, see [NOTICE](NOTICE).
