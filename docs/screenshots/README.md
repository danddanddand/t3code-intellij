# Screenshots

JetBrains Marketplace screenshots, all 2400 x 1520 px (2x of 1200 x 760), taken in the sandbox IDE
(`sandbox/run-sandbox.sh`) against the fictional `sandbox/demo-app` project. Each comes in a dark
version (Islands Dark) and a `-light` version (Islands Light).

- `1-tool-window.png`: the T3 Code tool window next to the editor, with a finished agent thread
- `2-thread-list.png`: the T3 Code thread sidebar with several threads for the project
- `3-settings.png`: Settings, Tools, T3 Code
- `4-health.png`: the status card shown when the T3 Code server is not reachable

The sandbox opens a Chrome DevTools port on 9333, which is how the T3 Code page was driven for these.
Its Chromium follows the system appearance, so the light versions hold `prefers-color-scheme: light`
over DevTools while capturing.
