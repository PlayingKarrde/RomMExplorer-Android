package com.playingkarrde.rommexplorer.data.api

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class ApiTest {
    private val server = MockWebServer()
    private val holder = ServerHolder()
    private lateinit var http: OkHttpClient

    @Before fun setUp() {
        server.start()
        http = buildHttpClient(holder, "test")
    }

    @After fun tearDown() = server.shutdown()

    private fun base() = server.url("/").toString().trimEnd('/')

    private fun client(auth: Auth = Auth.Basic("admin", "secret")): RommClient {
        val cfg = ServerConfig(base(), auth, false)
        holder.config = cfg
        return RommClient(cfg, http)
    }

    private fun rom(json: String) = Rom.parse(Json.parseToJsonElement(json) as JsonObject)

    @Test fun normalize() {
        assertEquals("http://host:8080", normalizeBase("  host:8080/ "))
        assertEquals("https://romm.example.com", normalizeBase("https://romm.example.com//"))
        assertEquals("", normalizeBase("  "))
    }

    @Test fun basicAuthAndUser() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"username":"admin","id":1}"""))
        assertEquals("admin", client().currentUser())
        val req = server.takeRequest()
        assertEquals("/api/users/me", req.path)
        assertTrue(req.getHeader("Authorization")!!.startsWith("Basic "))
    }

    @Test fun tokenAuth() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"username":"me"}"""))
        client(Auth.Token("rmm_abc")).currentUser()
        assertEquals("Bearer rmm_abc", server.takeRequest().getHeader("Authorization"))
    }

    @Test fun noAuthCallsPlatforms() = runBlocking {
        server.enqueue(MockResponse().setBody("[]"))
        assertEquals("guest", client(Auth.None).currentUser())
        val req = server.takeRequest()
        assertEquals("/api/platforms", req.path)
        assertNull(req.getHeader("Authorization"))
    }

    @Test fun authFailureMessage() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        try {
            client().currentUser()
            fail()
        } catch (e: RommException) {
            assertTrue(e.isAuth)
            assertEquals("Authentication failed, check your username/password or API token.", e.message)
        }
    }

    @Test fun otherErrorShowsStatusAndBody() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("x".repeat(500)))
        try {
            client().platforms()
            fail()
        } catch (e: RommException) {
            assertTrue(e.message!!.startsWith("The server returned 500: "))
            assertEquals(200, e.message!!.substringAfter(": ").length)
        }
    }

    @Test fun platformsArrayOrItems() = runBlocking {
        server.enqueue(MockResponse().setBody("""[{"id":1,"slug":"snes","name":"SNES","display_name":null}, "junk", {"id":2,"custom_name":"My GB"}]"""))
        server.enqueue(MockResponse().setBody("""{"items":[{"id":3,"slug":"psx"}],"total":1}"""))
        val c = client()
        val a = c.platforms()
        assertEquals(listOf(1L, 2L), a.map { it.id })
        assertEquals("SNES", a[0].label)
        assertEquals("My GB", a[1].label)
        assertEquals("psx", c.platforms().single().label)
    }

    @Test fun romsPaginateUntilTotal() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val offset = request.requestUrl!!.queryParameter("offset")!!.toInt()
                val limit = request.requestUrl!!.queryParameter("limit")!!.toInt()
                val total = 5
                val items = (offset until minOf(offset + limit, total)).joinToString(",") { """{"id":$it,"name":"G$it"}""" }
                return MockResponse().setBody("""{"items":[$items],"total":$total}""")
            }
        }
        val pages = client().allRoms(limit = 2).toList()
        assertEquals(listOf(2, 2, 1), pages.map { it.size })
        assertEquals(3, server.requestCount)
        val first = server.takeRequest().requestUrl!!
        assertEquals("name", first.queryParameter("order_by"))
        assertEquals("false", first.queryParameter("with_char_index"))
    }

    @Test fun romsKeepPagingWhenServerCapsLimit() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val offset = request.requestUrl!!.queryParameter("offset")!!.toInt()
                val total = 5
                // The server ignores our limit of 10 and sends at most 2 per page.
                val items = (offset until minOf(offset + 2, total)).joinToString(",") { """{"id":$it,"name":"G$it"}""" }
                return MockResponse().setBody("""{"items":[$items],"total":$total}""")
            }
        }
        val pages = client().allRoms(limit = 10).toList()
        assertEquals(listOf(2, 2, 1), pages.map { it.size })
    }

    @Test fun romsPlainArrayIsOnePage() = runBlocking {
        server.enqueue(MockResponse().setBody("""[{"id":1},{"id":2},{"id":3}]"""))
        val pages = client().allRoms(limit = 2).toList()
        assertEquals(1, pages.size)
        assertEquals(3, pages[0].size)
    }

    @Test fun romsStopWhenTotalReachedEvenIfFullPage() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"items":[{"id":1},{"id":2}],"total":2}"""))
        assertEquals(1, client().allRoms(limit = 2).toList().size)
        assertEquals(1, server.requestCount)
    }

    @Test fun nullHeavyRomParses() {
        val r = rom(
            """{"id":9,"name":null,"fs_name":"game.sfc","fs_name_no_ext":"game","fs_size_bytes":null,"platform_id":null,
               "platform_slug":null,"regions":null,"languages":["En",null],"summary":null,"has_multiple_files":null,
               "metadatum":null,"igdb_metadata":{"genres":[{"name":"RPG"},"Action",{"id":1}],"first_release_date":"915148800",
               "total_rating":8.5,"companies":["Square"]}}"""
        )
        assertEquals("game", r.title)
        assertEquals(0, r.fsSizeBytes)
        assertEquals(listOf("En"), r.languages)
        assertEquals(listOf("RPG", "Action"), r.genres)
        assertEquals(listOf("Square"), r.developers)
        assertEquals(915148800L, r.releaseTimestamp)
        assertEquals(85.0, r.rating!!, 0.001)
        assertEquals(false, r.isMulti)
    }

    @Test fun metadatumWinsAndMillis() {
        val r = rom(
            """{"id":1,"fs_name":"","multi":true,
               "metadatum":{"genres":["Platformer"],"developers":[{"name":"Nintendo"}],"first_release_date":656380800000,"average_rating":92},
               "igdb_metadata":{"genres":["Other"],"first_release_date":1}}"""
        )
        assertEquals("rom-1", r.fileName)
        assertEquals("", r.title)
        assertTrue(r.isMulti)
        assertEquals(listOf("Platformer"), r.genres)
        assertEquals(listOf("Nintendo"), r.developers)
        assertEquals(656380800L, r.releaseTimestamp)
        assertEquals(92.0, r.rating!!, 0.001)
    }

    @Test fun zeroReleaseDateIsUnknown() {
        assertNull(rom("""{"id":1,"metadatum":{"first_release_date":0}}""").releaseTimestamp)
    }

    @Test fun coverUrls() {
        val b = "http://h:1"
        fun c(small: String, large: String = "", url: String = "", isLarge: Boolean = false) =
            RommClient.coverUrl(b, rom("""{"id":1,"path_cover_small":"$small","path_cover_large":"$large","url_cover":"$url"}"""), isLarge)
        assertEquals("https://cdn/x.png", c("https://cdn/x.png"))
        assertEquals("http://h:1/assets/x.png", c("/assets/x.png"))
        assertEquals("http://h:1/assets/x.png", c("assets/x.png"))
        assertEquals("http://h:1/assets/romm/resources/roms/1/cover.png", c("roms/1/cover.png"))
        assertEquals("http://h:1/big.png", c("/small.png", "/big.png", isLarge = true))
        assertEquals("http://h:1/small.png", c("", "/small.png"))
        assertEquals("https://images.igdb.com/a.jpg", c("", url = "//images.igdb.com/a.jpg"))
        assertNull(c(""))
    }

    @Test fun contentUrlEncodesFsName() {
        val url = client().contentUrl(rom("""{"id":5,"fs_name":"Game #1 (USA) [!].zip"}"""))
        assertEquals(listOf("api", "roms", "5", "content", "Game #1 (USA) [!].zip"), url.pathSegments)
        assertTrue(url.toString(), url.toString().contains("/content/Game%20%231%20(USA)"))
        assertNull(url.query)
    }

    @Test fun credentialsOnlyGoToServer() {
        assertTrue(AuthInterceptor.isUnderBase("http://h:1/api/x", "http://h:1"))
        assertTrue(AuthInterceptor.isUnderBase("http://h:1", "http://h:1"))
        assertTrue(!AuthInterceptor.isUnderBase("http://h:1.evil.com/x", "http://h:1"))
        assertTrue(!AuthInterceptor.isUnderBase("https://images.igdb.com/x", "http://h:1"))
    }

    @Test fun externalCoverGetsNoAuth() {
        client()
        val other = MockWebServer().apply { start() }
        try {
            other.enqueue(MockResponse().setBody("img"))
            // Same host, different port: still a different origin from the configured base.
            http.newCall(okhttp3.Request.Builder().url(other.url("/cover.png")).build()).execute().close()
            assertNull(other.takeRequest().getHeader("Authorization"))
        } finally {
            other.shutdown()
        }
    }
}
