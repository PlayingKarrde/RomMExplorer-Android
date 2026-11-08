package com.playingkarrde.rommexplorer.downloads

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.playingkarrde.rommexplorer.data.CatalogRepository
import com.playingkarrde.rommexplorer.data.LibraryRepository
import com.playingkarrde.rommexplorer.data.api.Rom
import com.playingkarrde.rommexplorer.data.api.RommClient
import com.playingkarrde.rommexplorer.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.Request
import java.io.File
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext

sealed interface DownloadStatus {
    data object Queued : DownloadStatus
    data class Downloading(val bytes: Long, val total: Long, val bytesPerSec: Double) : DownloadStatus {
        val fraction: Float get() = if (total > 0) (bytes.toDouble() / total).toFloat().coerceIn(0f, 1f) else 0f
        val etaSeconds: Long? get() = if (bytesPerSec > 1 && total > bytes) ((total - bytes) / bytesPerSec).toLong() else null
    }
    data object Extracting : DownloadStatus
    data object Done : DownloadStatus
    data class Failed(val message: String) : DownloadStatus
    data object Cancelled : DownloadStatus
}

data class DownloadItem(val rom: Rom, val status: DownloadStatus) {
    val active: Boolean get() = status is DownloadStatus.Queued || status is DownloadStatus.Downloading || status is DownloadStatus.Extracting
}

/**
 * Single source of truth for downloads. The UI observes [items]; [DownloadWorker] calls [runQueue],
 * which runs at most [MAX_PARALLEL] downloads at once. The queue is persisted so it survives process death.
 */
