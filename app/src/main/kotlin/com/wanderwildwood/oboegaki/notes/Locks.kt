package com.wanderwildwood.oboegaki.notes

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The phone's side of locked notes ([Locked.kt]): where they are kept, the keystore's seal on the
 * vault, and the count of wrong PINs. All of it in the app's own storage under files/locked, which
 * no other app can read, which no backup or phone-to-phone transfer takes (backups are off for the
 * whole app), and which the notes' FileProvider does not reach.
 */
object Locks {

    private lateinit var appContext: Context

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        reload()
    }

    private val dir get() = File(appContext.filesDir, "locked")
    private val vault get() = Vault(File(dir, "vault"), KeystoreWrap)
    val store: LockedStore get() = LockedStore(File(dir, "notes"))

    private val prefs get() = appContext.getSharedPreferences("locked", Context.MODE_PRIVATE)
    private val attempts get() = Attempts(
        load = { prefs.getInt("wrong", 0) to prefs.getLong("until", 0L) },
        save = { n, until -> prefs.edit().putInt("wrong", n).putLong("until", until).apply() },
    )

    private val _list = MutableStateFlow<List<LockedNote>>(emptyList())
    /** The locked notes, the last changed first. */
    val list: StateFlow<List<LockedNote>> = _list

    fun reload() {
        _list.value = runCatching { store.list() }.getOrDefault(emptyList())
    }

    /** Whether a PIN has been set. */
    fun hasPin(): Boolean = vault.exists()

    /** What a PIN tried came to. */
    sealed interface Tried {
        /** The note key, held only while a locked note is open or being locked. */
        class Right(val key: ByteArray) : Tried
        data object Wrong : Tried
        /** Too many wrong ones: none is tried for this long. */
        data class Wait(val ms: Long) : Tried
        /** The vault could not be read: the keystore would not open it. */
        data object Broken : Tried
    }

    /** Try [pin]. Slow on purpose (the PIN is stretched), so never on the main thread. */
    fun tryPin(pin: String): Tried {
        val wait = attempts.waitMs()
        if (wait > 0) return Tried.Wait(wait)
        val key = try {
            vault.open(pin)
        } catch (_: Exception) {
            return Tried.Broken
        }
        return if (key != null) {
            attempts.succeeded()
            Tried.Right(key)
        } else {
            attempts.failed()
            attempts.waitMs().takeIf { it > 0 }?.let { Tried.Wait(it) } ?: Tried.Wrong
        }
    }

    /** The first PIN: a new vault and note key. Null when the keystore would not take it. */
    fun setPin(pin: String): ByteArray? = runCatching { vault.create(pin) }.getOrNull()

    /** A new PIN for the same locked notes; [Tried.Right] when it is changed. */
    fun changePin(old: String, new: String): Tried {
        val tried = tryPin(old)
        if (tried !is Tried.Right) return tried
        tried.key.fill(0)
        return if (runCatching { vault.changePin(old, new) }.getOrDefault(false)) tried else Tried.Broken
    }

    /** Every locked note and the PIN gone for good: the only way on from a forgotten PIN. */
    fun deleteAll() {
        store.deleteAll()
        vault.forget()
        attempts.succeeded()
        reload()
    }

    /**
     * The vault sealed once more with a key made inside the phone's keystore, which never leaves
     * it: copied off the phone, the vault cannot be tried against every PIN. `iv + ciphertext`.
     */
    private object KeystoreWrap : KeyWrap {
        private const val ALIAS = "oboegaki.locked.v1"
        private const val IV_BYTES = 12

        override fun seal(bytes: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            return cipher.iv + cipher.doFinal(bytes)
        }

        override fun open(bytes: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, IV_BYTES))
            return cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES)
        }

        private fun key(): SecretKey {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            generator.init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            return generator.generateKey()
        }
    }
}
