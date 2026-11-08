package com.playingkarrde.rommexplorer.data.settings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.playingkarrde.rommexplorer.data.api.Auth
import com.playingkarrde.rommexplorer.data.api.ServerConfig
import com.playingkarrde.rommexplorer.data.api.normalizeBase
import com.playingkarrde.rommexplorer.data.library.FolderNaming
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class SortKey(val label: String) { Name("Name"), Platform("Platform"), Size("Size"), ReleaseDate("Release date"), RecentlyInstalled("Recently installed") }
enum class InstallFilter(val label: String) { All("All games"), Installed("Installed"), NotInstalled("Not installed") }

data class AppSettings(
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    val token: String = "",
    val allowSelfSigned: Boolean = false,
    val libraryUri: String = "",
    val folderNaming: FolderNaming = FolderNaming.FsSlug,
    val extractMultiFile: Boolean = true,
    val swapAB: Boolean = false,
    val keepScreenOn: Boolean = true,
    val listView: Boolean = false,
    val sort: SortKey = SortKey.Name,
) {
    /** The token wins when both are set. */
    val auth: Auth
        get() = when {
            token.isNotBlank() -> Auth.Token(token.trim())
            username.isNotBlank() -> Auth.Basic(username, password)
            else -> Auth.None
        }

    val serverConfig: ServerConfig? get() = if (serverUrl.isBlank()) null else ServerConfig(normalizeBase(serverUrl), auth, allowSelfSigned)

    override fun toString() = "AppSettings(serverUrl=$serverUrl, username=$username, libraryUri=$libraryUri, ...)"
}

private val Context.dataStore by preferencesDataStore("settings")

class SettingsRepository(context: Context) {
    private val store = context.applicationContext.dataStore
    private val secrets = SecretBox()

    private object K {
        val server = stringPreferencesKey("server_url")
        val user = stringPreferencesKey("username")
        val pass = stringPreferencesKey("password_enc")
        val token = stringPreferencesKey("token_enc")
        val selfSigned = booleanPreferencesKey("allow_self_signed")
        val library = stringPreferencesKey("library_uri")
        val naming = stringPreferencesKey("folder_naming")
        val extract = booleanPreferencesKey("extract_multi")
        val swap = booleanPreferencesKey("swap_ab")
        val keepOn = booleanPreferencesKey("keep_screen_on")
        val list = booleanPreferencesKey("list_view")
        val sort = stringPreferencesKey("sort")
    }

    val settings: Flow<AppSettings> = store.data.map(::read)

    suspend fun current(): AppSettings = settings.first()

    private fun read(p: Preferences) = AppSettings(
        serverUrl = p[K.server] ?: "",
        username = p[K.user] ?: "",
        password = p[K.pass]?.let(secrets::decrypt) ?: "",
        token = p[K.token]?.let(secrets::decrypt) ?: "",
        allowSelfSigned = p[K.selfSigned] ?: false,
        libraryUri = p[K.library] ?: "",
        folderNaming = FolderNaming.parse(p[K.naming]),
        extractMultiFile = p[K.extract] ?: true,
        swapAB = p[K.swap] ?: false,
        keepScreenOn = p[K.keepOn] ?: true,
        listView = p[K.list] ?: false,
        sort = SortKey.entries.firstOrNull { it.name == p[K.sort] } ?: SortKey.Name,
    )

    suspend fun saveServer(url: String, username: String, password: String, token: String, allowSelfSigned: Boolean) {
        store.edit {
            it[K.server] = url.trim()
            it[K.user] = username.trim()
            it[K.pass] = secrets.encrypt(password)
            it[K.token] = secrets.encrypt(token.trim())
            it[K.selfSigned] = allowSelfSigned
        }
    }

    suspend fun signOut() = store.edit {
        it.remove(K.pass)
        it.remove(K.token)
    }

    suspend fun setLibraryUri(uri: String) = store.edit { it[K.library] = uri }
    suspend fun setFolderNaming(n: FolderNaming) = store.edit { it[K.naming] = n.name }
    suspend fun setExtractMulti(v: Boolean) = store.edit { it[K.extract] = v }
    suspend fun setSwapAB(v: Boolean) = store.edit { it[K.swap] = v }
    suspend fun setKeepScreenOn(v: Boolean) = store.edit { it[K.keepOn] = v }
    suspend fun setListView(v: Boolean) = store.edit { it[K.list] = v }
    suspend fun setSort(v: SortKey) = store.edit { it[K.sort] = v.name }
}

/** AES-GCM with a non-exportable Android Keystore key. Output is base64(iv || ciphertext). */
class SecretBox(private val alias: String = "romm_explorer_secrets") {
    // Synchronized so two first-time callers can't each generate a key and orphan one of them.
    @Synchronized
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val out = c.iv + c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    /** Returns "" if the value can't be decrypted (e.g. the key was wiped), so the user just signs in again. */
    fun decrypt(enc: String): String {
        if (enc.isEmpty()) return ""
        return runCatching {
            val bytes = Base64.decode(enc, Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
            String(c.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8)
        }.getOrDefault("")
    }
}
