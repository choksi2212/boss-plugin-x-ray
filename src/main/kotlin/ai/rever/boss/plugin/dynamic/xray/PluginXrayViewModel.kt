package ai.rever.boss.plugin.dynamic.xray

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * State for the Plugin X-Ray panel and its MCP tools.
 *
 * The panel and the MCP tools share one ViewModel because both ask the same question
 * ("which JARs have we scanned?"). The MCP tool provider reaches the ViewModel through the
 * plugin entry point's last-component reference, the same shape flow-bridge uses.
 *
 * State:
 * - [reports]: every JAR the user (or an MCP agent) has asked about this session, newest last.
 * - [lastPicked]: the path of the last file the picker returned, for the toolbar hint.
 * - [info] / [error]: transient toast text.
 */
class PluginXrayViewModel(
    private val scanner: JarScanner,
    private val loadedProbe: LoadedPluginProbe,
    private val pluginScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val scope = pluginScope

    private val _reports = MutableStateFlow<List<JarReport>>(emptyList())
    val reports: StateFlow<List<JarReport>> = _reports.asStateFlow()

    private val _loaded = MutableStateFlow<List<LoadedPluginProbe.LoadedProbeRow>>(emptyList())
    val loaded: StateFlow<List<LoadedPluginProbe.LoadedProbeRow>> = _loaded.asStateFlow()

    private val _lastPicked = MutableStateFlow<String?>(null)
    val lastPicked: StateFlow<String?> = _lastPicked.asStateFlow()

    private val _info = MutableStateFlow<String?>(null)
    val info: StateFlow<String?> = _info.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /**
     * Scan one JAR the user picked through the host's file picker.
     *
     * The path comes from `FilePickerProvider.pickFile`, which always gives us an absolute path
     * the host has already vetted. We still verify the file exists and is a regular file.
     */
    fun scanPicked(jarPath: String) {
        scope.launch {
            try {
                val file = File(jarPath)
                if (!file.exists() || !file.isFile) {
                    _error.value = "Not a file: $jarPath"
                    return@launch
                }
                val report = scanner.scan(jarPath)
                if (!report.readable) {
                    _error.value = "Could not scan JAR: ${report.error ?: "unknown error"}"
                } else {
                    appendReport(report)
                    _lastPicked.value = jarPath
                    _info.value = "Scanned ${file.name}"
                }
            } catch (e: Exception) {
                _error.value = "Scan failed: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    /**
     * Snapshot every currently-loaded plugin through [loadedProbe] and scan each one.
     *
     * The button is hidden when the host predates [PluginLoaderDelegate], so this only runs when
     * the probe actually has a delegate to ask. The "loaded" list is also exposed to the panel
     * so the user can see which plugin is which without opening a file picker.
     */
    fun scanAllLoaded() {
        scope.launch {
            try {
                val rows = loadedProbe.snapshot()
                _loaded.value = rows
                if (rows.isEmpty()) {
                    _info.value = if (loadedProbe.available) {
                        "No loaded plugins reported a jarPath"
                    } else {
                        "Plugin loader not available in this host"
                    }
                    return@launch
                }
                rows.forEach { row ->
                    appendReport(row.report)
                }
                _info.value = "Scanned ${rows.size} loaded plugin(s)"
            } catch (e: Exception) {
                _error.value = "Loaded plugin scan failed: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    /** Drop every report we have accumulated. The loaded-probe list stays. */
    fun clearReports() {
        _reports.value = emptyList()
        _info.value = "Cleared report list"
    }

    /** Drop the loaded-probe list and the picker hint. Reports are kept. */
    fun clearLoaded() {
        _loaded.value = emptyList()
        _lastPicked.value = null
    }

    /** Clear the info / error toast. Called by the UI after the auto-dismiss timer fires. */
    fun clearMessages() {
        _info.value = null
        _error.value = null
    }

    /** Drop a single report by JAR path. Used when the user removes a row. */
    fun removeReport(jarPath: String) {
        _reports.value = _reports.value.filterNot { it.jarPath == jarPath }
    }

    private fun appendReport(report: JarReport) {
        // De-dup by jarPath: rescanning the same file replaces the old report rather than
        // stacking a duplicate row.
        val existing = _reports.value.filterNot { it.jarPath == report.jarPath }
        _reports.value = existing + report
    }
}
