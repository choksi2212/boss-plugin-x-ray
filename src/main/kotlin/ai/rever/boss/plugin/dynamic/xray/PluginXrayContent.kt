package ai.rever.boss.plugin.dynamic.xray

import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.plugin.ui.BossThemeColors
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * The Plugin X-Ray panel.
 *
 * Two stacked sections:
 *  1. **Picked JARs** - the list of reports the user built up by picking JARs or asking
 *     "scan all loaded". Clicking a row opens the detail dialog.
 *  2. **Loaded plugins** - one row per loaded plugin with its id, version, and a one-line
 *     summary. Hidden when the host did not expose a [ai.rever.boss.plugin.api.PluginLoaderDelegate].
 *
 * The toolbar holds: a "Pick JAR" button (disabled when the host has no file picker), a
 * "Scan loaded" button (disabled when there is no plugin loader), and a "Clear" button for the
 * picked list.
 *
 * Errors land in the toast row at the top; both info and error share the row and auto-dismiss
 * after four seconds (the same timer flow-bridge uses).
 */
@Composable
fun PluginXrayContent(
    viewModel: PluginXrayViewModel,
    onPickFile: () -> Unit,
) {
    BossTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colors.background,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                HeaderRow(
                    onPickFile = onPickFile,
                    onScanLoaded = { viewModel.scanAllLoaded() },
                    onClear = { viewModel.clearReports() },
                    onClearLoaded = { viewModel.clearLoaded() },
                    loadedCount = viewModel.loaded.collectAsState().value.size,
                )

                Divider(color = MaterialTheme.colors.onBackground.copy(alpha = 0.1f))

                val info by viewModel.info.collectAsState()
                val error by viewModel.error.collectAsState()
                Toast(info = info, error = error, onDismiss = { viewModel.clearMessages() })

                ReportsSection(viewModel = viewModel)
            }
        }
    }
}

@Composable
private fun HeaderRow(
    onPickFile: () -> Unit,
    onScanLoaded: () -> Unit,
    onClear: () -> Unit,
    onClearLoaded: () -> Unit,
    loadedCount: Int,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .background(MaterialTheme.colors.surface)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Plugin X-Ray",
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colors.onSurface,
        )
        Spacer(modifier = Modifier.width(8.dp))
        OutlinedButton(
            onClick = onPickFile,
            modifier = Modifier.height(26.dp),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        ) {
            Icon(
                imageVector = Icons.Default.FolderOpen,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(text = "Pick JAR", fontSize = 11.sp)
        }
        Spacer(modifier = Modifier.width(4.dp))
        OutlinedButton(
            onClick = onScanLoaded,
            modifier = Modifier.height(26.dp),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(text = "Scan loaded", fontSize = 11.sp)
        }
        Spacer(modifier = Modifier.width(4.dp))
        OutlinedButton(
            onClick = onClear,
            modifier = Modifier.height(26.dp),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        ) {
            Text(text = "Clear", fontSize = 11.sp)
        }
        Spacer(modifier = Modifier.weight(1f))
        if (loadedCount > 0) {
            TextButton(
                onClick = onClearLoaded,
                modifier = Modifier.height(26.dp),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
            ) {
                Text(text = "Clear loaded", fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun Toast(info: String?, error: String?, onDismiss: () -> Unit) {
    if (info == null && error == null) return
    LaunchedEffect(info, error) {
        delay(4000)
        onDismiss()
    }
    val message = error ?: info ?: return
    val isError = error != null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isError) BossThemeColors.ErrorColor else BossThemeColors.SuccessColor)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (isError) Icons.Default.Error else Icons.Default.Search,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = BossThemeColors.TextPrimary,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = message,
            fontSize = 11.sp,
            color = BossThemeColors.TextPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onDismiss, modifier = Modifier.size(20.dp)) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Dismiss",
                modifier = Modifier.size(12.dp),
                tint = BossThemeColors.TextPrimary.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun ReportsSection(viewModel: PluginXrayViewModel) {
    val reports by viewModel.reports.collectAsState()
    val loaded by viewModel.loaded.collectAsState()
    var selected by remember { mutableStateOf<JarReport?>(null) }

    if (reports.isEmpty() && loaded.isEmpty()) {
        EmptyState()
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (reports.isNotEmpty()) {
            item {
                SectionHeader(title = "Scanned JARs (${reports.size})")
            }
            items(reports.size) { index ->
                val report = reports[index]
                ReportRow(report = report, onClick = { selected = report })
            }
        }
        if (loaded.isNotEmpty()) {
            item {
                SectionHeader(title = "Loaded plugins (${loaded.size})")
            }
            items(loaded.size) { index ->
                val row = loaded[index]
                LoadedRow(row = row, onClick = { selected = row.report })
            }
        }
    }

    selected?.let { report ->
        ReportDetailDialog(report = report, onDismiss = { selected = null })
    }
}

// items() comes from androidx.compose.foundation.lazy - imported at the top of the file.

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        fontSize = 10.sp,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
    )
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            text = "Pick a plugin JAR to see what it declares.",
            fontSize = 12.sp,
            color = MaterialTheme.colors.onBackground.copy(alpha = 0.7f),
        )
        Text(
            text = "Use \"Scan loaded\" to scan every plugin already in memory.",
            fontSize = 11.sp,
            color = MaterialTheme.colors.onBackground.copy(alpha = 0.5f),
        )
    }
}

