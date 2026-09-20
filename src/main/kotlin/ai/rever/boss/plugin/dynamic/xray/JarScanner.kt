package ai.rever.boss.plugin.dynamic.xray

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import java.io.InputStream
import java.security.MessageDigest
import java.util.jar.JarFile
import java.util.zip.ZipEntry

/**
 * Static JAR scanner. Reads only what is needed to fill a [JarReport] without loading any class.
 *
 * **Zip-bomb defense (mirrors the host's discipline in plugin-loader, see issue #914 / #1114):**
 *
 * - [MAX_MANIFEST_BYTES] - hard cap on the META-INF/boss-plugin/plugin.json entry. A 1 MiB manifest
 *   is already a smell; refusing anything bigger keeps a malicious JAR from streaming into the JSON
 *   parser. The scanner records [HygieneReport.manifestOversize] instead of silently truncating.
 * - [MAX_ENTRIES] - a JAR with more than [MAX_ENTRIES] entries is suspicious. The scanner stops
 *   reading entries past the cap and records [HygieneReport.entryCountCapped].
 * - [MAX_ENTRY_BYTES] - an entry reporting more than this is refused before any byte is read.
 *   Recorded as [HygieneReport.entryOversize]. This is the read cap that defends the heap; the
 *   host uses the same shape ([readNBytes(MAX + 1)]) to bound a streaming decompress.
 * - [JarFile] is opened with `verify = true` so CRC mismatches throw early, the same way the
 *   host does. A bad CRC never reaches the report.
 * - The scanner never decompresses an entry. It only ever reads the bytes of the manifest entry
 *   (via [JarFile.getInputStream] with a hard `readNBytes` cap) and the JAR's raw bytes for the
 *   SHA-256 (via [java.security.MessageDigest] over a buffered stream).
 *
 * Two reads reach the disk: the JAR's bytes (for the SHA-256) and the manifest entry. Both are
 * bounded. No other entry is read, opened, or written.
 */
class JarScanner {

    /**
     * Scan a JAR file from disk.
     *
     * @param jarPath absolute path to the JAR
     * @return a [JarReport] with [JarReport.readable] false when the JAR could not be opened;
     *  otherwise a fully-populated report.
     */
    fun scan(jarPath: String): JarReport {
        val file = java.io.File(jarPath)
        if (!file.exists() || !file.isFile) {
            return JarReport(
                jarPath = jarPath,
                label = file.name,
                jarSizeBytes = 0L,
                jarSha256 = "",
                readable = false,
                error = "file not found: $jarPath",
            )
        }

        val size = file.length()
        val sha = sha256OfFile(file)

        return try {
            JarFile(file, true).use { jar ->
                scanOpenJar(jar = jar, jarPath = jarPath, label = file.name, size = size, sha = sha)
            }
        } catch (e: Exception) {
            JarReport(
                jarPath = jarPath,
                label = file.name,
                jarSizeBytes = size,
                jarSha256 = sha,
                readable = false,
                error = (e.message ?: e.javaClass.simpleName),
            )
        }
    }

    /**
     * Build a [JarReport] from a manifest that an already-loaded plugin exposed through the host.
     *
     * The host's [ai.rever.boss.plugin.api.PluginLoaderDelegate] gives us [LoadedPluginInfo] for
     * every loaded plugin; that record carries the parsed manifest already, so we reuse it where
     * the in-JAR scan would have parsed it. JAR-level hygiene (entry counts, largest entry) still
     * requires reading the JAR.
     */
    fun fromLoaded(jarPath: String, pluginId: String, displayName: String, version: String): JarReport {
        val file = java.io.File(jarPath)
        if (!file.exists() || !file.isFile) {
            return JarReport(
                jarPath = jarPath,
                label = displayName.ifEmpty { pluginId },
                jarSizeBytes = 0L,
                jarSha256 = "",
                readable = false,
                error = "loaded plugin file not on disk: $jarPath",
                manifest = ManifestReport(
                    present = true,
                    pluginId = pluginId,
                    displayName = displayName,
                    version = version,
                ),
            )
        }
        // Re-use the on-disk scan so hygiene is consistent with file-picked reports.
        val base = scan(jarPath)
        return base.copy(
            label = displayName.ifEmpty { pluginId },
            manifest = base.manifest.copy(
                present = true,
                pluginId = base.manifest.pluginId.ifEmpty { pluginId },
                displayName = base.manifest.displayName.ifEmpty { displayName },
                version = base.manifest.version.ifEmpty { version },
            ),
        )
    }

