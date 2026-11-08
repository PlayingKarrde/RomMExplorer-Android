package com.playingkarrde.rommexplorer

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.playingkarrde.rommexplorer.browse.BrowseCallbacks
import com.playingkarrde.rommexplorer.browse.BrowseScreen
import com.playingkarrde.rommexplorer.browse.BrowseViewModel
import com.playingkarrde.rommexplorer.connect.ConnectScreen
import com.playingkarrde.rommexplorer.data.settings.AppSettings
import com.playingkarrde.rommexplorer.details.DetailsCallbacks
import com.playingkarrde.rommexplorer.details.DetailsScreen
import com.playingkarrde.rommexplorer.downloads.DownloadsScreen
import com.playingkarrde.rommexplorer.settings.SettingsCallbacks
import com.playingkarrde.rommexplorer.settings.SettingsScreen
import com.playingkarrde.rommexplorer.ui.AppButton
import com.playingkarrde.rommexplorer.ui.ButtonStyle
import com.playingkarrde.rommexplorer.ui.LocalPad
import com.playingkarrde.rommexplorer.ui.PadDispatcher
import com.playingkarrde.rommexplorer.ui.Palette
import com.playingkarrde.rommexplorer.ui.RommTheme
import com.playingkarrde.rommexplorer.ui.padActionFor
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val pad = PadDispatcher()
    @Volatile private var swapAB = false

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as RommApp).container

        lifecycleScope.launch {
            container.settings.settings.collect { swapAB = it.swapAB }
        }
        lifecycleScope.launch {
            combine(container.downloads.items, container.settings.settings) { items, s -> s.keepScreenOn && items.any { it.active } }
                .collect { on ->
                    if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
        }

        setContent {
            RommTheme {
                // LocalContentColor defaults to black outside a Surface; make plain Text/Icon light everywhere.
                CompositionLocalProvider(LocalPad provides pad, LocalContentColor provides Palette.Text) {
                    val app: AppViewModel = viewModel { AppViewModel(container) }
                    val compact = calculateWindowSizeClass(this).widthSizeClass == WindowWidthSizeClass.Compact
                    Box(Modifier.fillMaxSize().background(Palette.Background).safeDrawingPadding()) {
                        AppContent(app, compact)
                    }
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun AppContent(app: AppViewModel, compact: Boolean) {
        val c = app.c
        val settings by app.settings.collectAsState()
        val catalog by c.catalog.state.collectAsState()
        val library by c.library.state.collectAsState()
        val downloads by c.downloads.items.collectAsState()
        val browseVm: BrowseViewModel = viewModel { BrowseViewModel(c) }

        val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) app.onFolderPicked(uri)
        }
        val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

        BackHandler(enabled = app.stack.size > 1) { app.back() }

        val confirmUninstall = { rom: com.playingkarrde.rommexplorer.data.api.Rom -> app.confirmUninstall = rom }
        val install = { rom: com.playingkarrde.rommexplorer.data.api.Rom ->
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            c.downloads.enqueue(listOf(rom))
        }

        when (val screen = app.screen) {
            Screen.Starting -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            Screen.Connect -> ConnectScreen(settings ?: AppSettings(), app.connectError, app::connect)
            Screen.Browse -> BrowseScreen(
                browseVm, compact,
                BrowseCallbacks(
                    openDetails = { app.navigate(Screen.Details(it.id)) },
                    openDownloads = { app.navigate(Screen.Downloads) },
                    openSettings = { app.navigate(Screen.Settings) },
                    requestUninstall = confirmUninstall,
                    install = install,
                ),
            )
            is Screen.Details -> {
                val rom = catalog.roms.firstOrNull { it.id == screen.romId }
                if (rom == null) {
                    LaunchedEffect(Unit) { app.back() }
                } else {
                    DetailsScreen(
                        rom = rom,
                        base = c.catalog.client?.base ?: "",
                        installed = library.entries[rom.id],
                        download = downloads.firstOrNull { it.rom.id == rom.id },
                        libraryReady = library.ready,
                        compact = compact,
                        cb = DetailsCallbacks(
                            back = { app.back() },
                            install = install,
                            cancel = { c.downloads.cancel(it.id) },
                            requestUninstall = confirmUninstall,
                            openDownloads = { app.navigate(Screen.Downloads) },
                        ),
                    )
                }
            }
            Screen.Downloads -> DownloadsScreen(
                items = downloads,
                base = c.catalog.client?.base,
                onBack = { app.back() },
                onCancel = c.downloads::cancel,
                onRetry = c.downloads::retry,
                onCancelAll = c.downloads::cancelAll,
                onClearFinished = c.downloads::clearFinished,
            )
            Screen.Settings -> SettingsScreen(
                settings = settings ?: AppSettings(),
                username = catalog.username,
                library = library,
                busy = app.busy,
                cb = SettingsCallbacks(
                    back = { app.back() },
                    pickFolder = { runCatching { pickFolder.launch(null) }.onFailure { app.message = "This device has no folder picker available." } },
                    setNaming = app::setNaming,
                    setExtract = app::setExtract,
                    setSwapAB = app::setSwapAB,
                    setKeepScreenOn = app::setKeepScreenOn,
                    detectExisting = app::detectExisting,
                    verifyInstalled = app::verifyInstalled,
                    reorganize = app::reorganize,
                    refresh = { c.catalog.refresh(); app.message = "Reloading games from the server…" },
                    signOut = app::signOut,
                ),
            )
        }

        // First run: ask for the library folder up front. The system folder picker *is* the permission
        // grant (Storage Access Framework), so there's no separate "allow storage" prompt.
        var folderPromptDismissed by rememberSaveable { mutableStateOf(false) }
        if (app.screen == Screen.Browse && settings != null && library.uri.isEmpty() && !folderPromptDismissed) {
            AlertDialog(
                onDismissRequest = { folderPromptDismissed = true },
                title = { Text("Choose your library folder") },
                text = {
                    Text(
                        "Pick the folder games should be installed into, for example your ROMs folder on the SD card. " +
                            "Android will open its folder picker: go into the folder and choose \"Use this folder\", then allow access. " +
                            "Platform folders (snes, psx…) are created inside it."
                    )
                },
                confirmButton = {
                    AppButton("Pick folder", {
                        folderPromptDismissed = true
                        runCatching { pickFolder.launch(null) }.onFailure { app.message = "This device has no folder picker available." }
                    })
                },
                dismissButton = { AppButton("Later", { folderPromptDismissed = true }, style = ButtonStyle.Outline) },
            )
        }

        app.confirmUninstall?.let { rom ->
            AlertDialog(
                onDismissRequest = { app.confirmUninstall = null },
                title = { Text("Uninstall ${rom.title}?") },
                text = { Text("This deletes the game's files from your library folder.") },
                confirmButton = { AppButton("Uninstall", { app.confirmUninstall = null; app.uninstall(rom) }, style = ButtonStyle.Danger) },
                dismissButton = { AppButton("Cancel", { app.confirmUninstall = null }, style = ButtonStyle.Outline) },
            )
        }

        app.message?.let { msg ->
            LaunchedEffect(msg) {
                delay(4000)
                if (app.message == msg) app.message = null
            }
            Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.BottomCenter) {
                Snackbar { Text(msg) }
            }
        }
    }

    /**
     * Controller mapping. A (or B when swapped) becomes DPAD_CENTER so Compose treats it as a click,
     * B becomes Back, and the other buttons go to whichever screen registered a [com.playingkarrde.rommexplorer.ui.PadHandler].
     */
    @SuppressLint("RestrictedApi") // False positive: overriding Activity.dispatchKeyEvent is public API.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val confirm = if (swapAB) KeyEvent.KEYCODE_BUTTON_B else KeyEvent.KEYCODE_BUTTON_A
        val back = if (swapAB) KeyEvent.KEYCODE_BUTTON_A else KeyEvent.KEYCODE_BUTTON_B
        // Some handhelds send BACK for the B button instead of BUTTON_B; treat that as the controller's B too.
        val isPadBack = event.keyCode == back ||
            (event.keyCode == KeyEvent.KEYCODE_BACK && event.isFromSource(InputDevice.SOURCE_GAMEPAD))
        if (isPadBack) {
            // Only go back when there's somewhere to go (a previous screen, an open text field, …).
            // At the top level, B does nothing instead of closing the app; the system Back gesture still can.
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled && onBackPressedDispatcher.hasEnabledCallbacks()) {
                onBackPressedDispatcher.onBackPressed()
            }
            return true
        }
        if (event.keyCode == confirm) {
            return super.dispatchKeyEvent(
                KeyEvent(
                    event.downTime, event.eventTime, event.action, KeyEvent.KEYCODE_DPAD_CENTER,
                    event.repeatCount, event.metaState, event.deviceId, event.scanCode, event.flags, event.source,
                )
            )
        }
        val action = padActionFor(event.keyCode)
        if (action != null) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                val repeatable = action == com.playingkarrde.rommexplorer.ui.PadAction.L2 || action == com.playingkarrde.rommexplorer.ui.PadAction.R2
                if (event.repeatCount == 0 || repeatable) pad.dispatch(action)
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}
