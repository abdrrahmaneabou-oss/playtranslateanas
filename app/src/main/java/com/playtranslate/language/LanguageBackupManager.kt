package com.playtranslate.language

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.playtranslate.BuildConfig
import com.playtranslate.ocr.registry.OcrPackModelHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * User-selected SAF folder backups. A DocumentsProvider folder stays outside
 * app-private noBackupFilesDir; after uninstall/signature-changing reinstall
 * the user re-grants access to that folder and selects the saved ZIPs.
 *
 * Archives cover source dictionaries and (when present) the Arabic target
 * gloss pack and downloaded Arabic Paddle recognizer. Bundled unified OCR
 * models are already inside the APK. ML Kit's own private models are managed
 * by Google, not by LanguagePackStore, and cannot be copied here.
 */
object LanguageBackupManager {
    private const val PREFS = "language_pack_external_backup"
    private const val TREE_KEY = "saf_tree_uri"
    private const val PROMPT_KEY = "folder_prompt_seen"
    private const val HEADER = "PLAYTRANSLATE-BACKUP-V1"
    private const val INDEX = "backup-index.tsv"
    private const val TAG = "LanguageBackup"
    private const val MAX_ARCHIVE_BYTES = 700L * 1024 * 1024
    private const val MAX_UNPACKED_BYTES = 1000L * 1024 * 1024
    private const val MAX_ENTRIES = 1000
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val exportMutex = Mutex()

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun shouldPrompt(ctx: Context): Boolean = !prefs(ctx).getBoolean(PROMPT_KEY, false)
    fun markPrompted(ctx: Context) {
        prefs(ctx).edit().putBoolean(PROMPT_KEY, true).apply()
    }

    fun selectedFolder(ctx: Context): Uri? {
        val saved = prefs(ctx).getString(TREE_KEY, null) ?: return null
        val uri = runCatching { Uri.parse(saved) }.getOrNull() ?: return null
        return uri.takeIf { selected ->
            ctx.contentResolver.persistedUriPermissions.any {
                it.uri == selected && it.isReadPermission && it.isWritePermission
            }
        }
    }

    fun grantFolder(ctx: Context, uri: Uri): Boolean = try {
        ctx.contentResolver.takePersistableUriPermission(
            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        prefs(ctx).edit().putString(TREE_KEY, uri.toString())
            .putBoolean(PROMPT_KEY, true).apply()
        queueExport(ctx, SourceLangId.EN)
        queueExport(ctx, SourceLangId.AR)
        true
    } catch (e: Exception) {
        Log.w(TAG, "Could not persist SAF permission", e)
        false
    }

    /** Best-effort and serialized: no download waits for ZIP compression. */
    fun queueExport(ctx: Context, id: SourceLangId) {
        if (id != SourceLangId.EN && id != SourceLangId.AR) return
        if (selectedFolder(ctx) == null) return
        val app = ctx.applicationContext
        scope.launch {
            exportMutex.withLock {
                runCatching { exportNow(app, id) }
                    .onFailure { Log.w(TAG, "Auto-backup " + id.code + " failed", it) }
            }
        }
    }

    /** Export source+related optional packs into a new archive, then replace
     *  the previously named backup only after the new document was written. */
    suspend fun exportNow(ctx: Context, id: SourceLangId): String =
        withContext(Dispatchers.IO) {
            require(id == SourceLangId.EN || id == SourceLangId.AR)
            val tree = selectedFolder(ctx) ?: error("Select a backup folder first")
            val root = DocumentFile.fromTreeUri(ctx, tree)
                ?.takeIf { it.isDirectory && it.canWrite() }
                ?: error("The selected folder is unavailable or read-only")

            val sources = mutableListOf<Pair<String, File>>()
            if (LanguagePackStore.isInstalled(ctx, id)) {
                sources += ("source/" + id.code) to LanguagePackStore.dirFor(ctx, id)
            }
            if (id == SourceLangId.AR) {
                if (LanguagePackStore.isTargetInstalled(ctx, "ar")) {
                    sources += "target/ar" to LanguagePackStore.targetDirFor(ctx, "ar")
                }
                val ocr = OcrPackModelHelper("paddle-rec-arabic")
                if (ocr.isInstalled(ctx)) {
                    sources += "ocr/paddle-rec-arabic" to ocr.file(ctx)
                }
            }
            if (sources.isEmpty()) error("No installed " + id.displayName() + " pack to save")

            val local = File(ctx.cacheDir, "pt-backup-" + id.code + "-" + System.nanoTime() + ".zip")
            val backupName = "PlayTranslate-" + id.code + "-backup.zip"
            val tempName = "PlayTranslate-" + id.code + "-backup-pending.zip"
            try {
                val hashes = ArrayList<String>()
                ZipOutputStream(FileOutputStream(local).buffered()).use { zip ->
                    for ((prefix, directory) in sources) {
                        val files = directory.walkTopDown().filter { it.isFile }.sortedBy { it.path }
                        for (file in files) {
                            val relative = file.relativeTo(directory).invariantSeparatorsPath
                            val name = prefix + "/" + relative
                            require(safeName(name, id)) { "Invalid pack path: " + name }
                            zip.putNextEntry(ZipEntry(name))
                            val digest = MessageDigest.getInstance("SHA-256")
                            var size = 0L
                            FileInputStream(file).buffered().use { input ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    val n = input.read(buffer)
                                    if (n < 0) break
                                    digest.update(buffer, 0, n)
                                    zip.write(buffer, 0, n)
                                    size += n
                                }
                            }
                            zip.closeEntry()
                            hashes += name + "\t" + size + "\t" + digest.hex()
                        }
                    }
                    zip.putNextEntry(ZipEntry(INDEX))
                    zip.write((HEADER + "\t" + id.code + "\n" + hashes.joinToString("\n") + "\n").toByteArray())
                    zip.closeEntry()
                }
                if (local.length() > MAX_ARCHIVE_BYTES) error("Backup exceeds size limit")
                root.findFile(tempName)?.delete()
                val pending = root.createFile("application/zip", tempName)
                    ?: error("Could not create backup in the chosen folder")
                try {
                    ctx.contentResolver.openOutputStream(pending.uri, "wt")?.use { output ->
                        local.inputStream().buffered().use { it.copyTo(output) }
                    } ?: error("Could not write backup to chosen folder")
                } catch (e: Exception) {
                    pending.delete()
                    throw e
                }
                // An interrupted write never destroys the old good backup.
                val previous = root.findFile(backupName)
                if (previous != null && !previous.delete()) {
                    error("Could not replace old backup; new archive is at " + tempName)
                }
                if (!pending.renameTo(backupName)) {
                    error("Backup saved as " + tempName + "; provider could not rename it")
                }
                Log.i(TAG, "Saved " + backupName + " to SAF folder")
                backupName
            } finally {
                local.delete()
            }
        }

