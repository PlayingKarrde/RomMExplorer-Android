package com.playingkarrde.rommexplorer.browse

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.playingkarrde.rommexplorer.AppContainer
import com.playingkarrde.rommexplorer.data.CatalogState
import com.playingkarrde.rommexplorer.data.LibraryState
import com.playingkarrde.rommexplorer.data.api.Rom
import com.playingkarrde.rommexplorer.data.library.ManifestEntry
import com.playingkarrde.rommexplorer.data.settings.InstallFilter
import com.playingkarrde.rommexplorer.data.settings.SortKey
import com.playingkarrde.rommexplorer.downloads.DownloadItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PlatformTab(val id: Long?, val label: String, val count: Int)

data class BrowseFilters(
    val platformId: Long? = null,
    val install: InstallFilter = InstallFilter.All,
    val query: String = "",
)

data class BrowseUi(
    val tabs: List<PlatformTab> = emptyList(),
    val roms: List<Rom> = emptyList(),
    val installed: Map<Long, ManifestEntry> = emptyMap(),
    val downloads: Map<Long, DownloadItem> = emptyMap(),
    val filters: BrowseFilters = BrowseFilters(),
    val sort: SortKey = SortKey.Name,
    val listView: Boolean = false,
    val catalog: CatalogState = CatalogState(),
    val library: LibraryState = LibraryState(),
)

class BrowseViewModel(private val c: AppContainer) : ViewModel() {
    val filters = MutableStateFlow(BrowseFilters())

    /** Kept here so scroll position survives a trip to details and back. */
    val gridState = LazyGridState()
    val listState = LazyListState()
    var lastFocusedId: Long? = null
    var pendingFocusId by mutableStateOf<Long?>(null)

    private val settings = c.settings.settings

    val ui: StateFlow<BrowseUi> = combine(
        c.catalog.state, c.library.state, c.downloads.items, filters, settings,
    ) { catalog, library, downloads, f, s ->
        val counts = catalog.roms.groupingBy { it.platformId }.eachCount()
        val tabs = buildList {
            add(PlatformTab(null, "All platforms", catalog.roms.size))
            catalog.platforms.filter { (counts[it.id] ?: 0) > 0 }.forEach { add(PlatformTab(it.id, it.label, counts[it.id] ?: 0)) }
            // Platforms the server listed nothing for but ROMs reference anyway.
            val known = catalog.platforms.map { it.id }.toSet()
            catalog.roms.filter { it.platformId !in known }.distinctBy { it.platformId }
                .forEach { add(PlatformTab(it.platformId, it.platformLabel.ifBlank { "Unknown" }, counts[it.platformId] ?: 0)) }
        }
        val q = f.query.trim().lowercase()
        val installed = library.entries
        val filtered = catalog.roms.asSequence()
            .filter { f.platformId == null || it.platformId == f.platformId }
            .filter {
                when (f.install) {
                    InstallFilter.All -> true
                    InstallFilter.Installed -> it.id in installed
                    InstallFilter.NotInstalled -> it.id !in installed
                }
            }
            .filter { q.isEmpty() || it.title.lowercase().contains(q) || it.fsName.lowercase().contains(q) || it.platformLabel.lowercase().contains(q) }
            .toList()
        val sorted = when (s.sort) {
            SortKey.Name -> filtered.sortedBy { it.title.lowercase() }
            SortKey.Platform -> filtered.sortedWith(compareBy({ it.platformLabel.lowercase() }, { it.title.lowercase() }))
            SortKey.Size -> filtered.sortedByDescending { installed[it.id]?.sizeBytes ?: it.fsSizeBytes }
            SortKey.ReleaseDate -> filtered.sortedWith(compareByDescending<Rom> { it.releaseTimestamp ?: Long.MIN_VALUE }.thenBy { it.title.lowercase() })
            SortKey.RecentlyInstalled -> filtered.sortedWith(compareByDescending<Rom> { installed[it.id]?.installedAt ?: Long.MIN_VALUE }.thenBy { it.title.lowercase() })
        }
        BrowseUi(tabs, sorted, installed, downloads.associateBy { it.rom.id }, f, s.sort, s.listView, catalog, library)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), BrowseUi())

    fun selectPlatform(id: Long?) {
        filters.value = filters.value.copy(platformId = id)
        viewModelScope.launch {
            gridState.scrollToItem(0)
            listState.scrollToItem(0)
        }
    }

    fun cyclePlatform(delta: Int) {
        val tabs = ui.value.tabs
        if (tabs.isEmpty()) return
        val idx = tabs.indexOfFirst { it.id == filters.value.platformId }.coerceAtLeast(0)
        selectPlatform(tabs[(idx + delta).mod(tabs.size)].id)
        pendingFocusId = FOCUS_FIRST
    }

    fun setInstallFilter(f: InstallFilter) { filters.value = filters.value.copy(install = f) }
    fun setQuery(q: String) { filters.value = filters.value.copy(query = q) }
    fun setSort(s: SortKey) = viewModelScope.launch { c.settings.setSort(s) }
    fun setListView(v: Boolean) = viewModelScope.launch { c.settings.setListView(v) }

    fun install(rom: Rom) = c.downloads.enqueue(listOf(rom))
    fun installAll(roms: List<Rom>) = c.downloads.enqueue(roms)
    fun cancel(rom: Rom) = c.downloads.cancel(rom.id)
    fun refresh() = c.catalog.refresh()

    val coverBase: String? get() = c.catalog.client?.base

    companion object {
        const val FOCUS_FIRST = Long.MIN_VALUE
    }
}
