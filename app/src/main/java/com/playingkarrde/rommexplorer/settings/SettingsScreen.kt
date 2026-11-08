package com.playingkarrde.rommexplorer.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.ManageSearch
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.playingkarrde.rommexplorer.data.LibraryState
import com.playingkarrde.rommexplorer.data.library.FolderNaming
import com.playingkarrde.rommexplorer.data.settings.AppSettings
import com.playingkarrde.rommexplorer.ui.AppButton
import com.playingkarrde.rommexplorer.ui.AppIconButton
import com.playingkarrde.rommexplorer.ui.ButtonStyle
import com.playingkarrde.rommexplorer.ui.Palette
import com.playingkarrde.rommexplorer.ui.focusRing
import com.playingkarrde.rommexplorer.ui.formatBytes

class SettingsCallbacks(
    val back: () -> Unit,
    val pickFolder: () -> Unit,
    val setNaming: (FolderNaming) -> Unit,
    val setExtract: (Boolean) -> Unit,
    val setSwapAB: (Boolean) -> Unit,
    val setKeepScreenOn: (Boolean) -> Unit,
    val detectExisting: () -> Unit,
    val verifyInstalled: () -> Unit,
    val reorganize: () -> Unit,
    val refresh: () -> Unit,
    val signOut: () -> Unit,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(settings: AppSettings, username: String, library: LibraryState, busy: Boolean, cb: SettingsCallbacks) {
    val first = remember { FocusRequester() }
    var confirmReorg by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    Column(Modifier.fillMaxSize().background(Palette.Background)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", cb.back)
            Text("Settings", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp))
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp).widthIn(max = 760.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Section("Library folder")
            Text(
                when {
                    library.uri.isEmpty() -> "No folder picked yet."
                    else -> "${library.folderName} · ${library.entries.size} installed · ${formatBytes(library.entries.values.sumOf { it.sizeBytes })}"
                },
                color = Palette.TextDim,
            )
            library.error?.let { Text(it, color = Palette.Danger) }
            AppButton(if (library.uri.isEmpty()) "Pick library folder" else "Change library folder", cb.pickFolder, Modifier.focusRequester(first), Icons.Filled.Folder)

            Section("Platform folder names")
            Text("Shared with the desktop app. After changing this, use Reorganize to move installed games.", color = Palette.Muted, fontSize = 13.sp)
            FolderNaming.entries.forEach { n ->
                val shape = RoundedCornerShape(10.dp)
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 52.dp).focusRing(shape).clip(shape)
                        .selectable(settings.folderNaming == n, role = Role.RadioButton) { cb.setNaming(n) }.padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(settings.folderNaming == n, onClick = null)
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(n.label)
                        Text(n.example, color = Palette.Muted, fontSize = 12.sp)
                    }
                }
            }

            Section("Installing")
            ToggleRow("Extract multi-file games", "Off keeps them as a .zip file", settings.extractMultiFile, cb.setExtract)
            ToggleRow("Keep screen on while downloading", null, settings.keepScreenOn, cb.setKeepScreenOn)

            Section("Controller")
            ToggleRow("Swap A and B", "For Nintendo-style layouts: confirm with B, go back with A", settings.swapAB, cb.setSwapAB)
            Text(
                "D-pad: move · A: open · B: back · X: install/uninstall · Y: search · L1/R1: platform · L2/R2: page · Start: downloads · Select: sort & filter",
                color = Palette.Muted, fontSize = 13.sp,
            )

            Section("Library tools")
            Text("Detect existing files adds games that are already in the folder. Verify forgets games whose files are gone.", color = Palette.Muted, fontSize = 13.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AppButton("Detect existing files", cb.detectExisting, icon = Icons.Filled.ManageSearch, style = ButtonStyle.Tonal, enabled = !busy && library.ready)
                AppButton("Verify installed", cb.verifyInstalled, icon = Icons.Filled.FactCheck, style = ButtonStyle.Tonal, enabled = !busy && library.ready)
                AppButton("Reorganize folders", { confirmReorg = true }, icon = Icons.Filled.DriveFileMove, style = ButtonStyle.Tonal, enabled = !busy && library.ready)
            }

            Section("Server")
            Text("${settings.serverConfig?.base ?: settings.serverUrl} · signed in as $username", color = Palette.TextDim)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AppButton("Reload library", cb.refresh, icon = Icons.Filled.Refresh, style = ButtonStyle.Tonal)
                AppButton("Sign out", cb.signOut, icon = Icons.AutoMirrored.Filled.Logout, style = ButtonStyle.Outline)
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmReorg) {
        AlertDialog(
            onDismissRequest = { confirmReorg = false },
            title = { Text("Reorganize folders?") },
            text = { Text("Installed games will be moved into folders named by \"${settings.folderNaming.label}\". Games whose destination already exists are skipped.") },
            confirmButton = { AppButton("Reorganize", { confirmReorg = false; cb.reorganize() }) },
            dismissButton = { AppButton("Cancel", { confirmReorg = false }, style = ButtonStyle.Outline) },
        )
    }
}

@Composable
private fun Section(title: String) {
    Text(title, fontWeight = FontWeight.Bold, color = Palette.Accent2, modifier = Modifier.padding(top = 16.dp, bottom = 2.dp))
}

@Composable
private fun ToggleRow(title: String, subtitle: String?, value: Boolean, onChange: (Boolean) -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).focusRing(shape).clip(shape)
            .toggleable(value, role = Role.Switch, onValueChange = onChange).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title)
            subtitle?.let { Text(it, color = Palette.Muted, fontSize = 12.sp) }
        }
        Switch(value, onCheckedChange = null)
    }
}
