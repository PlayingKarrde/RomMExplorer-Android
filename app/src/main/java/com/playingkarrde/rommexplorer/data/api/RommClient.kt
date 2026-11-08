package com.playingkarrde.rommexplorer.data.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class RommException(message: String, val isAuth: Boolean = false, cause: Throwable? = null) : Exception(message, cause)

data class RomPage(val roms: List<Rom>, val total: Long?, val rawCount: Int, val paginated: Boolean)

/** Thin client for the RomM REST API. All calls go through the shared [http] client. */
class RommClient(val config: ServerConfig, private val http: OkHttpClient) {
    val base: String get() = config.base
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    init {
        if (base.toHttpUrlOrNull() == null) throw RommException("\"$base\" isn't a valid server address. Enter something like http://192.168.1.10:8080")
    }

    private fun api(path: String): HttpUrl.Builder =
        (base.toHttpUrlOrNull() ?: throw RommException("Invalid server address")).newBuilder().addPathSegment("api").addPathSegments(path)

    private suspend fun getJson(url: HttpUrl): JsonElement = withContext(Dispatchers.IO) {
        val resp = execute(Request.Builder().url(url).get().build())
        resp.use {
            checkResponse(it)
            val body = it.body?.string() ?: ""
            try {
                json.parseToJsonElement(body)
            } catch (e: Exception) {
                throw RommException("The server sent a response we couldn't read. Is this a RomM server?", cause = e)
            }
        }
    }

    /** Runs a call, cancelling the HTTP request if the coroutine is cancelled. */
    suspend fun execute(request: Request): Response = suspendCancellableCoroutine { cont ->
        val call = http.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                cont.resumeWithException(RommException("Couldn't reach $base. Check the address and your network connection. (${e.message})", cause = e))
            }
            override fun onResponse(call: Call, response: Response) {
                // If the coroutine was cancelled meanwhile, nobody will close the response: do it here.
                cont.resume(response) { _, _, _ -> response.close() }
            }
        })
    }

    /** Verifies login and returns the username. */
    suspend fun currentUser(): String {
        if (config.auth == Auth.None) {
            platforms()
            return "guest"
        }
        val v = getJson(api("users/me").build()) as? JsonObject
        return v?.str("username")?.ifBlank { null } ?: "user"
    }

    suspend fun platforms(): List<Platform> {
        val items = itemsOf(getJson(api("platforms").build()))?.first
            ?: throw RommException("The server's platform list has an unexpected format.")
        return items.mapNotNull { runCatching { Platform.parse(it as JsonObject) }.getOrNull() }
    }

    suspend fun romsPage(offset: Long, limit: Int): RomPage {
        val url = api("roms")
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("offset", offset.toString())
            .addQueryParameter("order_by", "name")
            .addQueryParameter("order_dir", "asc")
            .addQueryParameter("with_rom_id_index", "false")
            .addQueryParameter("with_char_index", "false")
            .addQueryParameter("with_filter_values", "false")
            .build()
        val (items, total, paginated) = itemsOf(getJson(url))
            ?: throw RommException("The server's game list has an unexpected format.")
        val roms = items.mapNotNull { runCatching { Rom.parse(it as JsonObject) }.getOrNull() }
        return RomPage(roms, total, items.size, paginated)
    }

    /** Emits each page as it arrives. */
    fun allRoms(limit: Int = 500): Flow<List<Rom>> = flow {
        var offset = 0L
        while (true) {
            val page = romsPage(offset, limit)
            emit(page.roms)
            offset += page.rawCount
            if (!page.paginated || page.rawCount == 0) break
            // Trust `total` when the server sends it: it may cap `limit` below what we asked for, so a short
            // page doesn't mean the end. Without a total, a short page is the last one.
            if (page.total != null) {
                if (offset >= page.total) break
            } else if (page.rawCount < limit) break
        }
    }.flowOn(Dispatchers.IO)

    fun coverUrl(rom: Rom, large: Boolean): String? = coverUrl(base, rom, large)

    fun webUrl(id: Long) = "$base/rom/$id"

    fun contentUrl(rom: Rom): HttpUrl = api("roms/${rom.id}/content").addPathSegment(rom.fileName).build()

    companion object {
        /** `[...]` or `{ "items": [...], "total": N }`. Returns (items, total, paginated). */
        internal fun itemsOf(v: JsonElement): Triple<JsonArray, Long?, Boolean>? = when (v) {
            is JsonArray -> Triple(v, null, false)
            is JsonObject -> (v["items"] as? JsonArray)?.let { Triple(it, (v["total"] as? JsonPrimitive)?.longOrNull, true) }
            else -> null
        }

        fun checkResponse(resp: Response) {
            if (resp.code == 401 || resp.code == 403) {
                throw RommException("Authentication failed, check your username/password or API token.", isAuth = true)
            }
            if (!resp.isSuccessful) {
                val snippet = runCatching { resp.peekBody(4096).string() }.getOrDefault("").take(200)
                throw RommException("The server returned ${resp.code}${if (snippet.isNotBlank()) ": $snippet" else ""}")
            }
        }

        fun coverUrl(base: String, rom: Rom, large: Boolean): String? {
            val p = if (large) rom.pathCoverLarge.ifBlank { rom.pathCoverSmall } else rom.pathCoverSmall.ifBlank { rom.pathCoverLarge }
            if (p.isNotBlank()) {
                return when {
                    p.startsWith("http://") || p.startsWith("https://") -> p
                    p.startsWith("/") -> base + p
                    p.startsWith("assets/") -> "$base/$p"
                    else -> "$base/assets/romm/resources/$p"
                }
            }
            if (rom.urlCover.isNotBlank()) {
                return if (rom.urlCover.startsWith("//")) "https:${rom.urlCover}" else rom.urlCover
            }
            return null
        }
    }
}

@Suppress("unused")
private fun JsonPrimitive.text() = contentOrNull
