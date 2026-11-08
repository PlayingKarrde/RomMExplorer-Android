package com.playingkarrde.rommexplorer.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.playingkarrde.rommexplorer.data.api.Platform
import com.playingkarrde.rommexplorer.data.api.Rom
import com.playingkarrde.rommexplorer.data.api.RommClient
import com.playingkarrde.rommexplorer.data.api.ServerConfig
import com.playingkarrde.rommexplorer.data.api.ServerHolder
import com.playingkarrde.rommexplorer.data.library.Library
import com.playingkarrde.rommexplorer.data.library.ManifestEntry
import com.playingkarrde.rommexplorer.data.storage.SafLibraryStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import kotlin.coroutines.cancellation.CancellationException

data class CatalogState(
    val username: String = "",
    val platforms: List<Platform> = emptyList(),
    val roms: List<Rom> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

/** The connected server and everything we've fetched from it. */
class CatalogRepository(private val scope: CoroutineScope, private val http: OkHttpClient, private val holder: ServerHolder) {
    private val _state = MutableStateFlow(CatalogState())
    val state: StateFlow<CatalogState> = _state.asStateFlow()

    @Volatile var client: RommClient? = null
        private set
    private var loadJob: Job? = null

    /** Verifies the server and credentials. Throws a [com.playingkarrde.rommexplorer.data.api.RommException] with a user-facing message. */
    suspend fun connect(cfg: ServerConfig): String {
        val previous = holder.config
        holder.config = cfg
        val c = RommClient(cfg, http)
        val user = try {
            c.currentUser()
        } catch (e: Throwable) {
            holder.config = previous
            throw e
        }
        // A load still running for the previous server would otherwise mix its games into the new state.
        loadJob?.cancel()
        client = c
        _state.value = CatalogState(username = user)
        return user
    }

    /** Uses saved settings without a network round trip (e.g. when the download worker restarts). */
    fun restore(cfg: ServerConfig) {
        if (client != null) return
        holder.config = cfg
        client = RommClient(cfg, http)
    }

    fun disconnect() {
        loadJob?.cancel()
        client = null
        holder.config = null
        _state.value = CatalogState()
    }

    fun refresh() {
        val c = client ?: return
        loadJob?.cancel()
        loadJob = scope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val platforms = c.platforms().sortedBy { it.label.lowercase() }
                // First load: show games page by page. Reload: keep the current list until the new one is
                // complete, so the grid (and the focused card) doesn't vanish underneath the user.
                val streaming = _state.value.roms.isEmpty()
                if (streaming) _state.update { it.copy(platforms = platforms) }
                val acc = ArrayList<Rom>()
                c.allRoms().collect { page ->
                    acc.addAll(page)
                    if (streaming) _state.update { it.copy(roms = ArrayList(acc)) }
                }
                _state.update { it.copy(platforms = platforms, roms = acc, loading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.update { it.copy(loading = false, error = e.message ?: "Couldn't load your library from the server.") }
            }
        }
    }

    fun rom(id: Long): Rom? = _state.value.roms.firstOrNull { it.id == id }
}

data class LibraryState(
    val uri: String = "",
    val folderName: String = "",
    val entries: Map<Long, ManifestEntry> = emptyMap(),
    val error: String? = null,
) {
    val ready: Boolean get() = uri.isNotEmpty() && error == null
}

/** The library folder the user picked, and its manifest. */
class LibraryRepository(private val context: Context) {
    private val _state = MutableStateFlow(LibraryState())
    val state: StateFlow<LibraryState> = _state.asStateFlow()
    private val mutex = Mutex()

    @Volatile var library: Library? = null
        private set

    suspend fun open(uriString: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            // Already open (e.g. a new activity re-reading settings while a download runs): keep the
            // instance, so an in-flight install can still record itself.
            if (uriString.isNotEmpty() && library != null && _state.value.uri == uriString && _state.value.error == null) {
                return@withLock
            }
            library = null
            if (uriString.isEmpty()) {
                _state.value = LibraryState()
                return@withLock
            }
            val uri = Uri.parse(uriString)
            val name = uri.lastPathSegment?.substringAfterLast(':')?.ifEmpty { "Internal storage" } ?: uriString
            val hasPermission = context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }
            if (!hasPermission) {
                _state.value = LibraryState(uriString, name, error = "Access to the library folder was lost. Pick it again in Settings.")
                return@withLock
            }
            try {
                val lib = Library(SafLibraryStorage(context, uri))
                lib.open()
                library = lib
                _state.value = LibraryState(uriString, name, lib.entries)
            } catch (e: Throwable) {
                _state.value = LibraryState(uriString, name, error = (e.message ?: "The library folder couldn't be opened.") +
                    " Fix or remove the file, or pick a different folder in Settings.")
            }
        }
    }

    fun takePermission(uri: Uri) {
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }

    /** Runs [block] on the IO dispatcher against the open library, then publishes the new entries. */
    suspend fun <T> use(block: (Library) -> T): T = withContext(Dispatchers.IO) {
        val lib = library ?: throw IllegalStateException("Pick a library folder in Settings first.")
        try {
            block(lib)
        } finally {
            _state.update { it.copy(entries = lib.entries) }
        }
    }

    fun publish() {
        library?.let { lib -> _state.update { it.copy(entries = lib.entries) } }
    }
}
