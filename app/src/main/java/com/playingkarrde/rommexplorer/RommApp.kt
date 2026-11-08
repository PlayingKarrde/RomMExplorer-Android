package com.playingkarrde.rommexplorer

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.playingkarrde.rommexplorer.data.CatalogRepository
import com.playingkarrde.rommexplorer.data.LibraryRepository
import com.playingkarrde.rommexplorer.data.api.ServerHolder
import com.playingkarrde.rommexplorer.data.api.buildHttpClient
import com.playingkarrde.rommexplorer.data.settings.SettingsRepository
import com.playingkarrde.rommexplorer.downloads.DownloadRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Manual DI: one instance of each repository for the whole process. */
class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val serverHolder = ServerHolder()
    val http = buildHttpClient(serverHolder, "RomMExplorer-Android/${BuildConfig.VERSION_NAME}")
    val settings = SettingsRepository(context)
    val catalog = CatalogRepository(appScope, http, serverHolder)
    val library = LibraryRepository(context)
    val downloads = DownloadRepository(context, appScope, catalog, library, settings)

    private val restoreLock = Mutex()

    /** Rebuilds client and library from saved settings if this process doesn't have them yet. */
    suspend fun restoreSession() = restoreLock.withLock {
        val s = settings.current()
        s.serverConfig?.let { cfg -> runCatching { catalog.restore(cfg) } }
        if (library.library == null && s.libraryUri.isNotEmpty()) library.open(s.libraryUri)
    }
}

class RommApp : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { container.http })) }
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.25).build() }
            .diskCache { DiskCache.Builder().directory(context.cacheDir.resolve("covers")).maxSizeBytes(256L * 1024 * 1024).build() }
            .crossfade(true)
            .build()
}
