package com.v2ray.ang

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.MmkvManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class MoghimiLicenseUiState(
    val isRegistered: Boolean = false,
    val username: String = "",
    val phone: String = "",
    val freeUsedBytes: Long = 0,
    val freeLimitBytes: Long = 5L * 1024 * 1024 * 1024,
    val freeRemainingBytes: Long = 5L * 1024 * 1024 * 1024,
    val paidRemainingBytes: Long = 0,
    val paidExpiresAt: String? = null,
    val accessAllowed: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
    val updateAvailable: Boolean = false,
    val latestVersionName: String? = null,
    val latestVersionCode: Long = 0,
    val latestApkUrl: String? = null,
    val latestSha256: String? = null,
    val releaseNotes: String? = null
) {
    val totalRemainingBytes: Long
        get() = (freeRemainingBytes + paidRemainingBytes).coerceAtLeast(0)
}

object MoghimiLicenseManager {
    private const val API_URL = "https://qaxiunpklkbgfjwmpwfp.supabase.co/functions/v1/moghimi-api"
    private const val API_KEY = "sb_publishable_tqUEU2cyQTgE4HUTR3_UYA_15fGU_Zf"
    private const val PREFS = "moghimi_license_v1"
    private const val KEY_ALIAS = "moghimi_license_aes_v1"
    private const val TOKEN_IV = "token_iv"
    private const val TOKEN_CIPHER = "token_cipher"
    private const val VERIFIED_AT = "verified_at"
    private const val CACHE_STATUS = "cache_status"
    private const val CACHE_RELEASE = "cache_release"
    private const val OFFLINE_GRACE_MS = 60_000L

    private val mutableState = MutableStateFlow(MoghimiLicenseUiState())
    val state: StateFlow<MoghimiLicenseUiState> = mutableState.asStateFlow()

