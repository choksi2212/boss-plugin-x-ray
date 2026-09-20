package ai.rever.boss.plugin.dynamic.xray

import ai.rever.boss.plugin.api.LoadedPluginInfo
import ai.rever.boss.plugin.api.PluginLoaderDelegate

/**
 * Lists already-loaded plugins through the host's [PluginLoaderDelegate].
 *
 * The host's [PluginLoaderDelegate.getLoadedPlugins] returns metadata-only records for every
 * plugin currently in memory. For each record that carries a `jarPath` pointing at a file on
 * disk we hand the JAR to [JarScanner.scan] to produce the same [JarReport] the file picker
 * would. Records without a usable path skip the on-disk scan and fall back to the metadata-only
 * path.
 *
 * The probe is stateless. The plugin entry point reads [snapshot] each time it needs a list, and
 * the panel / MCP tool simply asks the probe.
 */
class LoadedPluginProbe(
    private val loader: PluginLoaderDelegate?,
    private val scanner: JarScanner,
) {
    /** A loaded plugin, paired with its on-disk [JarReport] when one could be built. */
    data class LoadedProbeRow(
        val info: LoadedPluginInfo,
        val report: JarReport,
    )

    /**
     * Snapshot of every loaded plugin that has a JAR we can read.
     *
     * Plugins whose JAR path is empty or no longer points at a file are still returned, with
     * [JarReport.readable] false. The caller renders those as "loaded but file is missing" rather
     * than dropping them - the user is looking at a list of loaded plugins, after all.
     */
    fun snapshot(): List<LoadedProbeRow> {
        val delegate = loader ?: return emptyList()
        val loaded = runCatching { delegate.getLoadedPlugins() }.getOrNull() ?: return emptyList()
        return loaded.map { info ->
            val report = if (info.jarPath.isNotEmpty()) {
                scanner.fromLoaded(
                    jarPath = info.jarPath,
                    pluginId = info.pluginId,
                    displayName = info.displayName,
                    version = info.version,
                )
            } else {
                JarReport(
                    jarPath = "",
                    label = info.displayName.ifEmpty { info.pluginId },
                    jarSizeBytes = 0L,
                    jarSha256 = "",
                    readable = false,
                    error = "plugin loader did not report a jarPath for ${info.pluginId}",
                    manifest = ManifestReport(
                        present = true,
                        pluginId = info.pluginId,
                        displayName = info.displayName,
                        version = info.version,
                    ),
                )
            }
            LoadedProbeRow(info = info, report = report)
        }
    }

    /** True when the host exposes a [PluginLoaderDelegate]. */
    val available: Boolean get() = loader != null
}
