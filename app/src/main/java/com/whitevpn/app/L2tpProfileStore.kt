package com.whitevpn.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONObject

/** L2TP/IPsec with PSK, not IKEv2. Stored profiles are not active tunnels. */
data class L2tpProfile(
    val id: String = "",
    val name: String,
    val host: String,
    val username: String,
    val password: String,
    val preSharedKey: String
)

/** Pure input validation, independent of Android UI or Android KeyStore. */
object L2tpProfileValidation {
    fun isValid(profile: L2tpProfile): Boolean {
        return profile.name.isNotBlank() && profile.name.length <= 100 &&
            profile.host.length <= 253 && ServerPing.validHost(profile.host.trim()) &&
            profile.username.isNotBlank() && profile.username.length <= 255 &&
            profile.password.isNotBlank() && profile.password.length <= 512 &&
            profile.preSharedKey.isNotBlank() && profile.preSharedKey.length <= 512 &&
            listOf(profile.name, profile.host, profile.username, profile.password,
                profile.preSharedKey).none { it.contains('\n') || it.contains('\r') }
    }
}

/**
 * Separate encrypted local store; never writes credentials, username or PSK
 * in the clear. Each rewrite uses a new AES-256-GCM nonce and AtomicFile.
 * This does not provision a native L2TP tunnel on unsupported Android versions.
 */
class L2tpProfileStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "l2tp-profiles-v1.enc"))
    private val alias = "whitevpn-l2tp-profiles-v1"

    @Synchronized
    fun read(): List<L2tpProfile> {
        if (!file.baseFile.isFile) return emptyList()
        val bytes = file.openRead().use { it.readBytes() }
        require(bytes.size > 29 && bytes[0] == 1.toByte()) { "Invalid encrypted L2TP database" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(),
            GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
        val json = JSONObject(String(
            cipher.doFinal(bytes.copyOfRange(13, bytes.size)), StandardCharsets.UTF_8
        ))
        val entries = json.getJSONArray("profiles")
        require(entries.length() <= 100)
        return (0 until entries.length()).map { index ->
            val p = entries.getJSONObject(index)
            L2tpProfile(
                id = p.getString("id"),
                name = p.getString("name"),
                host = p.getString("host"),
                username = p.getString("username"),
                password = p.getString("password"),
                preSharedKey = p.getString("psk")
            )
        }
    }

    @Synchronized
    fun upsert(profile: L2tpProfile): List<L2tpProfile> {
        require(L2tpProfileValidation.isValid(profile)) { "Invalid L2TP server settings" }
        val old = read()
        val existing = old.indexOfFirst { it.id == profile.id && profile.id.isNotBlank() }
        val updated = profile.copy(
            id = if (existing >= 0) profile.id else UUID.randomUUID().toString(),
            name = profile.name.trim(),
            host = profile.host.trim(),
            username = profile.username.trim()
        )
        val items = old.toMutableList()
        if (existing >= 0) {
            items[existing] = updated
        } else {
            require(items.size < 100)
            items.add(updated)
        }
        write(items)
        return items
    }

    @Synchronized
    fun delete(id: String): List<L2tpProfile> {
        val remaining = read().filterNot { it.id == id }
        write(remaining)
        return remaining
    }

    private fun write(profiles: List<L2tpProfile>) {
        val entries = JSONArray()
        profiles.forEach { p ->
            entries.put(JSONObject()
                .put("id", p.id)
                .put("name", p.name)
                .put("host", p.host)
                .put("username", p.username)
                .put("password", p.password)
                .put("psk", p.preSharedKey))
        }
        val cleartext = JSONObject().put("version", 1).put("profiles", entries)
            .toString().toByteArray(StandardCharsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val contents = byteArrayOf(1) + cipher.iv + cipher.doFinal(cleartext)
        var stream: java.io.FileOutputStream? = null
        try {
            stream = file.startWrite()
            stream.write(contents)
            file.finishWrite(stream)
        } catch (error: Exception) {
            if (stream != null) file.failWrite(stream)
            throw error
        }
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = keyStore.getKey(alias, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore"
        )
        generator.init(KeyGenParameterSpec.Builder(
            alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        ).setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build())
        return generator.generateKey()
    }
}