    private val meterLock = Any()
    private var meterSessionId: String? = null
    private var meterSequence = 0L
    private var meterRxTotal = 0L
    private var meterTxTotal = 0L
    private var localRemainingBytes = Long.MAX_VALUE

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun sha256(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun deviceHash(context: Context): String {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?.takeIf { it.isNotBlank() && it != "9774d56d682e549c" }
            ?: throw IllegalStateException("شناسه امن دستگاه در دسترس نیست.")
        return sha256(androidId + "|" + context.packageName + "|MOGHIMI_DEVICE_V1")
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun encryptionKey(): SecretKey {
        val store = keyStore()
        val existing = store.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    private fun generateToken(): String {
        val raw = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return Base64.encodeToString(raw, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun loadToken(context: Context): String? {
        val p = prefs(context)
        val ivText = p.getString(TOKEN_IV, null) ?: return null
        val cipherText = p.getString(TOKEN_CIPHER, null) ?: return null
        return runCatching {
            val iv = Base64.decode(ivText, Base64.NO_WRAP)
            val encrypted = Base64.decode(cipherText, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun saveToken(context: Context, token: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
        val encrypted = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        prefs(context).edit()
            .putString(TOKEN_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(TOKEN_CIPHER, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .apply()
    }

    fun hasProfile(context: Context): Boolean =
        !prefs(context).getString("username", null).isNullOrBlank() && !loadToken(context).isNullOrBlank()

    fun username(context: Context): String = prefs(context).getString("username", "").orEmpty()

    fun phone(context: Context): String = prefs(context).getString("phone", "").orEmpty()

    private fun callApi(body: JSONObject): JSONObject {
        val connection = (URL(API_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 4_000
            readTimeout = 5_000
            doOutput = true
            useCaches = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            // Publishable key only; no service-role or database secret is shipped in the APK.
            setRequestProperty("apikey", API_KEY)
            setRequestProperty("User-Agent", "MoghimiV2Ray-Android/2.0.1")
        }
        try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val result = runCatching { JSONObject(text) }.getOrElse {
                throw IllegalStateException("پاسخ سرویس مجوز معتبر نیست.")
            }
            if (status !in 200..299) {
                throw IllegalStateException(errorMessage(result.optString("code"), status))
            }
            return result
        } finally {
            connection.disconnect()
        }
    }

    private fun errorMessage(code: String, status: Int): String = when (code) {
        "username_or_device_already_registered" -> "این نام کاربری قبلاً روی دستگاه دیگری ثبت شده است."
        "username_mismatch" -> "نام کاربری با حساب ثبت‌شده مطابقت ندارد."
        "phone_mismatch" -> "شماره موبایل با پروفایل ثبت‌شده مطابقت ندارد."
        "device_blocked" -> "دسترسی این دستگاه غیرفعال شده است."
        "invalid_or_expired_code" -> "کد فعال‌سازی نامعتبر یا منقضی شده است."
        "username_mismatch" -> "نام کاربری با سفارش تلگرام یکسان نیست."
        else -> "ارتباط با سرویس اشتراک برقرار نشد (کد $status)."
    }

    private fun baseRequest(context: Context, action: String): JSONObject {
        val token = loadToken(context) ?: throw IllegalStateException("پروفایل دستگاه ثبت نشده است.")
        return JSONObject()
            .put("action", action)
            .put("device_hash", deviceHash(context))
            .put("device_token", token)
    }

    fun register(context: Context, usernameInput: String, phoneInput: String?): MoghimiLicenseUiState {
        val username = usernameInput.trim().removePrefix("@")
        val phone = phoneInput?.trim().orEmpty().ifBlank { null }
        require(Regex("^[A-Za-z0-9_]{5,32}$").matches(username)) {
            "نام کاربری باید ۵ تا ۳۲ نویسه انگلیسی، عدد یا _ باشد."
        }
        require(phone == null || Regex("^09[0-9]{9}$").matches(phone)) {
            "شماره موبایل باید با ۰۹ شروع شود و ۱۱ رقم باشد."
        }

        mutableState.value = mutableState.value.copy(isLoading = true, error = null)
        val token = loadToken(context) ?: generateToken()
        val response = JSONObject()
            .put("action", "register")
            .put("device_hash", deviceHash(context))
            .put("device_token", token)
            .put("username", username)
            .put("phone", phone ?: JSONObject.NULL)
        val result = callApi(response)
        if (!result.optBoolean("ok")) {
            throw IllegalStateException(errorMessage(result.optString("code"), 409))
        }

        saveToken(context, token)
        prefs(context).edit()
            .putString("username", username)
            .putString("phone", phone.orEmpty())
            .apply()
        mutableState.value = mutableState.value.copy(isRegistered = true, username = username, phone = phone.orEmpty())
        return refreshStatus(context)
    }

    fun refreshStatus(context: Context): MoghimiLicenseUiState {
        if (!hasProfile(context)) {
            val empty = MoghimiLicenseUiState()
            mutableState.value = empty
            return empty
        }
        mutableState.value = mutableState.value.copy(isLoading = true, error = null)
        return try {
            val result = callApi(baseRequest(context, "status"))
            if (!result.optBoolean("ok")) {
                throw IllegalStateException(errorMessage(result.optString("code"), 403))
            }
            val updated = parseStatus(context, result)
            mutableState.value = updated
            prefs(context).edit()
                .putLong(VERIFIED_AT, System.currentTimeMillis())
                .putString(CACHE_STATUS, result.toString())
                .apply()
            synchronized(meterLock) {
                localRemainingBytes = updated.totalRemainingBytes
            }
            updated
        } catch (error: Exception) {
            val cached = loadCachedStatus(context)
            val age = System.currentTimeMillis() - prefs(context).getLong(VERIFIED_AT, 0L)
            val usableCache = cached != null && age in 0..OFFLINE_GRACE_MS && cached.accessAllowed
            val fallback = (cached ?: mutableState.value).copy(
                isLoading = false,
                error = error.localizedMessage ?: "دریافت وضعیت اشتراک انجام نشد."
            )
            mutableState.value = if (!usableCache) fallback.copy(accessAllowed = false) else fallback
            mutableState.value
        }
    }

    private fun parseStatus(context: Context, result: JSONObject): MoghimiLicenseUiState {
        return MoghimiLicenseUiState(
            isRegistered = true,
            username = result.optString("username", username(context)),
            phone = if (result.isNull("phone")) phone(context) else result.optString("phone"),
            freeUsedBytes = result.optLong("free_used_bytes"),
            freeLimitBytes = result.optLong("free_limit_bytes", 5L * 1024 * 1024 * 1024),
            freeRemainingBytes = result.optLong("free_remaining_bytes"),
            paidRemainingBytes = result.optLong("paid_remaining_bytes"),
            paidExpiresAt = result.optString("paid_expires_at").takeIf { it.isNotBlank() && it != "null" },
            accessAllowed = result.optBoolean("access_allowed")
        )
    }

    private fun loadCachedStatus(context: Context): MoghimiLicenseUiState? {
        val raw = prefs(context).getString(CACHE_STATUS, null) ?: return null
        return runCatching { parseStatus(context, JSONObject(raw)) }.getOrNull()
    }

    fun claimActivationCode(context: Context, code: String): MoghimiLicenseUiState {
        val request = baseRequest(context, "claim").put("activation_code", code.trim())
        val result = callApi(request)
        if (!result.optBoolean("ok")) {
            throw IllegalStateException(errorMessage(result.optString("code"), 409))
        }
        return refreshStatus(context)
    }

    fun beginUsageSession(context: Context) {
        synchronized(meterLock) {
            meterSessionId = UUID.randomUUID().toString()
            meterSequence = 0L
            meterRxTotal = 0L
            meterTxTotal = 0L
            localRemainingBytes = mutableState.value.totalRemainingBytes
        }
    }

    /**
     * Xray exposes byte counters per outbound. CoreServiceManager supplies the sum of
     * upload and download deltas excluding blocked traffic. The backend is authoritative;
     * a local remaining-byte guard stops the tunnel if reporting temporarily goes offline.
     */
    fun recordTraffic(context: Context, rxDelta: Long, txDelta: Long): Boolean {
        if (rxDelta <= 0 && txDelta <= 0) return mutableState.value.accessAllowed
        val session: String
        val sequence: Long
        val rx: Long
        val tx: Long
        synchronized(meterLock) {
            if (meterSessionId.isNullOrBlank()) {
                meterSessionId = UUID.randomUUID().toString()
                meterSequence = 0L
                meterRxTotal = 0L
                meterTxTotal = 0L
                localRemainingBytes = mutableState.value.totalRemainingBytes
            }
            meterRxTotal += rxDelta.coerceAtLeast(0)
            meterTxTotal += txDelta.coerceAtLeast(0)
            localRemainingBytes = (localRemainingBytes - rxDelta.coerceAtLeast(0) - txDelta.coerceAtLeast(0)).coerceAtLeast(0)
            session = meterSessionId!!
            sequence = meterSequence + 1
            rx = meterRxTotal
            tx = meterTxTotal
        }

        return try {
            val request = baseRequest(context, "usage")
                .put("session_id", session)
                .put("sequence", sequence)
                .put("rx_total", rx)
                .put("tx_total", tx)
            val result = callApi(request)
            val accepted = result.optBoolean("ok") && (result.optBoolean("duplicate") || result.has("accepted_bytes"))
            if (!accepted) throw IllegalStateException(errorMessage(result.optString("code"), 409))

            val updated = MoghimiLicenseUiState(
                isRegistered = true,
                username = username(context),
                phone = phone(context),
                freeUsedBytes = result.optLong("free_used_bytes"),
                freeLimitBytes = result.optLong("free_limit_bytes", 5L * 1024 * 1024 * 1024),
                freeRemainingBytes = result.optLong("free_remaining_bytes"),
                paidRemainingBytes = result.optLong("paid_remaining_bytes"),
                paidExpiresAt = result.optString("paid_expires_at").takeIf { it.isNotBlank() && it != "null" },
                accessAllowed = result.optBoolean("access_allowed")
            )
            synchronized(meterLock) {
                meterSequence = sequence
                localRemainingBytes = updated.totalRemainingBytes
            }
            mutableState.value = updated
            if (!updated.accessAllowed) stopTunnelForQuota(context)
            updated.accessAllowed
        } catch (error: Exception) {
            val allowed = synchronized(meterLock) { localRemainingBytes > 0 && mutableState.value.accessAllowed }
            if (!allowed) {
                stopTunnelForQuota(context)
                false
            } else {
                true
            }
        }
    }

    private fun stopTunnelForQuota(context: Context) {
        runCatching {
            val intent = Intent(AppConfig.BROADCAST_ACTION_SERVICE).apply {
                setPackage(AppConfig.ANG_PACKAGE)
                putExtra("key", AppConfig.MSG_STATE_STOP)
            }
            context.applicationContext.sendBroadcast(intent)
        }
    }

    fun canStartConnection(context: Context): Boolean {
        if (!hasProfile(context)) return false
        val updated = refreshStatus(context)
        return updated.accessAllowed && updated.totalRemainingBytes > 0
    }

    fun formatBytes(value: Long): String {
        val n = value.coerceAtLeast(0)
        val gb = 1024.0 * 1024 * 1024
        val mb = 1024.0 * 1024
        return when {
            n >= gb -> String.format(java.util.Locale.US, "%.2f GB", n / gb)
            n >= mb -> String.format(java.util.Locale.US, "%.1f MB", n / mb)
            else -> String.format(java.util.Locale.US, "%.0f KB", n / 1024.0)
        }
    }
}
