package ai.rever.boss.plugin.dynamic.xray

import ai.rever.boss.plugin.api.Panel
import ai.rever.boss.plugin.api.Panel.Companion.bottom
import ai.rever.boss.plugin.api.Panel.Companion.left
import ai.rever.boss.plugin.api.PanelId
import ai.rever.boss.plugin.api.PanelInfo
import compose.icons.FeatherIcons
import compose.icons.feathericons.Zap

/**
 * Plugin X-Ray panel info.
 *
 * Lives in the left bottom slot at priority 60 - same neighbourhood as the Toolbox panel, but
 * lower than it so X-Ray drops in below the heavier management surfaces. Static-analysis tools
 * are read-mostly, so a low-priority slot keeps the panel from competing with editing surfaces.
 */
object PluginXrayInfo : PanelInfo {
    override val id = PanelId("plugin-x-ray", 60)
    override val displayName = "Plugin X-Ray"
    override val icon = FeatherIcons.Zap
    override val defaultSlotPosition = left.bottom
}
