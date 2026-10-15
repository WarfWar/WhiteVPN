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

internal data class StoredProfile(
    val id: String,
    val name: String,
    val provider: String,
    val configuration: String
)
internal data class SavedProfiles(val entries: List<StoredProfile>, val selectedId: String?)

/** Profile data, including private keys, is stored in AES-256-GCM encrypted files. */
internal class SecureConfigStore(private val context: Context) {
    private val alias = "whitevpn-wireguard-v1"
    private val oldFile = File(context.filesDir, "profile.enc")
    private val file = File(context.filesDir, "profiles-v2.enc")

    @Synchronized fun read(): SavedProfiles {
        if (!file.isFile) {
            if (!oldFile.isFile) return SavedProfiles(emptyList(), null)
            // Backward compatible with WhiteVPN 0.1.0, keeping legacy file as backup.
            val legacy = StoredProfile(
                UUID.randomUUID().toString(), "Мой VPN (старый профиль)",
                "Личный", decrypt(oldFile)
            )
            return SavedProfiles(listOf(legacy), legacy.id).also(::write)
        }
        val json = JSONObject(decrypt(file))
        val arr = json.getJSONArray("profiles")
        require(arr.length() <= 100)
        val entries = (0 until arr.length()).map { i ->
            val j = arr.getJSONObject(i)
            StoredProfile(j.getString("id"), j.getString("name"),
                j.getString("provider"), j.getString("configuration"))
        }
        val selected = json.optString("selectedId").takeIf { id ->
            entries.any { it.id == id }
        } ?: entries.firstOrNull()?.id
        return SavedProfiles(entries, selected)
    }

    @Synchronized fun add(name: String, provider: String, configuration: String): SavedProfiles {
        val previous = read()
        require(previous.entries.size < 100)
        require(configuration.isNotBlank() && configuration.length <= 65536)
        val record = StoredProfile(
            UUID.randomUUID().toString(), name.take(100), provider.take(50), configuration
        )
        return SavedProfiles(previous.entries + record, record.id).also(::write)
    }

    @Synchronized fun select(id: String): SavedProfiles {
        val previous = read()
        require(previous.entries.any { it.id == id })
        return previous.copy(selectedId = id).also(::write)
    }

    @Synchronized fun remove(id: String): SavedProfiles {
        val previous = read()
        val remaining = previous.entries.filterNot { it.id == id }
        val selected = if (previous.selectedId == id) remaining.firstOrNull()?.id
            else previous.selectedId
        return SavedProfiles(remaining, selected).also(::write)
    }

    private fun write(profiles: SavedProfiles) {
        val arr = JSONArray()
        profiles.entries.forEach { p ->
            arr.put(JSONObject()
                .put("id", p.id)
                .put("name", p.name)
                .put("provider", p.provider)
                .put("configuration", p.configuration))
        }
        val text = JSONObject().put("version", 2)
            .put("selectedId", profiles.selectedId ?: "")
            .put("profiles", arr).toString()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val data = byteArrayOf(1) + cipher.iv +
            cipher.doFinal(text.toByteArray(StandardCharsets.UTF_8))
        val atomic = AtomicFile(file)
        var out: java.io.FileOutputStream? = null
        try {
            out = atomic.startWrite()
            out.write(data)
            atomic.finishWrite(out)
        } catch (e: Exception) {
            if (out != null) atomic.failWrite(out)
            throw e
        }
    }

    private fun decrypt(file: File): String {
        val bytes = AtomicFile(file).openRead().use { it.readBytes() }
        require(bytes.size > 29 && bytes[0] == 1.toByte())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(),
            GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
        return String(cipher.doFinal(bytes.copyOfRange(13, bytes.size)), StandardCharsets.UTF_8)
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val saved = ks.getKey(alias, null) as? SecretKey
        if (saved != null) return saved
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build())
        return gen.generateKey()
    }
}