    /** Import from an externally picked ZIP, validate all archived hashes and
     *  installed-pack schemas before any swap; never trust the ZIP's paths. */
    suspend fun restore(ctx: Context, id: SourceLangId, picked: Uri): String =
        withContext(Dispatchers.IO) {
            require(id == SourceLangId.EN || id == SourceLangId.AR)
            val app = ctx.applicationContext
            val zipped = File(app.cacheDir, "pt-import-" + System.nanoTime() + ".zip")
            val stage = File(LanguagePackStore.rootDir(app), ".restore-" + System.nanoTime())
            try {
                app.contentResolver.openInputStream(picked)?.buffered().use { input ->
                    requireNotNull(input) { "Selected file cannot be opened" }
                    FileOutputStream(zipped).buffered().use { out ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            total += n
                            if (total > MAX_ARCHIVE_BYTES) error("Backup file is too large")
                            out.write(buffer, 0, n)
                        }
                    }
                }
                stage.mkdirs()
                ZipFile(zipped).use { zip ->
                    val infoEntry = zip.getEntry(INDEX) ?: error("Not a PlayTranslate language backup")
                    val index = zip.getInputStream(infoEntry).bufferedReader().use { it.readText() }
                        .lineSequence().filter { it.isNotBlank() }.toList()
                    if (index.firstOrNull() != HEADER + "\t" + id.code) {
                        error("Wrong language or unsupported backup format")
                    }
                    val items = index.drop(1).map { line ->
                        val parts = line.split('\t')
                        if (parts.size != 3) error("Invalid backup index")
                        val name = parts[0]
                        val size = parts[1].toLongOrNull() ?: error("Invalid file size")
                        if (!safeName(name, id) || size < 0 || size > MAX_UNPACKED_BYTES ||
                            !parts[2].matches(Regex("[0-9a-fA-F]{64}"))) error("Invalid backup entry")
                        Triple(name, size, parts[2])
                    }
                    if (items.isEmpty() || items.size > MAX_ENTRIES ||
                        items.map { it.first }.toSet().size != items.size) {
                        error("Empty or duplicate backup entries")
                    }
                    val archiveFiles = zip.entries().asSequence()
                        .filter { !it.isDirectory && it.name != INDEX }.map { it.name }.toSet()
                    if (archiveFiles != items.map { it.first }.toSet()) error("Archive contents do not match backup index")
                    var total = 0L
                    for ((name, expectedSize, expectedHash) in items) {
                        val dest = File(stage, name)
                        if (!PackIntegrity.isInside(stage, dest)) error("Unsafe archive path")
                        dest.parentFile?.mkdirs()
                        val entry = zip.getEntry(name) ?: error("Missing file: " + name)
                        val digest = MessageDigest.getInstance("SHA-256")
                        var length = 0L
                        zip.getInputStream(entry).buffered().use { input ->
                            dest.outputStream().buffered().use { output ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    val n = input.read(buffer)
                                    if (n < 0) break
                                    length += n
                                    total += n
                                    if (length > expectedSize || total > MAX_UNPACKED_BYTES)
                                        error("Backup is larger than declared")
                                    digest.update(buffer, 0, n)
                                    output.write(buffer, 0, n)
                                }
                            }
                        }
                        if (length != expectedSize || !digest.hex().equals(expectedHash, ignoreCase = true)) {
                            error("Backup checksum mismatch: " + name)
                        }
                    }
                }

