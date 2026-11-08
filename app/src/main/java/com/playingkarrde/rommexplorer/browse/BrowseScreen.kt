package com.playingkarrde.rommexplorer.browse

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Downloading
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.BackHandler
import com.playingkarrde.rommexplorer.data.api.Rom
import com.playingkarrde.rommexplorer.data.api.RommClient
import com.playingkarrde.rommexplorer.data.settings.InstallFilter
import com.playingkarrde.rommexplorer.data.settings.SortKey
import com.playingkarrde.rommexplorer.downloads.DownloadItem
import com.playingkarrde.rommexplorer.downloads.DownloadStatus
import com.playingkarrde.rommexplorer.ui.AppButton
import com.playingkarrde.rommexplorer.ui.AppIconButton
import com.playingkarrde.rommexplorer.ui.ButtonHint
import com.playingkarrde.rommexplorer.ui.ButtonStyle
import com.playingkarrde.rommexplorer.ui.Cover
import com.playingkarrde.rommexplorer.ui.PadAction
import com.playingkarrde.rommexplorer.ui.PadHandler
import com.playingkarrde.rommexplorer.ui.PadTextField
import com.playingkarrde.rommexplorer.ui.rememberPadFieldState
import androidx.compose.material3.OutlinedTextFieldDefaults
import com.playingkarrde.rommexplorer.ui.Palette
import com.playingkarrde.rommexplorer.ui.focusRing
import com.playingkarrde.rommexplorer.ui.formatBytes
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

class BrowseCallbacks(
    val openDetails: (Rom) -> Unit,
    val openDownloads: () -> Unit,
    val openSettings: () -> Unit,
    val requestUninstall: (Rom) -> Unit,
    val install: (Rom) -> Unit,
)

@Composable
fun BrowseScreen(vm: BrowseViewModel, compact: Boolean, cb: BrowseCallbacks) {
    val ui by vm.ui.collectAsState()
    val searchField = rememberPadFieldState()
    val railFocus = remember { FocusRequester() }
    var showOptions by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun toggleInstall(rom: Rom) {
        val dl = ui.downloads[rom.id]
        when {
            rom.id in ui.installed -> cb.requestUninstall(rom)
            dl != null && dl.active -> vm.cancel(rom)
            ui.library.ready -> cb.install(rom)
            else -> cb.openSettings()
        }
    }

    PadHandler { action ->
        when (action) {
            PadAction.X -> {
                val id = vm.lastFocusedId
                ui.roms.firstOrNull { it.id == id }?.let(::toggleInstall)
                true
            }
            PadAction.Y -> { searchField.startEditing(); true }
            PadAction.L1 -> { vm.cyclePlatform(-1); true }
            PadAction.R1 -> { vm.cyclePlatform(1); true }
            PadAction.L2, PadAction.R2 -> {
                val n = ui.roms.size
                if (n > 0) scope.launch {
                    val dir = if (action == PadAction.R2) 1 else -1
                    val target = if (ui.listView) {
                        val page = vm.listState.layoutInfo.visibleItemsInfo.size.coerceAtLeast(1)
                        (vm.listState.firstVisibleItemIndex + dir * page).coerceIn(0, n - 1)
                    } else {
                        val page = vm.gridState.layoutInfo.visibleItemsInfo.size.coerceAtLeast(1)
                        (vm.gridState.firstVisibleItemIndex + dir * page).coerceIn(0, n - 1)
                    }
                    // +1 for the header row.
                    if (ui.listView) vm.listState.scrollToItem(target + 1) else vm.gridState.scrollToItem(target + 1)
                    vm.pendingFocusId = ui.roms[target].id
                }
                true
            }
            PadAction.Start -> { cb.openDownloads(); true }
            PadAction.Select -> { showOptions = true; true }
        }
    }

    // Initial focus / focus restore.
    LaunchedEffect(Unit) {
        if (vm.pendingFocusId == null) vm.pendingFocusId = vm.lastFocusedId ?: BrowseViewModel.FOCUS_FIRST
    }
    LaunchedEffect(vm.pendingFocusId, ui.roms.isEmpty()) {
        if (vm.pendingFocusId != null && ui.roms.isEmpty() && !ui.catalog.loading) {
            runCatching { railFocus.requestFocus() }
            vm.pendingFocusId = null
        }
    }

    val activeDownloads = ui.downloads.values.count { it.active }

    val topBar: @Composable () -> Unit = {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PadTextField(
                value = ui.filters.query,
                onValueChange = vm::setQuery,
                state = searchField,
                placeholder = "Search games",
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = { ButtonHint("Y") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = Palette.Surface, unfocusedContainerColor = Palette.Surface),
                modifier = Modifier.weight(1f),
            )
            if (ui.filters.query.isNotEmpty()) AppIconButton(Icons.Filled.Close, "Clear search", { vm.setQuery("") })
            AppIconButton(Icons.Filled.Tune, "Sort and filter (Select)", { showOptions = true })
            AppIconButton(if (ui.listView) Icons.Filled.GridView else Icons.Filled.ViewList, if (ui.listView) "Grid view" else "List view", { vm.setListView(!ui.listView) })
            BadgedBox(badge = { if (activeDownloads > 0) Badge { Text("$activeDownloads") } }) {
                AppIconButton(Icons.Filled.Downloading, "Downloads (Start)", cb.openDownloads)
            }
            AppIconButton(Icons.Filled.Settings, "Settings", cb.openSettings)
        }
    }

    val content: @Composable (Modifier) -> Unit = { mod ->
        Column(mod) {
            Banners(ui, cb, onRetry = vm::refresh)
            if (ui.catalog.loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Palette.Accent2)
            RomCollection(ui, vm, compact, cb, ::toggleInstall)
        }
    }

    Box(Modifier.fillMaxSize().background(Palette.Background)) {
        if (compact) {
            Column(Modifier.fillMaxSize()) {
                topBar()
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(bottom = 6.dp),
                ) {
                    items(ui.tabs, key = { it.id ?: -1 }) { tab ->
                        val selected = tab.id == ui.filters.platformId
                        PlatformChip(tab, selected, { vm.selectPlatform(tab.id) }, if (selected) Modifier.focusRequester(railFocus) else Modifier)
                    }
                }
                content(Modifier.weight(1f))
            }
        } else {
            Row(Modifier.fillMaxSize()) {
                PlatformRail(ui, vm, railFocus, Modifier.width(230.dp).fillMaxHeight())
                Column(Modifier.weight(1f)) {
                    topBar()
                    content(Modifier.weight(1f))
                }
            }
        }
    }

    if (showOptions) OptionsDialog(ui, vm, onDismiss = { showOptions = false })
}

