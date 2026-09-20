package ai.rever.boss.plugin.dynamic.xray

import ai.rever.boss.plugin.api.PanelComponentWithUI
import ai.rever.boss.plugin.api.PanelInfo
import androidx.compose.runtime.Composable
import com.arkivanov.decompose.ComponentContext

/**
 * Panel component for Plugin X-Ray.
 *
 * Owns the [PluginXrayViewModel]. The MCP tools reach the same ViewModel through the plugin
 * entry point's last-component reference - the same shape flow-bridge uses.
 *
 * The component itself does no scanning: every action is forwarded to the ViewModel, which runs
 * the work on [pluginScope]. The picker callback (which the host invokes from the UI thread)
 * is the only thing that touches the ViewModel from here.
 */
class PluginXrayComponent(
    ctx: ComponentContext,
    override val panelInfo: PanelInfo,
    private val filePickerProvider: ai.rever.boss.plugin.api.FilePickerProvider?,
    private val loadedProbe: LoadedPluginProbe,
    private val pluginScope: kotlinx.coroutines.CoroutineScope,
) : PanelComponentWithUI, ComponentContext by ctx {

    private val viewModel = PluginXrayViewModel(
        scanner = JarScanner(),
        loadedProbe = loadedProbe,
        pluginScope = pluginScope,
    )

    /** Exposed so the MCP tool provider can drive scans through the same state. */
    val exposedViewModel: PluginXrayViewModel get() = viewModel

    @Composable
    override fun Content() {
        PluginXrayContent(
            viewModel = viewModel,
            onPickFile = { pickJarFile() },
        )
    }

    /**
     * Open the host's file picker for a JAR.
     *
     * The picker hands us an absolute path on its callback. We forward to the ViewModel. If the
     * host predates `FilePickerProvider` we never reach this branch - the toolbar button is
     * disabled.
     */
    private fun pickJarFile() {
        val picker = filePickerProvider ?: return
        picker.pickFile(
            title = "Pick a plugin JAR",
            filters = listOf("jar"),
            onResult = { path ->
                if (path != null) {
                    viewModel.scanPicked(path)
                }
            },
        )
    }
}