class DownloadRepository(
    private val context: Context,
    private val scope: CoroutineScope,
    private val catalog: CatalogRepository,
    private val library: LibraryRepository,
    private val settings: SettingsRepository,
) {
    private val lock = Any()
    private val map = LinkedHashMap<Long, DownloadItem>()
    private val jobs = HashMap<Long, Job>()
    private val _items = MutableStateFlow<List<DownloadItem>>(emptyList())
    val items: StateFlow<List<DownloadItem>> = _items.asStateFlow()
    private val queueFile = File(context.filesDir, "download-queue.json")
    private val json = Json { ignoreUnknownKeys = true }

    init {
        restore()
    }

    private fun publish() {
        _items.value = map.values.toList()
    }

    private fun set(id: Long, status: DownloadStatus) = synchronized(lock) {
        val cur = map[id] ?: return@synchronized
        map[id] = cur.copy(status = status)
        publish()
        if (status !is DownloadStatus.Downloading) persist()
    }

    fun enqueue(roms: List<Rom>) {
        synchronized(lock) {
            val installed = library.state.value.entries
            for (rom in roms) {
                if (rom.id in installed) continue
                val cur = map[rom.id]
                if (cur != null && cur.active) continue
                map.remove(rom.id)
                map[rom.id] = DownloadItem(rom, DownloadStatus.Queued)
            }
            publish()
            persist()
        }
        startWorker()
    }

    fun retry(id: Long) {
        val rom = synchronized(lock) { map[id]?.rom } ?: return
        enqueue(listOf(rom))
    }

    fun cancel(id: Long) {
        synchronized(lock) {
            jobs[id]?.cancel()
            if (map[id]?.status == DownloadStatus.Queued) set(id, DownloadStatus.Cancelled)
        }
    }

    fun cancelAll() = synchronized(lock) {
        map.values.filter { it.active }.forEach { cancel(it.rom.id) }
    }

    fun clearFinished() = synchronized(lock) {
        map.values.removeAll { !it.active }
        publish()
    }

    fun startWorker() {
        val req = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, req)
    }

    private fun takeNext(): DownloadItem? = synchronized(lock) {
        val next = map.values.firstOrNull { it.status == DownloadStatus.Queued } ?: return null
        map[next.rom.id] = next.copy(status = DownloadStatus.Downloading(0, next.rom.fsSizeBytes, 0.0))
        publish()
        next
    }

    val hasQueued: Boolean get() = synchronized(lock) { map.values.any { it.status == DownloadStatus.Queued } }

    /** Runs until the queue is empty. Cancelling the caller cancels every running download. */
    suspend fun runQueue() = coroutineScope {
        repeat(MAX_PARALLEL) {
            launch {
                while (true) {
                    val item = takeNext() ?: break
                    val job = launch(start = kotlinx.coroutines.CoroutineStart.LAZY) { runOne(item.rom) }
                    synchronized(lock) { jobs[item.rom.id] = job }
                    job.join()
                    synchronized(lock) {
                        jobs.remove(item.rom.id)
                        if (map[item.rom.id]?.active == true) set(item.rom.id, DownloadStatus.Cancelled)
                    }
                }
            }
        }
    }

    /** Marks anything still marked as running as cancelled (the worker was stopped). */
    fun onWorkerStopped() = synchronized(lock) {
        map.values.filter { it.status !is DownloadStatus.Queued && it.active }.forEach { set(it.rom.id, DownloadStatus.Cancelled) }
    }

    private suspend fun runOne(rom: Rom) {
        try {
            val client = catalog.client ?: throw IOException("Not connected to a server. Open the app and sign in, then retry.")
            val lib = library.library ?: throw IOException("No library folder. Pick one in Settings, then retry.")
            val s = settings.current()
            val job = coroutineContext.job
            val resp = client.execute(Request.Builder().url(client.contentUrl(rom)).get().build())
            val entry = resp.use { r ->
                RommClient.checkResponse(r)
                val body = r.body ?: throw IOException("The server sent an empty response.")
                val total = body.contentLength().takeIf { it > 0 } ?: rom.fsSizeBytes
                withContext(Dispatchers.IO) {
                    lib.install(
                        rom, s.folderNaming, s.extractMultiFile,
                        isCancelled = { !job.isActive },
                        onExtracting = { set(rom.id, DownloadStatus.Extracting) },
                    ) { out ->
                        val input = body.byteStream()
                        val buf = ByteArray(256 * 1024)
                        var done = 0L
                        var lastTime = System.nanoTime()
                        var lastBytes = 0L
                        var speed = 0.0
                        while (true) {
                            if (!job.isActive) throw CancellationException("Cancelled")
                            val n = try {
                                input.read(buf)
                            } catch (e: IOException) {
                                if (!job.isActive) throw CancellationException("Cancelled")
                                throw IOException("The download was interrupted (${e.message}). Check your connection and retry.", e)
                            }
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            val t = System.nanoTime()
                            val dt = (t - lastTime) / 1e9
                            if (dt >= 0.3) {
                                val inst = (done - lastBytes) / dt
                                speed = if (speed == 0.0) inst else speed * 0.7 + inst * 0.3
                                lastTime = t
                                lastBytes = done
                                set(rom.id, DownloadStatus.Downloading(done, maxOf(total, done), speed))
                            }
                        }
                    }
                }
            }
            library.use { it.record(entry) }
            set(rom.id, DownloadStatus.Done)
        } catch (e: CancellationException) {
            set(rom.id, DownloadStatus.Cancelled)
            throw e
        } catch (e: Throwable) {
            coroutineContext.ensureActive()
            set(rom.id, DownloadStatus.Failed(e.message ?: e.javaClass.simpleName))
        }
    }

    private fun persist() {
        val queued = map.values.filter { it.active }.map { it.rom.raw }
        scope.launch(Dispatchers.IO) {
            runCatching { queueFile.writeText(json.encodeToString(JsonArray.serializer(), JsonArray(queued))) }
        }
    }

    private fun restore() {
        val arr = runCatching { json.parseToJsonElement(queueFile.readText()) as? JsonArray }.getOrNull() ?: return
        synchronized(lock) {
            for (el in arr) {
                val rom = runCatching { Rom.parse(el as JsonObject) }.getOrNull() ?: continue
                map[rom.id] = DownloadItem(rom, DownloadStatus.Queued)
            }
            publish()
        }
    }

    companion object {
        const val MAX_PARALLEL = 2
        const val WORK_NAME = "downloads"
    }
}
