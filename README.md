# Plugin X-Ray

A static, pre-load report of what a plugin JAR can do. The host loads plugins at startup
and the only feedback is "binary compatible" or "incompatible" - there is no middle ground
where a user can see "this plugin registers three MCP tools, requires two permissions,
reads the home directory". This plugin fixes that.

## What it does

- A sidebar **Plugin X-Ray** panel listing plugin JARs the user points it at.
- For each JAR: a static analysis result - everything visible in
  `META-INF/boss-plugin/plugin.json` plus any inference we can do without loading the class.
- A second panel section showing the report for every already-loaded plugin
  (uses `PluginLoaderDelegate.getLoadedPlugins`).
- MCP tools so an in-terminal agent can ask "what can this plugin do?" for a JAR or for
  every loaded plugin at once.

## Why it is unique

No existing BOSS surface tells a user what a plugin will do *before* it loads. The host's
load-time check answers compatibility and stops there; the Toolbox lists a plugin's
declared `displayName` and one-line description and stops there. X-Ray is the only
plugin-shaped surface that reads the JAR as a JAR and tells the user what the manifest
declared and what JAR-shape signals (entry counts, biggest entry, manifest size) smell
like.

## Report fields

For each scanned JAR:

- `jarPath`, `jarSizeBytes`, `jarSha256`
- `pluginId`, `displayName`, `version`, `description`, `author`, `url`
- `apiVersion`, `minApiVersion`, `minBossVersion`
- `mainClass` + whether the class is present in the JAR
- `type` (`panel` / `tab` / `mixed` / `hybrid` / `service`)
- For panel-typed manifests: `panel.position`, `panel.priority`
- `dependencies` - each plugin id + `optional` flag + version range
- `requiredPermissions` and `definedPermissions` if declared
- `isolationMode`, `fallback`, `stateHolderClass` if declared
- `manifestSizeBytes` - how large `plugin.json` is
- `entryCount` - number of JAR entries
- `hasCompiledClasses` - whether the JAR contains `.class` files
- `largestEntry` - name + size of the largest single entry

## Zip-bomb defense

`JarScanner` defends against zip-bomb reads:

- `manifestSizeBytes` is capped at 1 MiB (`JarScanner.MAX_MANIFEST_BYTES`). Oversize
  manifests are refused before they reach the JSON parser.
- `entryCount` is capped at 5000 (`JarScanner.MAX_ENTRIES`). Capped scans record
  `entryCountCapped` and stop counting.
- A single entry is refused at 100 MiB (`JarScanner.MAX_ENTRY_BYTES`) and never read.
- `JarFile(stream, verify=true)` validates CRCs at open time.
- No entry is ever decompressed; only the manifest entry's bytes and the JAR's raw bytes
  (for the SHA-256) are read.

These caps mirror the host's own discipline in `plugin-loader`.

## MCP tools

| Tool | Purpose |
|---|---|
| `xray_scan_jar` | Scan a JAR by absolute path. Returns a JSON object with the [JarReport] fields. Read-only. |
| `xray_scan_all_loaded` | Scan every plugin the host currently has loaded and return one report per plugin. Returns a clear "not available" error when the host does not expose `PluginLoaderDelegate`. Read-only. |

Both tools reply through the panel's `PluginXrayViewModel`, so the state the panel
renders and the state an agent queries are the same.

## Requirements

- BOSS >= 9.5.0, `boss-plugin-api` >= 1.0.93.
- A host with `FilePickerProvider` for the "Pick JAR" button. On older hosts the button
  is still rendered but no-ops.
- A host with `PluginLoaderDelegate` for the "Scan loaded" button and
  `xray_scan_all_loaded`. On older hosts the button hides and the tool replies with a
  clear "PluginLoaderDelegate not available" error.

## Install

```bash
./gradlew buildPluginJar
cp build/libs/boss-plugin-x-ray-0.1.0.jar ~/.boss/plugins/
```

Then enable Plugin X-Ray from the Toolbox and open the panel from the left bottom
sidebar. The panel is at priority 60 - lower than the Toolbox, so it drops in below the
heavier management surfaces.

## License

Proprietary - Risa Labs Inc.
