package com.playingkarrde.rommexplorer

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.playingkarrde.rommexplorer.connect.ConnectForm
import com.playingkarrde.rommexplorer.data.api.Rom
import com.playingkarrde.rommexplorer.data.api.ServerConfig
import com.playingkarrde.rommexplorer.data.api.normalizeBase
import com.playingkarrde.rommexplorer.data.settings.AppSettings
import com.playingkarrde.rommexplorer.data.library.FolderNaming
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface Screen {
    data object Starting : Screen
    data object Connect : Screen
    data object Browse : Screen
    data class Details(val romId: Long) : Screen
    data object Downloads : Screen
    data object Settings : Screen
}

class AppViewModel(val c: AppContainer) : ViewModel() {
    val stack = mutableStateListOf<Screen>(Screen.Starting)
    val screen: Screen get() = stack.last()

    var connectError by mutableStateOf<String?>(null)
    var message by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
    var confirmUninstall by mutableStateOf<Rom?>(null)

    val settings: StateFlow<AppSettings?> = c.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        viewModelScope.launch {
            c.settings.settings.map { it.libraryUri }.distinctUntilChanged().collect { c.library.open(it) }
        }
        viewModelScope.launch { startup() }
    }

    private suspend fun startup() {
        val s = c.settings.current()
        val cfg = s.serverConfig
        if (cfg == null) {
            replaceAll(Screen.Connect)
            return
        }
        try {
            c.catalog.connect(cfg)
            onConnected()
        } catch (e: Throwable) {
            connectError = e.message
            replaceAll(Screen.Connect)
        }
    }

    private fun onConnected() {
        c.catalog.refresh()
        replaceAll(Screen.Browse)
        if (c.downloads.hasQueued) c.downloads.startWorker()
    }

    /** Returns an error message, or null on success. */
    suspend fun connect(form: ConnectForm): String? {
        val cfg = ServerConfig(normalizeBase(form.url), AppSettings(username = form.username, password = form.password, token = form.token).auth, form.allowSelfSigned)
        return try {
            c.catalog.connect(cfg)
            c.settings.saveServer(form.url, form.username, form.password, form.token, form.allowSelfSigned)
            connectError = null
            onConnected()
            null
        } catch (e: Throwable) {
            e.message ?: "Couldn't connect."
        }
    }

    fun signOut() = viewModelScope.launch {
        c.downloads.cancelAll()
        c.settings.signOut()
        c.catalog.disconnect()
        replaceAll(Screen.Connect)
    }

    fun navigate(s: Screen) {
        if (stack.last() != s) stack.add(s)
    }

    /** Returns false when there's nowhere to go back to. */
    fun back(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    private fun replaceAll(s: Screen) {
        stack.clear()
        stack.add(s)
    }

    fun onFolderPicked(uri: Uri) = viewModelScope.launch {
        runCatching { c.library.takePermission(uri) }.onFailure {
            message = "Couldn't get permanent access to that folder. Try a different one."
            return@launch
        }
        c.settings.setLibraryUri(uri.toString())
    }

    fun uninstall(rom: Rom) = viewModelScope.launch {
        try {
            c.library.use { it.uninstall(rom.id) }
            message = "Uninstalled ${rom.title}"
        } catch (e: Throwable) {
            message = "Couldn't uninstall ${rom.title}: ${e.message}"
        }
    }

    private fun tool(name: String, block: suspend () -> String) = viewModelScope.launch {
        if (busy) return@launch
        busy = true
        message = "$name…"
        message = try {
            block()
        } catch (e: Throwable) {
            "$name failed: ${e.message}"
        } finally {
            busy = false
        }
    }

    fun detectExisting() = tool("Detecting existing files") {
        val roms = c.catalog.state.value.roms
        if (roms.isEmpty()) return@tool "The game list hasn't loaded yet. Try again in a moment."
        val naming = c.settings.current().folderNaming
        val n = c.library.use { it.adoptExisting(roms, naming) }
        if (n == 0) "No existing games found in the library folder." else "Added $n existing game${if (n == 1) "" else "s"} to the library."
    }

    fun verifyInstalled() = tool("Verifying") {
        val n = c.library.use { it.verify() }
        if (n == 0) "All installed games are present." else "Forgot $n game${if (n == 1) "" else "s"} whose files were missing."
    }

    fun reorganize() = tool("Reorganizing") {
        val naming = c.settings.current().folderNaming
        val r = c.library.use { it.reorganize(naming) }
        "Moved ${r.moved}, skipped ${r.skipped}."
    }

    fun setNaming(n: FolderNaming) = viewModelScope.launch { c.settings.setFolderNaming(n) }
    fun setExtract(v: Boolean) = viewModelScope.launch { c.settings.setExtractMulti(v) }
    fun setSwapAB(v: Boolean) = viewModelScope.launch { c.settings.setSwapAB(v) }
    fun setKeepScreenOn(v: Boolean) = viewModelScope.launch { c.settings.setKeepScreenOn(v) }
}
