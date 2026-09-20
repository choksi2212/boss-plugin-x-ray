# AGENTS.md

## Project Overview

**Plugin X-Ray** (`ai.rever.boss.plugin.dynamic.xray`) is a dynamic plugin for the BOSS
desktop application.

A static, pre-load report of what a plugin JAR can do - reads
`META-INF/boss-plugin/plugin.json` and JAR metadata, lists MCP tools and permissions
without loading classes.

- **Plugin ID**: `ai.rever.boss.plugin.dynamic.xray`
- **Main Class**: `ai.rever.boss.plugin.dynamic.xray.PluginXrayDynamicPlugin`
- **API Version**: 1.0.93

## Essential Commands

```bash
./gradlew buildPluginJar    # Build plugin JAR (output: build/libs/)
./gradlew build              # Full build (Tests CI workflow runs this)
./gradlew processResources   # Process resources (syncs version)
```

## Workflow Rules

- Do NOT run the BOSS application to test. The user will test manually.
- After building, copy JAR to `~/.boss/plugins/` for local testing.

## Architecture

### Plugin Structure

```
src/main/kotlin/   -> Plugin source code (package: ai.rever.boss.plugin.dynamic.xray)
src/main/resources/META-INF/boss-plugin/plugin.json -> Plugin manifest
build.gradle.kts   -> Build config + version (single source of truth)
```

### Module Layout

- `PluginXrayDynamicPlugin` - entry point. Registers the panel + the MCP tool provider.
- `PluginXrayInfo` - panel id, icon, slot, priority.
- `PluginXrayComponent` - panel component, owns the ViewModel, exposes it to MCP tools.
- `PluginXrayViewModel` - state holder (reports, loaded probe, info/error toasts).
- `PluginXrayContent` - composable panel UI.
- `JarScanner` - the actual scanner. Bounded reads; refuses oversize entries.
- `LoadedPluginProbe` - reads `PluginLoaderDelegate.getLoadedPlugins()` and pairs each
  record with a `JarReport`.
- `PluginXrayMcpTools` - MCP provider for `xray_scan_jar` and `xray_scan_all_loaded`.
- `Report.kt` - the data classes for the report.

### Key Patterns

- Entry point: `DynamicPlugin` interface with `register(context)` and `dispose()`.
- UI: `PanelComponentWithUI` with `@Composable Content()`.
- State: ViewModel pattern with `StateFlow`.
- The MCP tool provider reaches the ViewModel through the plugin entry point's
  `lastComponent()` reference - the same shape flow-bridge uses.
- The plugin reaches the host's `PluginLoaderDelegate` through
  `context.getPluginAPI(PluginLoaderDelegate::class.java)`. Older hosts without the
  delegate still compile and run; the "Scan loaded" button hides and the MCP tool
  replies with a clear "not available" error.

### Zip-Bomb Discipline

`JarScanner` defends against malicious JARs:

- `MAX_MANIFEST_BYTES = 1 MiB` - the manifest is read via `InputStream.readNBytes` with
  this cap; anything bigger is refused before parsing.
- `MAX_ENTRIES = 5000` - the JAR's entry list is read up to this count; past it the
  scanner stops counting and records `entryCountCapped`.
- `MAX_ENTRY_BYTES = 100 MiB` - any single entry reporting more than this is refused
  before any byte is read.
- `JarFile(stream, verify=true)` - CRC mismatches throw early.
- No entry is ever decompressed; only the manifest entry's bytes (capped) and the JAR's
  raw bytes (for the SHA-256) are read.

The caps are constants on `JarScanner.Companion` so a future test can change them.

## Dependencies

- **boss-plugin-api**: compileOnly (provided by host app at runtime).
- **Compose Desktop**: UI framework.
- **Decompose**: Navigation and component lifecycle.
- **Coroutines**: Async operations.
- **kotlinx.serialization**: JSON for the manifest parser and the MCP tool responses.

## Version Management

**`build.gradle.kts` is the single source of truth for version.**

The `processResources` task automatically syncs the version into `plugin.json` at
build time. Never manually edit the version in `plugin.json` - only change it in
`build.gradle.kts`.

## Code Quality

- Use Compose Multiplatform APIs (not Android-specific).
- All Kotlin files must end with a newline.
- Handle null providers gracefully - show fallback UI, never crash.
- The plugin reads the JAR defensively: no zip-bomb trust, hard caps on every read.

## CI/CD

Pushes to `main` trigger the release workflow which:

1. Builds the plugin JAR.
2. Creates a GitHub release.
3. Publishes to the BOSS Plugin Store.

The workflow is defined in `.github/workflows/build.yml` and delegates to the shared
workflow in `risa-labs-inc/BossConsole-Releases`.

Pull requests run the test workflow at `.github/workflows/test.yml`, which builds the
JAR against the downloaded `boss-plugin-api` jar. Both workflows require
`permissions: contents: write` on the release workflow and `contents: read` on the
test workflow.
