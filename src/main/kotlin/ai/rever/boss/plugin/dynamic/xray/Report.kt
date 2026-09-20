package ai.rever.boss.plugin.dynamic.xray

import kotlinx.serialization.Serializable

/**
 * Result of a static scan of one plugin JAR.
 *
 * The scanner fills every field it can read from the JAR without loading any class. A field is
 * null / false when the source has no answer (no entry, manifest absent, parse failed). The
 * [JarScanner] is defensive: caps on every read bound memory and refuse-on-mismatch is preferred
 * to silent truncation.
 *
 * @see JarScanner for the read discipline and the caps.
 */
@Serializable
data class JarReport(
    /** Absolute path to the JAR that was scanned. Empty when the scan came from a loaded plugin. */
    val jarPath: String,
    /** Human-friendly label: the file's name, the loaded plugin's id, or a fallback. */
    val label: String,
    /** Size in bytes of the JAR, or 0 when the file is missing. */
    val jarSizeBytes: Long,
    /** SHA-256 of the JAR bytes, lowercase hex. */
    val jarSha256: String,
    /** True when the scanner could read the JAR at all (a bad zip is the only way this is false). */
    val readable: Boolean,
    /** First error the scanner hit, when [readable] is false. */
    val error: String? = null,

    /** Fields from META-INF/boss-plugin/plugin.json (raw text + parsed fields). */
    val manifest: ManifestReport = ManifestReport(),

    /** JAR-level hygiene checks (counts, biggest entry). */
    val hygiene: HygieneReport = HygieneReport(),
)

/** Everything the scanner could extract from META-INF/boss-plugin/plugin.json. */
@Serializable
data class ManifestReport(
    /** True when the manifest was found at the canonical path. */
    val present: Boolean = false,
    /** Path inside the JAR the scanner found the manifest at, when [present]. */
    val manifestPath: String = "",
    /** Raw text of the manifest, useful for the detail dialog. Empty when absent. */
    val rawText: String = "",
    /** Size in bytes of the manifest file inside the JAR. */
    val manifestSizeBytes: Long = 0,

    val pluginId: String = "",
    val displayName: String = "",
    val version: String = "",
    val description: String = "",
    val author: String = "",
    val url: String = "",
    val apiVersion: String = "",
    val minApiVersion: String = "",
    val minBossVersion: String = "",

    val mainClass: String = "",
    /** True when [mainClass] resolves to an actual .class entry inside the JAR. */
    val mainClassPresent: Boolean = false,

    /** PANEL / TAB / MIXED / HYBRID / SERVICE as a string (matches [PluginType]). */
    val type: String = "",
    /** Panel block, parsed from the JSON even when [PluginManifest] would drop it. */
    val panel: PanelBlock = PanelBlock(),
    /** Sandbox block. */
    val sandbox: SandboxBlock = SandboxBlock(),
    /** Process isolation mode: "in-process" or "out-of-process". */
    val isolationMode: String = "",
    /** Fallback isolation mode when the primary fails. */
    val fallback: String = "",
    /** Class the host should use to persist this plugin's state. */
    val stateHolderClass: String = "",

    /** Required permissions the user must hold for the plugin to load. */
    val requiredPermissions: List<String> = emptyList(),
    /** Permissions this plugin introduces to the RBAC catalog. */
    val definedPermissions: List<DefinedPermissionReport> = emptyList(),
    /** Dependencies declared in the manifest. */
    val dependencies: List<DependencyReport> = emptyList(),
)

/** Subset of the plugin.json panel block. */
@Serializable
data class PanelBlock(
    val position: String = "",
    val priority: Int = 0,
    val icon: String = "",
    val location: String = "",
    val order: Int = 0,
    val panelId: String = "",
    val displayName: String = "",
)

/** Subset of the plugin.json sandbox block. */
@Serializable
data class SandboxBlock(
    val maxThreads: Int = 0,
    val maxMemoryMb: Int = 0,
    val enableSandbox: Boolean = false,
    val heartbeatIntervalMs: Long = 0,
    val maxRestartAttempts: Int = 0,
)

/** Subset of a definedPermission entry. */
@Serializable
data class DefinedPermissionReport(
    val name: String,
    val description: String = "",
)

/** Subset of a dependency entry. */
@Serializable
data class DependencyReport(
    val pluginId: String,
    val version: String = "*",
    val optional: Boolean = false,
)

/** JAR-level hygiene signals. */
@Serializable
data class HygieneReport(
    /** Total number of entries inside the JAR. */
    val entryCount: Int = 0,
    /** True when at least one .class entry is present. */
    val hasCompiledClasses: Boolean = false,
    /** True when the JAR holds any resources but no .class files. */
    val resourceOnly: Boolean = false,
    /** Name + size of the largest single entry (smell test: zip bombs and large font payloads). */
    val largestEntry: EntryReport = EntryReport(),
    /** True when the manifest block exceeded the cap and was refused. */
    val manifestOversize: Boolean = false,
    /** True when [entryCount] exceeded the cap and the scanner stopped early. */
    val entryCountCapped: Boolean = false,
    /** True when any single entry exceeded the entry-size cap and was refused. */
    val entryOversize: Boolean = false,
)

/** Smallest possible view of a single JAR entry. */
@Serializable
data class EntryReport(
    val name: String = "",
    val sizeBytes: Long = 0,
)