                val source = File(stage, "source/" + id.code)
                val target = File(stage, "target/ar")
                val model = File(stage, "ocr/paddle-rec-arabic")
                if (!source.exists() && !target.exists()) error("Backup contains no language data")
                if (source.exists()) validatePack(app, source, id.code, id.code, false)
                if (target.exists()) validatePack(app, target, "target-ar", "ar", true)
                if (model.exists()) validateOcr(app, model)

                // Validate *all* components first. Promote each directory on
                // the same filesystem using the existing rollback-safe swap.
                if (source.exists()) {
                    SourceLanguageEngines.releaseForPack(id.packId)
                    LanguagePackStore.safeSwap(source, LanguagePackStore.dirFor(app, id))
                }
                if (target.exists()) {
                    TargetGlossDatabaseProvider.release("ar")
                    LanguagePackStore.safeSwap(target, LanguagePackStore.targetDirFor(app, "ar"))
                }
                if (model.exists()) {
                    val dest = OcrPackModelHelper("paddle-rec-arabic").file(app)
                    LanguagePackStore.safeSwap(model, dest)
                }
                "Restored " + id.displayName() + " language files. Select the language in setup if needed."
            } finally {
                zipped.delete()
                stage.deleteRecursively()
            }
        }

    private suspend fun validatePack(
        ctx: Context, directory: File, catalogKey: String, languageCode: String, target: Boolean
    ) {
        val manifest = LanguagePackManifestIO.read(File(directory, "manifest.json"))
            ?: error("Pack is missing its manifest")
        if (manifest.schemaVersion > 1 || manifest.appMinVersion > BuildConfig.VERSION_CODE)
            error("Pack requires a newer PlayTranslate version")
        if (!target && manifest.langId != languageCode) error("Pack has the wrong language")
        val entry = LanguagePackCatalogLoader.entryForKey(ctx, catalogKey)
        if (entry != null && manifest.packVersion < entry.packVersion)
            error("Backup is older than this app's pack version; download an updated pack")
        for (part in manifest.files) {
            val file = File(directory, part.path)
            if (!PackIntegrity.isInside(directory, file) || !file.isFile || file.length() != part.size)
                error("Backup contains an incomplete language pack")
            if (part.sha256 != null &&
                !PackIntegrity.sha256Hex(file).equals(part.sha256, ignoreCase = true))
                error("Language pack file failed SHA-256 verification")
        }
        if (target) {
            for (name in listOf("index.fst", "data.bin", "strings.bin")) {
                if (!File(directory, name).isFile) error("Missing target glossary file: " + name)
            }
        } else {
            if (!File(directory, "dict.sqlite").isFile) error("Missing language dictionary")
        }
    }

    private suspend fun validateOcr(ctx: Context, directory: File) {
        val entry = LanguagePackCatalogLoader.entryForKey(ctx, "paddle-rec-arabic")
            ?: error("Arabic OCR model is not supported by this app version")
        val files = entry.files ?: error("Arabic OCR model lacks catalog hashes")
        val sentinel = File(directory, ".sentinel")
        val expected = com.playtranslate.translation.llm.MultiFileSha.aggregate(files)
        if (!sentinel.isFile || !sentinel.readText().trim().equals(expected, ignoreCase = true))
            error("OCR model version does not match the app catalog")
        for (part in files) {
            val file = File(directory, part.path)
            if (!PackIntegrity.isInside(directory, file) || !file.isFile || file.length() != part.size)
                error("Arabic OCR model is incomplete")
            if (!PackIntegrity.sha256Hex(file).equals(part.sha256, ignoreCase = true))
                error("Arabic OCR model checksum mismatch")
        }
    }

    private fun safeName(name: String, id: SourceLangId): Boolean {
        if (name.contains('\\') || name.startsWith('/') || name == INDEX) return false
        val segments = name.split('/')
        if (segments.any { it.isBlank() || it == "." || it == ".." }) return false
        return name.startsWith("source/" + id.code + "/") ||
            (id == SourceLangId.AR &&
                (name.startsWith("target/ar/") || name.startsWith("ocr/paddle-rec-arabic/")))
    }

    private fun MessageDigest.hex(): String =
        digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
