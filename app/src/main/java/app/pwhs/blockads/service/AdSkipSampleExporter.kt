package app.pwhs.blockads.service

import android.content.Context
import androidx.core.content.FileProvider
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Packages the self-learning samples (filesDir/ad_learn/ JSON samples) into a
 * single zip under cacheDir/logs (a FileProvider-covered path) and hands
 * back a shareable content URI. Completes the learning loop for non-root
 * users: collect on-device -> export -> offline rule synthesis.
 */
object AdSkipSampleExporter {

    private const val SAMPLES_DIR = "ad_learn"

    fun hasSamples(context: Context): Boolean {
        val dir = File(context.filesDir, SAMPLES_DIR)
        return dir.isDirectory && dir.listFiles()?.any { it.length() > 0 } == true
    }

    /** Returns a shareable URI or null when there is nothing to export. */
    fun export(context: Context): android.net.Uri? {
        return try {
            val dir = File(context.filesDir, SAMPLES_DIR)
            val files = dir.listFiles()?.filter { it.length() > 0 } ?: return null
            if (files.isEmpty()) return null
            val outDir = File(context.cacheDir, "logs").apply { mkdirs() }
            val zip = File(outDir, "ad_learn_${System.currentTimeMillis()}.zip")
            ZipOutputStream(java.io.FileOutputStream(zip)).use { zos ->
                for (f in files.sortedBy { it.name }) {
                    zos.putNextEntry(ZipEntry(f.name))
                    f.inputStream().use { it.copyTo(zos, 8 * 1024) }
                    zos.closeEntry()
                }
            }
            val authority = "${context.packageName}.fileprovider"
            FileProvider.getUriForFile(context, authority, zip)
        } catch (e: Exception) {
            timber.log.Timber.e(e, "sample export failed")
            null
        }
    }
}