@Composable
private fun ReportRow(report: JarReport, onClick: () -> Unit) {
    val tone = when {
        !report.readable -> BossThemeColors.WarningColor
        report.hygiene.entryCountCapped || report.hygiene.entryOversize || report.hygiene.manifestOversize ->
            BossThemeColors.WarningColor
        else -> BossThemeColors.SuccessColor
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colors.onBackground.copy(alpha = 0.1f),
                shape = RoundedCornerShape(4.dp),
            )
            .background(MaterialTheme.colors.surface.copy(alpha = 0.3f))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(tone, RoundedCornerShape(3.dp)),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = report.manifest.displayName.ifEmpty { report.label.ifEmpty { "(unnamed)" } },
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colors.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildString {
                    append(report.manifest.pluginId.ifEmpty { "(no pluginId)" })
                    if (report.manifest.version.isNotEmpty()) append(" @ ${report.manifest.version}")
                    if (!report.manifest.type.isEmpty()) append(" - ${report.manifest.type}")
                },
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colors.onBackground.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${report.manifest.mainClass.ifEmpty { "(no main class)" }} - " +
                    "${report.hygiene.entryCount} entries - " +
                    humanBytes(report.jarSizeBytes),
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colors.onBackground.copy(alpha = 0.5f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
            Text(text = "Details", fontSize = 10.sp)
        }
    }
}

@Composable
private fun LoadedRow(row: LoadedPluginProbe.LoadedProbeRow, onClick: () -> Unit) {
    ReportRow(report = row.report, onClick = onClick)
}