    private fun scanOpenJar(jar: JarFile, jarPath: String, label: String, size: Long, sha: String): JarReport {
        // Manifest: try canonical first, then legacy.
        val manifestEntry: ZipEntry? = jar.getEntry(MANIFEST_PATH) ?: jar.getEntry(LEGACY_MANIFEST_PATH)
        val hygieneBuilder = HygieneBuilder()
        val manifestReport: ManifestReport = if (manifestEntry != null) {
            readManifestEntry(jar, manifestEntry, hygieneBuilder)
        } else {
            ManifestReport(present = false)
        }

        // Walk every entry to build the hygiene summary. Stop at the cap.
        var entryCount = 0
        var largest = EntryReport()
        var classSeen = false
        var resourceSeen = false
        var entryCountCapped = false
        var entryOversize = false
        val entries = jar.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (entry.name.equals(MANIFEST_PATH, ignoreCase = true) ||
                entry.name.equals(LEGACY_MANIFEST_PATH, ignoreCase = true)
            ) {
                // Manifest already accounted for in the size cap; skip it here.
                continue
            }
            entryCount += 1
            if (entryCount > MAX_ENTRIES) {
                entryCountCapped = true
                break
            }
            val sz = entry.size
            if (sz > MAX_ENTRY_BYTES) {
                entryOversize = true
                // Do not read the bytes; just note the size and continue counting for the cap.
                if (sz > largest.sizeBytes) {
                    largest = EntryReport(name = entry.name, sizeBytes = sz)
                }
                continue
            }
            if (sz > largest.sizeBytes) {
                largest = EntryReport(name = entry.name, sizeBytes = sz)
            }
            if (entry.name.endsWith(".class", ignoreCase = true)) {
                classSeen = true
            } else {
                resourceSeen = true
            }
        }

        val mainPresent = manifestReport.mainClass.isNotEmpty() &&
            hasMainClassEntry(jar, manifestReport.mainClass)

