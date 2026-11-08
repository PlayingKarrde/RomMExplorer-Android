package com.playingkarrde.rommexplorer.data.api

import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.internal.tls.OkHostnameVerifier
import java.net.Socket
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager

sealed interface Auth {
    data object None : Auth
    data class Basic(val username: String, val password: String) : Auth {
        override fun toString() = "Basic(username=$username, password=***)"
    }
    data class Token(val token: String) : Auth {
        override fun toString() = "Token(***)"
    }
}

/** The server we're talking to. Credentials are only ever attached to URLs under [base]. */
data class ServerConfig(val base: String, val auth: Auth, val allowSelfSigned: Boolean) {
    val host: String? get() = base.toHttpUrlOrNull()?.host
}

/** Trims, drops trailing `/`, and adds `http://` if no scheme was given. */
fun normalizeBase(url: String): String {
    var s = url.trim().trimEnd('/')
    if (s.isNotEmpty() && !s.startsWith("http://", true) && !s.startsWith("https://", true)) s = "http://$s"
    return s
}

/** Holds the active server so the one shared OkHttp client (also used by Coil) can scope auth and TLS to it. */
class ServerHolder {
    @Volatile var config: ServerConfig? = null
}

/** Adds credentials only to requests whose URL starts with the configured server base. */
class AuthInterceptor(private val holder: ServerHolder) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val cfg = holder.config
        val req = chain.request()
        if (cfg == null || req.header("Authorization") != null || !isUnderBase(req.url.toString(), cfg.base)) {
            return chain.proceed(req)
        }
        val header = when (val a = cfg.auth) {
            is Auth.Basic -> Credentials.basic(a.username, a.password, Charsets.UTF_8)
            is Auth.Token -> "Bearer ${a.token}"
            Auth.None -> null
        }
        return chain.proceed(if (header == null) req else req.newBuilder().header("Authorization", header).build())
    }

    companion object {
        fun isUnderBase(url: String, base: String): Boolean {
            if (!url.startsWith(base, ignoreCase = true)) return false
            // Make sure "http://host" doesn't match "http://host.evil.com".
            val rest = url.substring(base.length)
            return rest.isEmpty() || rest[0] == '/' || rest[0] == '?'
        }
    }
}

/**
 * Trusts the system CAs, and additionally accepts any certificate from the configured server host
 * when the user turned on "Allow self-signed certificates". Other hosts never get that exception.
 */
class ScopedTrustManager(private val holder: ServerHolder) : X509ExtendedTrustManager() {
    private val system: X509TrustManager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).run {
        init(null as KeyStore?)
        trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    private fun lenientFor(host: String?): Boolean {
        val cfg = holder.config ?: return false
        return cfg.allowSelfSigned && host != null && host.equals(cfg.host, ignoreCase = true)
    }

    private fun check(chain: Array<out X509Certificate>, authType: String, host: String?) {
        try {
            system.checkServerTrusted(chain, authType)
        } catch (e: CertificateException) {
            if (!lenientFor(host)) throw e
        }
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String, socket: Socket?) =
        check(chain, authType, (socket as? SSLSocket)?.handshakeSession?.peerHost)

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String, engine: SSLEngine?) =
        check(chain, authType, engine?.handshakeSession?.peerHost ?: engine?.peerHost)

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) =
        system.checkServerTrusted(chain, authType)

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String, socket: Socket?) =
        system.checkClientTrusted(chain, authType)

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String, engine: SSLEngine?) =
        system.checkClientTrusted(chain, authType)

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) =
        system.checkClientTrusted(chain, authType)

    override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers
}

class ScopedHostnameVerifier(private val holder: ServerHolder) : HostnameVerifier {
    override fun verify(hostname: String, session: SSLSession): Boolean {
        if (OkHostnameVerifier.verify(hostname, session)) return true
        val cfg = holder.config ?: return false
        return cfg.allowSelfSigned && hostname.equals(cfg.host, ignoreCase = true)
    }
}

fun buildHttpClient(holder: ServerHolder, userAgent: String): OkHttpClient {
    val tm = ScopedTrustManager(holder)
    val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }
    return OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .sslSocketFactory(ssl.socketFactory, tm)
        .hostnameVerifier(ScopedHostnameVerifier(holder))
        .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("User-Agent", userAgent).build()) }
        .addInterceptor(AuthInterceptor(holder))
        .build()
}