@Composable
private fun ReportDetailDialog(report: JarReport, onDismiss: () -> Unit) {
    // The dialog is intentionally a full-screen surface so the long manifest raw text can scroll.
    Surface(
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)),
        color = Color.Transparent,
    ) {
        Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colors.surface, RoundedCornerShape(8.dp))
                    .border(1.dp, MaterialTheme.colors.onBackground.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = report.manifest.displayName.ifEmpty { report.label },
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colors.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(20.dp)) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            modifier = Modifier.size(12.dp),
                        )
                    }
                }
                Divider(color = MaterialTheme.colors.onBackground.copy(alpha = 0.1f))
                Column(
                    modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    DetailLine("Plugin id", report.manifest.pluginId)
                    DetailLine("Version", report.manifest.version)
                    DetailLine("Type", report.manifest.type)
                    DetailLine("API version", report.manifest.apiVersion)
                    DetailLine("Min API", report.manifest.minApiVersion)
                    DetailLine("Min BOSS", report.manifest.minBossVersion)
                    DetailLine("Main class", report.manifest.mainClass)
                    DetailLine("Main present", if (report.manifest.mainClassPresent) "yes" else "no")
                    DetailLine("Isolation", report.manifest.isolationMode)
                    DetailLine("Fallback", report.manifest.fallback)
                    DetailLine("State holder", report.manifest.stateHolderClass)
                    DetailLine("Author", report.manifest.author)
                    DetailLine("URL", report.manifest.url)
                    DetailLine("Description", report.manifest.description)
                    DetailLine("JAR size", humanBytes(report.jarSizeBytes))
                    DetailLine("JAR sha256", report.jarSha256)
                    DetailLine("Entry count", report.hygiene.entryCount.toString())
                    DetailLine("Compiled classes", if (report.hygiene.hasCompiledClasses) "yes" else "no")
                    DetailLine("Resource only", if (report.hygiene.resourceOnly) "yes" else "no")
                    DetailLine(
                        "Largest entry",
                        if (report.hygiene.largestEntry.name.isEmpty()) {
                            "(none)"
                        } else {
                            "${report.hygiene.largestEntry.name} (${humanBytes(report.hygiene.largestEntry.sizeBytes)})"
                        },
                    )
                    if (report.hygiene.manifestOversize) {
                        WarningLine("Manifest exceeded the size cap and was refused")
                    }
                    if (report.hygiene.entryCountCapped) {
                        WarningLine("Entry count exceeded ${JarScanner.MAX_ENTRIES} and was capped")
                    }
                    if (report.hygiene.entryOversize) {
                        WarningLine("One or more entries exceeded ${humanBytes(JarScanner.MAX_ENTRY_BYTES)} and were refused")
                    }
                    DetailLine("Required permissions", report.manifest.requiredPermissions.joinToString(", ").ifEmpty { "(none)" })
                    if (report.manifest.definedPermissions.isNotEmpty()) {
                        DetailLine(
                            "Defined permissions",
                            report.manifest.definedPermissions.joinToString("\n") { "${it.name} - ${it.description}" },
                        )
                    }
                    if (report.manifest.dependencies.isNotEmpty()) {
                        DetailLine(
                            "Dependencies",
                            report.manifest.dependencies.joinToString("\n") { dep ->
                                buildString {
                                    append(dep.pluginId)
                                    if (dep.version.isNotEmpty() && dep.version != "*") append(" @ ${dep.version}")
                                    if (dep.optional) append(" (optional)")
                                }
                            },
                        )
                    }
                    DetailLine("Panel position", report.manifest.panel.position.ifEmpty { "(none)" })
                    DetailLine("Panel priority", report.manifest.panel.priority.toString())
                    DetailLine("Sandbox max threads", report.manifest.sandbox.maxThreads.toString())
                    if (report.manifest.rawText.isNotEmpty()) {
                        Text(
                            text = "Manifest (raw):",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                        )
                        Text(
                            text = report.manifest.rawText.take(RAW_TEXT_LIMIT),
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colors.onBackground.copy(alpha = 0.6f),
                        )
                    }
                    if (!report.readable) {
                        WarningLine(report.error ?: "JAR was unreadable")
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    if (value.isEmpty()) return
    Column {
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
        )
        Text(
            text = value,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colors.onBackground,
        )
    }
}

@Composable
private fun WarningLine(message: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, BossThemeColors.WarningColor.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
            .background(BossThemeColors.WarningColor.copy(alpha = 0.08f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = null,
            modifier = Modifier.size(12.dp),
            tint = BossThemeColors.WarningColor,
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = message,
            fontSize = 10.sp,
            color = MaterialTheme.colors.onBackground,
        )
    }
}

private fun humanBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = listOf("B", "KiB", "MiB", "GiB")
    var value = bytes.toDouble()
    var idx = 0
    while (value >= 1024 && idx < units.lastIndex) {
        value /= 1024
        idx += 1
    }
    return if (idx == 0) "${bytes} ${units[0]}" else String.format("%.2f %s", value, units[idx])
}

private const val RAW_TEXT_LIMIT = 16_000