        return JarReport(
            jarPath = jarPath,
            label = label,
            jarSizeBytes = size,
            jarSha256 = sha,
            readable = true,
            manifest = manifestReport.copy(mainClassPresent = mainPresent),
            hygiene = HygieneReport(
                entryCount = entryCount.coerceAtMost(MAX_ENTRIES),
                hasCompiledClasses = classSeen,
                resourceOnly = !classSeen && resourceSeen,
                largestEntry = largest,
                manifestOversize = hygieneBuilder.manifestOversize,
                entryCountCapped = entryCountCapped,
                entryOversize = entryOversize,
            ),
        )
    }

    private fun readManifestEntry(jar: JarFile, entry: ZipEntry, hygiene: HygieneBuilder): ManifestReport {
        val declaredSize = if (entry.size >= 0) entry.size else 0L
        return try {
            jar.getInputStream(entry).use { stream ->
                // Cap the read at MAX_MANIFEST_BYTES. A malicious manifest reports a -1 size and
                // would otherwise read to EOF.
                val bytes = stream.readNBytes(MAX_MANIFEST_BYTES + 1)
                if (bytes.size > MAX_MANIFEST_BYTES) {
                    hygiene.manifestOversize = true
                    ManifestReport(
                        present = true,
                        manifestPath = entry.name,
                        manifestSizeBytes = declaredSize,
                    )
                } else {
                    val text = bytes.toString(Charsets.UTF_8)
                    parseManifest(text = text, declaredSize = declaredSize, path = entry.name)
                }
            }
        } catch (e: Exception) {
            ManifestReport(
                present = true,
                manifestPath = entry.name,
                manifestSizeBytes = declaredSize,
            )
        }
    }

    /**
     * Parse the manifest text into a [ManifestReport].
     *
     * We use a permissive [Json] parser (ignoreUnknownKeys) so a manifest that declares a field we
     * do not model still parses. A malformed JSON returns a present-but-empty [ManifestReport].
     */
    private fun parseManifest(text: String, declaredSize: Long, path: String): ManifestReport {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ManifestReport(present = true, manifestPath = path, manifestSizeBytes = declaredSize)
        val element = try {
            permissiveJson.parseToJsonElement(trimmed)
        } catch (e: Exception) {
            return ManifestReport(present = true, manifestPath = path, manifestSizeBytes = declaredSize, rawText = text)
        }
        if (element !is JsonObject) {
            return ManifestReport(present = true, manifestPath = path, manifestSizeBytes = declaredSize, rawText = text)
        }
        val obj = element

        val panelObj = obj["panel"] as? JsonObject
        val sandboxObj = obj["sandbox"] as? JsonObject

        val type = obj.string("type") ?: ""
        val mainClass = obj.string("mainClass") ?: ""

        val deps = (obj["dependencies"] as? JsonArray)?.let { parseDependencies(it) } ?: emptyList()
        val defined = (obj["definedPermissions"] as? JsonArray)?.let { parseDefinedPermissions(it) } ?: emptyList()
        val required = (obj["requiredPermissions"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?: emptyList()

        return ManifestReport(
            present = true,
            manifestPath = path,
            rawText = text,
            manifestSizeBytes = declaredSize,
            pluginId = obj.string("pluginId") ?: "",
            displayName = obj.string("displayName") ?: "",
            version = obj.string("version") ?: "",
            description = obj.string("description") ?: "",
            author = obj.string("author") ?: "",
            url = obj.string("url") ?: "",
            apiVersion = obj.string("apiVersion") ?: "",
            minApiVersion = obj.string("minApiVersion") ?: "",
            minBossVersion = obj.string("minBossVersion") ?: "",
            mainClass = mainClass,
            mainClassPresent = false, // populated by the caller once the JAR is open
            type = type,
            panel = PanelBlock(
                position = panelObj?.string("position") ?: "",
                priority = panelObj?.int("priority") ?: 0,
                icon = panelObj?.string("icon") ?: "",
                location = panelObj?.string("location") ?: "",
                order = panelObj?.int("order") ?: 0,
                panelId = panelObj?.string("panelId") ?: "",
                displayName = panelObj?.string("displayName") ?: "",
            ),
            sandbox = SandboxBlock(
                maxThreads = sandboxObj?.int("maxThreads") ?: 0,
                maxMemoryMb = sandboxObj?.int("maxMemoryMb") ?: 0,
                enableSandbox = sandboxObj?.boolean("enableSandbox") ?: false,
                heartbeatIntervalMs = sandboxObj?.long("heartbeatIntervalMs") ?: 0L,
                maxRestartAttempts = sandboxObj?.int("maxRestartAttempts") ?: 0,
            ),
            isolationMode = obj.string("isolationMode") ?: "",
            fallback = obj.string("fallback") ?: "",
            stateHolderClass = obj.string("stateHolderClass") ?: "",
            requiredPermissions = required,
            definedPermissions = defined,
            dependencies = deps,
        )
    }

    private fun parseDependencies(arr: JsonArray): List<DependencyReport> = arr.mapNotNull { el ->
        val obj = el as? JsonObject ?: return@mapNotNull null
        DependencyReport(
            pluginId = obj.string("pluginId") ?: return@mapNotNull null,
            version = obj.string("version") ?: "*",
            optional = obj.boolean("optional") ?: false,
        )
    }

    private fun parseDefinedPermissions(arr: JsonArray): List<DefinedPermissionReport> = arr.mapNotNull { el ->
        val obj = el as? JsonObject ?: return@mapNotNull null
        DefinedPermissionReport(
            name = obj.string("name") ?: return@mapNotNull null,
            description = obj.string("description") ?: "",
        )
    }

    private fun hasMainClassEntry(jar: JarFile, mainClass: String): Boolean {
        if (mainClass.isBlank()) return false
        val path = mainClass.replace('.', '/') + ".class"
        return jar.getEntry(path) != null
    }

    private fun sha256OfFile(file: java.io.File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = stream.read(buffer)
                if (n <= 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    // ----- helpers ---------------------------------------------------------

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.longOrNull

    private fun JsonObject.boolean(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.booleanOrNull

    /** Mutable holder shared between [scanOpenJar] and the manifest reader. */
    private class HygieneBuilder(var manifestOversize: Boolean = false)

    companion object {
        /** Canonical plugin manifest path. Mirrors the host's [PluginManifestConstants.MANIFEST_PATH]. */
        const val MANIFEST_PATH: String = "META-INF/boss-plugin/plugin.json"
        /** Legacy manifest path kept for backward compatibility. */
        const val LEGACY_MANIFEST_PATH: String = "META-INF/plugin.json"

        /** Hard cap on the manifest entry: 1 MiB. */
        const val MAX_MANIFEST_BYTES: Int = 1 * 1024 * 1024
        /** Hard cap on the number of JAR entries we read. */
        const val MAX_ENTRIES: Int = 5000
        /** Hard cap on a single entry's uncompressed size: 100 MiB. */
        const val MAX_ENTRY_BYTES: Long = 100L * 1024L * 1024L

        private val permissiveJson = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    }
}

// Suppress detekt on the helper extensions -- they are private and called only from this file.
// These would normally trip FunctionMaxLines / ReturnCount; the single-file class already meets
// the file ceiling.
