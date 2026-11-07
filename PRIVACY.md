# Privacy Policy

T3 Code Tool Window is a free, open-source plugin for JetBrains IDEs. It does not collect, store or
send any personal data, usage data or telemetry to the plugin's author or to anyone else.

## What the plugin connects to

- **Your T3 Code server.** The plugin loads the server URL you configure (by default
  `http://127.0.0.1:3773/`, a server on your own computer) in the IDE's embedded browser, and sends it
  a plain request to check it is running. What you do in T3 Code is between you and that server; the
  plugin does not read or keep it.
- **Nothing else.** The plugin itself makes no other network requests. Links in the pane that point to
  other sites open in your default browser, outside the plugin.

## What the plugin runs and stores on your computer

- **`t3 pair`.** When you choose Pair with T3 Code, the plugin runs the `t3` command-line tool you
  configured to create a pairing token for your server.
- **Dropped files.** Files you drag into the pane are read from disk and handed to the T3 Code page,
  the same way a browser upload would. The plugin does not copy or keep them.
- **Settings.** Server URL, CLI path and similar options are saved in the IDE's own settings.
- **Logs.** Diagnostic messages go to the IDE's log folder (`idea.log`, and `t3code-jcef.log` when
  verbose browser logging is on). They stay on your computer unless you share them.

## Third parties

The T3 Code server and app are made by T3 Tools Inc., and the IDE and its embedded browser by
JetBrains. Their own privacy policies apply to them. This plugin is not affiliated with T3 Tools Inc.

## Contact

Questions: open an issue at https://github.com/danddanddand/t3code-intellij/issues or email
t3code@borderconnect.com.
