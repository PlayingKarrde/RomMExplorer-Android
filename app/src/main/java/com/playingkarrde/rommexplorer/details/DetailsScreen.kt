package com.playingkarrde.rommexplorer.details

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.playingkarrde.rommexplorer.browse.statusText
import com.playingkarrde.rommexplorer.data.api.Rom
import com.playingkarrde.rommexplorer.data.api.RommClient
import com.playingkarrde.rommexplorer.data.library.ManifestEntry
import com.playingkarrde.rommexplorer.downloads.DownloadItem
import com.playingkarrde.rommexplorer.downloads.DownloadStatus
import com.playingkarrde.rommexplorer.ui.AppButton
import com.playingkarrde.rommexplorer.ui.AppIconButton
import com.playingkarrde.rommexplorer.ui.ButtonStyle
import com.playingkarrde.rommexplorer.ui.Cover
import com.playingkarrde.rommexplorer.ui.PadAction
import com.playingkarrde.rommexplorer.ui.PadHandler
import com.playingkarrde.rommexplorer.ui.Palette
import com.playingkarrde.rommexplorer.ui.Pill
import com.playingkarrde.rommexplorer.ui.focusRing
import com.playingkarrde.rommexplorer.ui.formatBytes
import com.playingkarrde.rommexplorer.ui.formatDate
import com.playingkarrde.rommexplorer.ui.formatDuration

class DetailsCallbacks(
    val back: () -> Unit,
    val install: (Rom) -> Unit,
    val cancel: (Rom) -> Unit,
    val requestUninstall: (Rom) -> Unit,
    val openDownloads: () -> Unit,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DetailsScreen(
    rom: Rom,
    base: String,
    installed: ManifestEntry?,
    download: DownloadItem?,
    libraryReady: Boolean,
    compact: Boolean,
    cb: DetailsCallbacks,
) {
    val primary = remember { FocusRequester() }
    val context = LocalContext.current
    LaunchedEffect(rom.id) { runCatching { primary.requestFocus() } }

    fun toggle() {
        when {
            installed != null -> cb.requestUninstall(rom)
            download?.active == true -> cb.cancel(rom)
            libraryReady -> cb.install(rom)
        }
    }

    PadHandler { action ->
        when (action) {
            PadAction.X -> { toggle(); true }
            PadAction.Start -> { cb.openDownloads(); true }
            else -> false
        }
    }

    val coverUrl = RommClient.coverUrl(base, rom, large = true)

    val actions: @Composable () -> Unit = {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val m = Modifier.focusRequester(primary)
            when {
                installed != null -> AppButton("Uninstall", { toggle() }, m, Icons.Filled.Delete, ButtonStyle.Danger, hint = "X")
                download?.active == true -> AppButton("Cancel download", { toggle() }, m, Icons.Filled.Close, ButtonStyle.Outline, hint = "X")
                download?.status is DownloadStatus.Failed -> AppButton("Retry install", { cb.install(rom) }, m, Icons.Filled.Refresh, enabled = libraryReady, hint = "X")
                else -> AppButton("Install", { toggle() }, m, Icons.Filled.Download, enabled = libraryReady, hint = "X")
            }
            AppButton("Open in RomM", {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("$base/rom/${rom.id}"))) }
            }, icon = Icons.Filled.OpenInBrowser, style = ButtonStyle.Outline)
        }
        if (!libraryReady && installed == null) {
            Text("Pick a library folder in Settings to install games.", color = Palette.Warn, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
        }
        download?.let { DownloadProgress(it) }
    }

    val info: @Composable () -> Unit = {
        Text(rom.title, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text(rom.platformLabel, color = Palette.Accent2, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(12.dp))
        actions()
        Spacer(Modifier.height(16.dp))
        val chips = rom.genres + rom.regions + rom.languages
        if (chips.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                rom.genres.forEach { Pill(it) }
                rom.regions.forEach { Pill(it, Palette.Accent.copy(alpha = 0.25f)) }
                rom.languages.forEach { Pill(it, Palette.Accent2.copy(alpha = 0.18f)) }
            }
            Spacer(Modifier.height(12.dp))
        }
        if (rom.summary.isNotBlank()) {
            // Focusable so a controller can scroll through a long summary.
            Text(rom.summary, color = Palette.TextDim, lineHeight = 22.sp, modifier = Modifier.focusRing(RoundedCornerShape(8.dp)).focusable().padding(4.dp))
            Spacer(Modifier.height(12.dp))
        }
        Fact("Release date", rom.releaseTimestamp?.let(::formatDate))
        Fact("Developer", rom.developers.joinToString(", ").ifBlank { null })
        Fact("Rating", rom.rating?.let { "${it.toInt()} / 100" })
        Fact("Revision", rom.revision.ifBlank { null })
        Fact("File", rom.fileName + if (rom.isMulti) "  (multiple files)" else "")
        Fact("Size", formatBytes(installed?.sizeBytes ?: rom.fsSizeBytes))
        installed?.let { Fact("Installed at", it.relPath) }
    }

    Box(Modifier.fillMaxSize().background(Palette.Background)) {
        if (compact) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                AppIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", cb.back)
                Cover(coverUrl, rom.title, Modifier.fillMaxWidth(0.6f).aspectRatio(3f / 4f).clip(RoundedCornerShape(14.dp)).align(Alignment.CenterHorizontally))
                Spacer(Modifier.height(16.dp))
                info()
            }
        } else {
            Row(Modifier.fillMaxSize().padding(20.dp)) {
                Column(Modifier.width(300.dp)) {
                    AppIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", cb.back)
                    Spacer(Modifier.height(8.dp))
                    Cover(coverUrl, rom.title, Modifier.fillMaxWidth().aspectRatio(3f / 4f).clip(RoundedCornerShape(14.dp)))
                }
                Spacer(Modifier.width(24.dp))
                Column(Modifier.weight(1f).widthIn(max = 760.dp).verticalScroll(rememberScrollState()).padding(top = 8.dp)) { info() }
            }
        }
    }
}

@Composable
private fun DownloadProgress(dl: DownloadItem) {
    val s = dl.status
    Column(Modifier.padding(top = 12.dp).widthIn(max = 480.dp)) {
        val text = when (s) {
            is DownloadStatus.Downloading -> buildString {
                append("${formatBytes(s.bytes)} of ${formatBytes(s.total)}")
                if (s.bytesPerSec > 0) append(" · ${formatBytes(s.bytesPerSec.toLong())}/s")
                s.etaSeconds?.let { append(" · ${formatDuration(it)} left") }
            }
            is DownloadStatus.Failed -> s.message
            else -> statusText(dl)
        }
        Text(text, color = if (s is DownloadStatus.Failed) Palette.Danger else Palette.TextDim, fontSize = 13.sp)
        if (dl.active) {
            Spacer(Modifier.height(6.dp))
            if (s is DownloadStatus.Downloading) LinearProgressIndicator(progress = { s.fraction }, Modifier.fillMaxWidth(), color = Palette.Accent2)
            else LinearProgressIndicator(Modifier.fillMaxWidth(), color = Palette.Accent2)
        }
    }
}

@Composable
private fun Fact(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Row(Modifier.padding(vertical = 4.dp)) {
        Text(label, color = Palette.Muted, modifier = Modifier.width(120.dp), fontSize = 14.sp)
        Text(value, fontSize = 14.sp)
    }
}
