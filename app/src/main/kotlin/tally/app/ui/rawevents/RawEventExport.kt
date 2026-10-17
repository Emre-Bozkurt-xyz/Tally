package tally.app.ui.rawevents

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tally.app.data.RawEventRepository
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Writes raw events to a JSON file in the cache and returns a shareable content URI. */
suspend fun exportRawEvents(context: Context, repository: RawEventRepository, everything: Boolean): Uri =
    withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val json = repository.exportJson(now, everything)
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        // Only the latest export is kept around.
        dir.listFiles()?.forEach { it.delete() }
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(now))
        val file = File(dir, "tally-raw-events-$stamp.json")
        file.writeText(json)
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

fun shareJson(context: Context, uri: Uri) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/json"
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri(null, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Export raw events"))
}