@Composable
private fun PlatformRail(ui: BrowseUi, vm: BrowseViewModel, railFocus: FocusRequester, modifier: Modifier) {
    Column(modifier.background(Palette.Panel)) {
        Row(Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Platforms", fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
            ButtonHint("L1")
            Spacer(Modifier.width(4.dp))
            ButtonHint("R1")
        }
        LazyColumn(contentPadding = PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(ui.tabs, key = { it.id ?: -1 }) { tab ->
                val selected = tab.id == ui.filters.platformId
                val shape = RoundedCornerShape(10.dp)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .then(if (selected) Modifier.focusRequester(railFocus) else Modifier)
                        .focusRing(shape)
                        .clip(shape)
                        .background(if (selected) Palette.Accent.copy(alpha = 0.25f) else Palette.Panel)
                        .selectable(selected, role = Role.Tab) { vm.selectPlatform(tab.id) }
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        tab.label, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (selected) Palette.Text else Palette.TextDim, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    Text("${tab.count}", color = Palette.Muted, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun PlatformChip(tab: PlatformTab, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val shape = RoundedCornerShape(50)
    Text(
        "${tab.label}  ${tab.count}",
        modifier = modifier
            .heightIn(min = 40.dp)
            .focusRing(shape)
            .clip(shape)
            .background(if (selected) Palette.Accent else Palette.Surface)
            .selectable(selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        fontSize = 13.sp,
        color = if (selected) Palette.Text else Palette.TextDim,
        maxLines = 1,
    )
}

@Composable
private fun Banners(ui: BrowseUi, cb: BrowseCallbacks, onRetry: () -> Unit) {
    val lib = ui.library
    val msg = when {
        lib.uri.isEmpty() -> "Pick a folder to install games into before downloading."
        lib.error != null -> lib.error
        else -> null
    }
    if (msg != null) {
        Banner(msg, Palette.Warn) { AppButton("Open settings", cb.openSettings, style = ButtonStyle.Tonal) }
    }
    ui.catalog.error?.let { err ->
        Banner(err, Palette.Danger) { AppButton("Retry", onRetry, icon = Icons.Filled.Refresh, style = ButtonStyle.Tonal) }
    }
}

@Composable
private fun Banner(text: String, color: androidx.compose.ui.graphics.Color, action: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).background(color.copy(alpha = 0.14f), RoundedCornerShape(12.dp)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, modifier = Modifier.weight(1f), color = Palette.Text)
        Spacer(Modifier.width(12.dp))
        action()
    }
}

@Composable
private fun RomCollection(ui: BrowseUi, vm: BrowseViewModel, compact: Boolean, cb: BrowseCallbacks, toggleInstall: (Rom) -> Unit) {
    val header: @Composable () -> Unit = { CollectionHeader(ui, vm) }
    if (ui.roms.isEmpty() && !ui.catalog.loading) {
        Column(Modifier.fillMaxSize()) {
            header()
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    when {
                        ui.catalog.roms.isEmpty() -> "No games on the server yet."
                        else -> "No games match these filters."
                    },
                    color = Palette.Muted,
                )
            }
        }
        return
    }
    if (ui.listView) {
        LazyColumn(state = vm.listState, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxSize()) {
            item(key = "header") { header() }
            items(ui.roms, key = { it.id }) { rom ->
                RomRow(rom, ui, vm, cb, toggleInstall)
            }
        }
    } else {
        LazyVerticalGrid(
            state = vm.gridState,
            columns = GridCells.Adaptive(if (compact) 108.dp else 132.dp),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "header", span = { GridItemSpan(maxLineSpan) }) { header() }
            items(ui.roms, key = { it.id }) { rom ->
                RomCard(rom, ui, vm, cb, toggleInstall)
            }
        }
    }
}

@Composable
private fun CollectionHeader(ui: BrowseUi, vm: BrowseViewModel) {
    val tab = ui.tabs.firstOrNull { it.id == ui.filters.platformId }
    val notInstalled = ui.roms.filter { it.id !in ui.installed && ui.downloads[it.id]?.active != true }
    var confirm by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(tab?.label ?: "All platforms", fontWeight = FontWeight.Bold, fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${ui.roms.size} games · ${ui.installed.keys.count { id -> ui.roms.any { it.id == id } }} installed · ${ui.filters.install.label} · ${ui.sort.label}",
                color = Palette.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (ui.filters.platformId != null && notInstalled.isNotEmpty() && ui.library.ready) {
            AppButton("Install all (${notInstalled.size})", { confirm = true }, icon = Icons.Filled.Download, style = ButtonStyle.Tonal)
        }
    }
    if (confirm) {
        val total = notInstalled.sumOf { it.fsSizeBytes }
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Install ${notInstalled.size} games?") },
            text = { Text("This downloads about ${formatBytes(total)} into your library folder.") },
            confirmButton = { AppButton("Install all", { confirm = false; vm.installAll(notInstalled) }) },
            dismissButton = { AppButton("Cancel", { confirm = false }, style = ButtonStyle.Outline) },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RomCard(rom: Rom, ui: BrowseUi, vm: BrowseViewModel, cb: BrowseCallbacks, toggleInstall: (Rom) -> Unit) {
    val requester = remember { FocusRequester() }
    var menu by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    val isFirst = ui.roms.firstOrNull()?.id == rom.id
    PendingFocus(rom, isFirst, vm, requester)
    Column {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .focusRequester(requester)
                .focusRing(shape, scale = 1.06f) { if (it) vm.lastFocusedId = rom.id }
                .clip(shape)
                .combinedClickable(onClick = { cb.openDetails(rom) }, onLongClick = { menu = true }),
        ) {
            Cover(vm.coverUrl(rom), rom.title, Modifier.fillMaxSize())
            StatusOverlay(rom, ui)
            RomMenu(menu, { menu = false }, rom, ui, cb, toggleInstall)
        }
        Text(rom.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp))
        if (ui.filters.platformId == null) Text(rom.platformLabel, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp, color = Palette.Muted)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RomRow(rom: Rom, ui: BrowseUi, vm: BrowseViewModel, cb: BrowseCallbacks, toggleInstall: (Rom) -> Unit) {
    val requester = remember { FocusRequester() }
    var menu by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    PendingFocus(rom, ui.roms.firstOrNull()?.id == rom.id, vm, requester)
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .focusRequester(requester)
                .focusRing(shape) { if (it) vm.lastFocusedId = rom.id }
                .clip(shape)
                .background(Palette.Panel)
                .combinedClickable(onClick = { cb.openDetails(rom) }, onLongClick = { menu = true })
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Cover(vm.coverUrl(rom), rom.title, Modifier.size(40.dp, 53.dp).clip(RoundedCornerShape(6.dp)))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(rom.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                Text("${rom.platformLabel} · ${formatBytes(ui.installed[rom.id]?.sizeBytes ?: rom.fsSizeBytes)}", color = Palette.Muted, fontSize = 12.sp, maxLines = 1)
            }
            RowStatus(rom, ui)
        }
        RomMenu(menu, { menu = false }, rom, ui, cb, toggleInstall)
    }
}

@Composable
private fun PendingFocus(rom: Rom, isFirst: Boolean, vm: BrowseViewModel, requester: FocusRequester) {
    val pending = vm.pendingFocusId
    val match = pending == rom.id || (pending == BrowseViewModel.FOCUS_FIRST && isFirst)
    LaunchedEffect(match) {
        if (match) {
            if (runCatching { requester.requestFocus() }.isSuccess) vm.pendingFocusId = null
        }
    }
}

@Composable
private fun RomMenu(open: Boolean, onDismiss: () -> Unit, rom: Rom, ui: BrowseUi, cb: BrowseCallbacks, toggleInstall: (Rom) -> Unit) {
    val installed = rom.id in ui.installed
    val dl = ui.downloads[rom.id]
    DropdownMenu(open, onDismiss) {
        DropdownMenuItem(text = { Text("Details") }, onClick = { onDismiss(); cb.openDetails(rom) })
        val label = when {
            installed -> "Uninstall"
            dl?.active == true -> "Cancel download"
            else -> "Install"
        }
        DropdownMenuItem(text = { Text(label) }, onClick = { onDismiss(); toggleInstall(rom) })
    }
}

@Composable
private fun StatusOverlay(rom: Rom, ui: BrowseUi) {
    val dl = ui.downloads[rom.id]
    Box(Modifier.fillMaxSize()) {
        if (rom.id in ui.installed) {
            Icon(
                Icons.Filled.CheckCircle, "Installed", tint = Palette.Success,
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).background(Palette.Background.copy(alpha = 0.7f), RoundedCornerShape(50)).padding(2.dp).size(20.dp),
            )
        }
        if (dl != null && dl.active) {
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Palette.Background.copy(alpha = 0.8f)).padding(6.dp)) {
                Text(statusText(dl), fontSize = 11.sp, color = Palette.Text, maxLines = 1)
                Spacer(Modifier.height(4.dp))
                val st = dl.status
                if (st is DownloadStatus.Downloading) LinearProgressIndicator(progress = { st.fraction }, modifier = Modifier.fillMaxWidth(), color = Palette.Accent2)
                else LinearProgressIndicator(Modifier.fillMaxWidth(), color = Palette.Accent2)
            }
        }
    }
}

