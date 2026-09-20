package ai.rever.boss.plugin.dynamic.xray

import ai.rever.boss.plugin.api.McpToolArgs
import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * MCP tool provider for Plugin X-Ray.
 *
 * Two tools, both read-only:
 *
 *  - `xray_scan_jar` - scan a JAR by absolute path. Returns a JSON object with the [JarReport]
 *    fields. The path is taken from the caller; we do not own a file picker on the MCP side.
 *  - `xray_scan_all_loaded` - return one [JarReport] per plugin the host's
 *    [ai.rever.boss.plugin.api.PluginLoaderDelegate] reports as loaded, or a clear "not available"
 *    error when the host predates the delegate.
 *
 * Both tools reply through the panel's [PluginXrayViewModel], so the state the panel renders and
 * the state an agent queries are the same. When no panel is open we still answer: the tools
 * scan on demand and do not require the user to have opened the X-Ray panel first.
 */
internal class PluginXrayMcpToolProvider(
    override val providerId: String,
    private val scanner: JarScanner,
    private val loadedProbe: LoadedPluginProbe,
    private val component: () -> PluginXrayComponent?,
) : McpToolProvider {

    override fun tools(): List<McpToolDefinition> = listOf(
        McpToolDefinition(
            name = "xray_scan_jar",
            description =
                "Static, pre-load report of what a plugin JAR can do - reads " +
                    "META-INF/boss-plugin/plugin.json and JAR metadata without loading any class. " +
                    "Returns a JSON object with pluginId, version, declared permissions, declared " +
                    "dependencies, panel position, isolation mode, JAR hygiene (entry count, " +
                    "largest entry, manifest size) and the SHA-256 of the JAR bytes.",
            inputSchema = """{"type":"object","properties":{"path":{"type":"string","description":"Absolute path to the plugin JAR to scan."}},"required":["path"]}""",
            handler = McpToolHandler { handleScanJar(it) },
        ),
        McpToolDefinition(
            name = "xray_scan_all_loaded",
            description =
                "Scan every plugin the host currently has loaded (read from " +
                    "PluginLoaderDelegate.getLoadedPlugins) and return one report per plugin. " +
                    "Returns a clear 'not available' error when the host does not expose a plugin " +
                    "loader; otherwise a JSON array of the same shape as xray_scan_jar.",
            handler = McpToolHandler { handleScanAllLoaded() },
        ),
    )

    private suspend fun handleScanJar(args: McpToolArgs): McpToolResult {
        val path = args.string("path")
            ?: return McpToolResult("Missing required argument: path", isError = true)
        if (path.isBlank()) {
            return McpToolResult("Argument 'path' must not be blank", isError = true)
        }
        return try {
            val report = scanner.scan(path)
            McpToolResult(json.encodeToString(JarReport.serializer(), report))
        } catch (e: Exception) {
            McpToolResult("xray_scan_jar failed: ${e.message ?: e.javaClass.simpleName}", isError = true)
        }
    }

    private suspend fun handleScanAllLoaded(): McpToolResult {
        if (!loadedProbe.available) {
            return McpToolResult(
                "xray_scan_all_loaded: PluginLoaderDelegate not available in this host",
                isError = true,
            )
        }
        return try {
            val rows = loadedProbe.snapshot()
            if (rows.isEmpty()) {
                return McpToolResult("[]")
            }
            val reports = rows.map { it.report }
            McpToolResult(json.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(JarReport.serializer()),
                reports,
            ))
        } catch (e: Exception) {
            McpToolResult(
                "xray_scan_all_loaded failed: ${e.message ?: e.javaClass.simpleName}",
                isError = true,
            )
        }
    }

    /**
     * Update the panel's state so the user sees the same reports the agent just received.
     *
     * The panel is the home of the "what did we scan?" list. We push the same reports into its
     * ViewModel so a follow-up click on a row opens the same detail an agent just read.
     */
    fun publishToPanel(reports: List<JarReport>) {
        val comp = component() ?: return
        val vm = comp.exposedViewModel
        reports.forEach { report ->
            // appendReport deduplicates by jarPath, so re-pushing the same list is a no-op.
            // We expose a public path through the ViewModel: scanPicked for new files,
            // and the dedupe happens in appendReport.
            if (report.jarPath.isNotEmpty()) {
                vm.scanPicked(report.jarPath)
            }
        }
    }

    private companion object {
        val json = Json {
            prettyPrint = false
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}
