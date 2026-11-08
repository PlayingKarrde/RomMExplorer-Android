package com.playingkarrde.rommexplorer.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ClearAll
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.playingkarrde.rommexplorer.data.api.RommClient
import com.playingkarrde.rommexplorer.ui.AppButton
import com.playingkarrde.rommexplorer.ui.AppIconButton
import com.playingkarrde.rommexplorer.ui.ButtonStyle
import com.playingkarrde.rommexplorer.ui.Cover
import com.playingkarrde.rommexplorer.ui.Palette
import com.playingkarrde.rommexplorer.ui.formatBytes
import com.playingkarrde.rommexplorer.ui.formatDuration

@Composable
fun DownloadsScreen(
    items: List<DownloadItem>,
    base: String?,
    onBack: () -> Unit,
    onCancel: (Long) -> Unit,
    onRetry: (Long) -> Unit,
    onCancelAll: () -> Unit,
    onClearFinished: () -> Unit,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    Column(Modifier.fillMaxSize().background(Palette.Background)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack, Modifier.focusRequester(first))
            Text("Downloads", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (items.any { it.active }) AppButton("Cancel all", onCancelAll, icon = Icons.Filled.Close, style = ButtonStyle.Outline)
            if (items.any { !it.active }) AppButton("Clear finished", onClearFinished, icon = Icons.Filled.ClearAll, style = ButtonStyle.Tonal)
        }
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Nothing downloading. Press X on a game, or open it and choose Install.", color = Palette.Muted, modifier = Modifier.padding(24.dp))
            }
            return@Column
        }
        LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items, key = { it.rom.id }) { item ->
                DownloadRow(item, base, onCancel, onRetry)
            }
        }
    }
}

@Composable
private fun DownloadRow(item: DownloadItem, base: String?, onCancel: (Long) -> Unit, onRetry: (Long) -> Unit) {
    val s = item.status
    Row(
        Modifier.fillMaxWidth().widthIn(max = 900.dp).clip(RoundedCornerShape(12.dp)).background(Palette.Panel).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(base?.let { RommClient.coverUrl(it, item.rom, false) }, item.rom.title, Modifier.size(48.dp, 64.dp).clip(RoundedCornerShape(6.dp)))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(item.rom.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val (text, color) = when (s) {
                DownloadStatus.Queued -> "Queued · ${formatBytes(item.rom.fsSizeBytes)}" to Palette.TextDim
                is DownloadStatus.Downloading -> buildString {
                    append("${formatBytes(s.bytes)} of ${formatBytes(s.total)}")
                    if (s.bytesPerSec > 0) append(" · ${formatBytes(s.bytesPerSec.toLong())}/s")
                    s.etaSeconds?.let { append(" · ${formatDuration(it)} left") }
                } to Palette.TextDim
                DownloadStatus.Extracting -> "Extracting…" to Palette.TextDim
                DownloadStatus.Done -> "Installed" to Palette.Success
                is DownloadStatus.Failed -> s.message to Palette.Danger
                DownloadStatus.Cancelled -> "Cancelled" to Palette.Muted
            }
            Text(text, color = color, fontSize = 13.sp, maxLines = 3)
            if (item.active) {
                Spacer(Modifier.height(6.dp))
                if (s is DownloadStatus.Downloading) LinearProgressIndicator(progress = { s.fraction }, Modifier.fillMaxWidth(), color = Palette.Accent2)
                else LinearProgressIndicator(Modifier.fillMaxWidth(), color = Palette.Accent2)
            }
        }
        when {
            item.active -> AppButton("Cancel", { onCancel(item.rom.id) }, icon = Icons.Filled.Close, style = ButtonStyle.Outline)
            s is DownloadStatus.Failed || s is DownloadStatus.Cancelled -> AppButton("Retry", { onRetry(item.rom.id) }, icon = Icons.Filled.Refresh, style = ButtonStyle.Tonal)
        }
    }
}