@Composable
private fun RowStatus(rom: Rom, ui: BrowseUi) {
    val dl = ui.downloads[rom.id]
    when {
        dl != null && dl.active -> Text(statusText(dl), fontSize = 12.sp, color = Palette.Accent2)
        rom.id in ui.installed -> Icon(Icons.Filled.CheckCircle, "Installed", tint = Palette.Success)
        else -> {}
    }
}

fun statusText(dl: DownloadItem): String = when (val s = dl.status) {
    DownloadStatus.Queued -> "Queued"
    is DownloadStatus.Downloading -> "${(s.fraction * 100).toInt()}%"
    DownloadStatus.Extracting -> "Extracting…"
    DownloadStatus.Done -> "Installed"
    is DownloadStatus.Failed -> "Failed"
    DownloadStatus.Cancelled -> "Cancelled"
}

@Composable
private fun OptionsDialog(ui: BrowseUi, vm: BrowseViewModel, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sort and filter") },
        text = {
            LazyColumn {
                item { Text("Show", fontWeight = FontWeight.Bold, color = Palette.TextDim, modifier = Modifier.padding(vertical = 6.dp)) }
                items(InstallFilter.entries) { f ->
                    OptionRow(f.label, ui.filters.install == f, { vm.setInstallFilter(f) }, if (f == InstallFilter.All) Modifier.focusRequester(first) else Modifier)
                }
                item { Text("Sort by", fontWeight = FontWeight.Bold, color = Palette.TextDim, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp)) }
                items(SortKey.entries) { s -> OptionRow(s.label, ui.sort == s, { vm.setSort(s) }) }
            }
        },
        confirmButton = { AppButton("Done", onDismiss) },
    )
}

@Composable
private fun OptionRow(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier.fillMaxWidth().heightIn(min = 48.dp).focusRing(shape).clip(shape).selectable(selected, role = Role.RadioButton, onClick = onClick).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected, onClick = null)
        Text(label, modifier = Modifier.padding(start = 12.dp))
    }
}

/** Cover URL for the grid (small cover preferred). */
fun BrowseViewModel.coverUrl(rom: Rom): String? = coverBase?.let { RommClient.coverUrl(it, rom, large = false) }
