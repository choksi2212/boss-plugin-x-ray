package ai.rever.boss.plugin.dynamic.xray

import ai.rever.boss.plugin.api.DynamicPlugin
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.api.PluginLoaderDelegate
import com.arkivanov.essenty.lifecycle.doOnDestroy

/**
 * Plugin X-Ray dynamic plugin - loaded from external JAR.
 *
 * Exposes a static, pre-load view of what a plugin JAR can do without loading any class. Two
 * surfaces:
 *
 *  - A sidebar panel (`PluginXrayInfo`) that lists the JARs the user has picked and the reports
 *    the scanner has produced, with a detail dialog for each row.
 *  - An MCP tool provider exposing `xray_scan_jar` and `xray_scan_all_loaded`, both read-only,
 *    removed automatically when this plugin is disabled or unloaded.
 *
 * The plugin reaches the host's [ai.rever.boss.plugin.api.PluginLoaderDelegate] and
 * [ai.rever.boss.plugin.api.FilePickerProvider] only through [PluginContext]. On hosts that
 * predate either provider the corresponding button or tool degrades to a status line rather
 * than failing.
 */
class PluginXrayDynamicPlugin : DynamicPlugin {
    override val pluginId: String = "ai.rever.boss.plugin.dynamic.xray"
    override val displayName: String = "Plugin X-Ray (Dynamic)"
    override val version: String = manifestVersion()
    override val description: String =
        "Static, pre-load report of what a plugin JAR can do - reads META-INF/boss-plugin/plugin.json " +
            "and JAR metadata, lists MCP tools and permissions without loading classes."
    override val author: String = "choksi2212"
    override val url: String = "https://github.com/choksi2212/boss-plugin-x-ray"

    /**
     * Last opened panel, so the MCP tool provider can drive the same state the panel renders.
     *
     * The panel's component owns the [PluginXrayViewModel]; MCP tools that want to update the
     * panel's visible list go through it. A destroyed component's scope is cancelled, so MCP
     * tools driving a destroyed component would silently no-op with false success - clear the
     * reference when the panel closes.
     */
    @Volatile
    private var lastComponent: PluginXrayComponent? = null

    /**
     * The MCP tool provider. Held so [register] can unregister it on [dispose] if the host ever
     * needs that; today the host cleans up automatically with `registerMcpToolProvider`.
     */
    private var toolProvider: PluginXrayMcpToolProvider? = null

    override fun register(context: PluginContext) {
        val loader = runCatching { context.getPluginAPI(PluginLoaderDelegate::class.java) }.getOrNull()

        val scanner = JarScanner()
        val loadedProbe = LoadedPluginProbe(loader = loader, scanner = scanner)
        val picker = context.filePickerProvider

        context.panelRegistry.registerPanel(PluginXrayInfo) { ctx, panelInfo ->
            PluginXrayComponent(
                ctx = ctx,
                panelInfo = panelInfo,
                filePickerProvider = picker,
                loadedProbe = loadedProbe,
                pluginScope = context.pluginScope,
            ).also { comp ->
                lastComponent = comp
                ctx.lifecycle.doOnDestroy {
                    if (lastComponent === comp) {
                        lastComponent = null
                    }
                }
            }
        }

        val provider = PluginXrayMcpToolProvider(
            providerId = pluginId,
            scanner = scanner,
            loadedProbe = loadedProbe,
            component = { lastComponent },
        )
        toolProvider = provider
        context.registerMcpToolProvider(provider)
    }

    override fun dispose() {
        lastComponent = null
        toolProvider = null
    }

    /**
     * The version from *this* plugin's manifest.
     *
     * Every BOSS plugin ships `/META-INF/boss-plugin/plugin.json` at the same resource path, so a
     * single `getResourceAsStream` returns whichever jar comes first if the host ever loads
     * plugins through a shared or parent-first classloader - and this plugin would report
     * someone else's version. Only the one naming this plugin id is accepted.
     */
    private fun manifestVersion(): String =
        runCatching {
            javaClass.classLoader
                ?.getResources("META-INF/boss-plugin/plugin.json")
                ?.asSequence()
                ?.mapNotNull { url -> runCatching { url.readText() }.getOrNull() }
                ?.firstOrNull { text -> field(text, "pluginId") == pluginId }
                ?.let { text -> field(text, "version") }
        }.getOrNull() ?: "unknown"

    private fun field(manifest: String, name: String): String? =
        Regex(""""$name"\s*:\s*"([^"]+)"""").find(manifest)?.groupValues?.get(1)
}
