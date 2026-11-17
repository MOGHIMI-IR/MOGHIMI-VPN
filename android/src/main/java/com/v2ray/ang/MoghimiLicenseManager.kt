package com.v2ray.ang

import android.content.Context
import android.content.Intent
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
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

private const val MOGHIMI_FREE_LIMIT_BYTES = 7L * 1024 * 1024 * 1024

data class MoghimiLicenseUiState(
    val isRegistered: Boolean = false,
    val username: String = "",
    val phone: String = "",
    val freeUsedBytes: Long = 0,
    val freeLimitBytes: Long = MOGHIMI_FREE_LIMIT_BYTES,
    val freeRemainingBytes: Long = MOGHIMI_FREE_LIMIT_BYTES,
    val paidRemainingBytes: Long = 0,
    val paidUsedBytes: Long = 0,
    val paidGrantedBytes: Long = 0,
    val paidExpiresAt: String? = null,
    val accessAllowed: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
    val networkAvailable: Boolean = true,
    val isServerVerified: Boolean = false,
    val lastVerifiedAtMs: Long = 0L,
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
    private const val STATUS_CACHE_TTL_MS = 20_000L
    private const val RELEASE_CACHE_TTL_MS = 60_000L
    private const val RELEASE_VERIFIED_AT = "release_verified_at"
    private const val UPDATE_CHANNEL_ID = "moghimi_app_updates"
    private const val UPDATE_NOTIFICATION_ID = 58024
    private const val SUPABASE_APK_PREFIX = "https://atrwhvyhopifugphfcpe.supabase.co/storage/v1/object/public/app-releases/"

    private val mutableState = MutableStateFlow(MoghimiLicenseUiState())
    val state: StateFlow<MoghimiLicenseUiState> = mutableState.asStateFlow()

    private val meterLock = Any()
    private var meterSessionId: String? = null
    private var meterSequence = 0L
    private var meterRxTotal = 0L
    private var meterTxTotal = 0L
    private var meterReportedRxTotal = 0L
    private var meterReportedTxTotal = 0L
    private var localRemainingBytes = Long.MAX_VALUE
    private var lastMeterEntitlementCheckMs = 0L
    private const val ENTITLEMENT_RECHECK_INTERVAL_MS = 60_000L
    private const val MAX_USAGE_REPORT_BYTES = 268435456L

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

    @Suppress("DEPRECATION")
    private fun installedVersionCode(context: Context): Long {
        val info = if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(0)
            )
        } else {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    }

    private fun notifyUpdateAvailable(context: Context, versionName: String, downloadUrl: String) {
        runCatching {
            val app = context.applicationContext
            val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        UPDATE_CHANNEL_ID,
                        "به‌روزرسانی مقیمی V2Ray",
                        NotificationManager.IMPORTANCE_HIGH
                    ).apply { description = "اعلان نسخه‌های جدید و اجباری برنامه" }
                )
            }
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
            val pending = PendingIntent.getActivity(app, UPDATE_NOTIFICATION_ID, intent, flags)
            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(app, UPDATE_CHANNEL_ID)
            } else {
                Notification.Builder(app)
            }
            val notification = builder
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("به‌روزرسانی اجباری مقیمی V2Ray")
                .setContentText("نسخهٔ $versionName آماده است؛ برای ادامهٔ اتصال، به‌روزرسانی کن.")
                .setStyle(
                    Notification.BigTextStyle().bigText(
                        "نسخهٔ $versionName منتشر شده است. نسخهٔ قدیمی اجازهٔ اتصال ندارد. " +
                            "لینک دانلود مستقیم از سرور Supabase:\\n$downloadUrl"
                    )
                )
                .setContentIntent(pending)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .build()
            manager.notify(UPDATE_NOTIFICATION_ID, notification)
        }
    }

    private fun applyReleaseInfo(context: Context, release: JSONObject): MoghimiLicenseUiState {
        val versionName = release.optString("version_name").trim()
        val versionCode = release.optLong("version_code")
        val url = release.optString("apk_url").trim()
        val sha = release.optString("sha256").trim()
        val available = url.startsWith(SUPABASE_APK_PREFIX) &&
            versionName.isNotBlank() && versionCode > installedVersionCode(context)
        val updated = mutableState.value.copy(
            updateAvailable = available,
            latestVersionName = versionName.ifBlank { null },
            latestVersionCode = versionCode,
            latestApkUrl = url.takeIf { it.startsWith(SUPABASE_APK_PREFIX) },
            latestSha256 = sha.ifBlank { null },
            releaseNotes = release.optString("release_notes").ifBlank { null }
        )
        mutableState.value = updated
        prefs(context).edit().putString(CACHE_RELEASE, release.toString()).apply()
        if (available) {
            val p = prefs(context)
            if (p.getLong("update_notified_version_code", -1L) != versionCode) {
                notifyUpdateAvailable(context, versionName, url)
                p.edit().putLong("update_notified_version_code", versionCode).apply()
            }
        }
        return updated
    }

    fun checkForUpdate(context: Context): MoghimiLicenseUiState {
        val appContext = context.applicationContext
        val p = prefs(appContext)
        val now = System.currentTimeMillis()
        val lastChecked = p.getLong(RELEASE_VERIFIED_AT, 0L)
        val cached = p.getString(CACHE_RELEASE, null)
        if (lastChecked > 0L && now >= lastChecked && now - lastChecked < RELEASE_CACHE_TTL_MS) {
            if (!cached.isNullOrBlank()) {
                return runCatching {
                    applyReleaseInfo(appContext, JSONObject(cached))
                }.getOrElse { mutableState.value }
            }
            val unchanged = mutableState.value.copy(
                updateAvailable = false,
                latestVersionName = null,
                latestVersionCode = 0,
                latestApkUrl = null,
                latestSha256 = null,
                releaseNotes = null
            )
            mutableState.value = unchanged
            return unchanged
        }
        return try {
            val response = callApi(JSONObject().put("action", "release"))
            if (!response.optBoolean("ok")) throw IllegalStateException("دریافت نسخهٔ جدید ناموفق بود.")
            val release = response.optJSONObject("release")
            p.edit().putLong(RELEASE_VERIFIED_AT, now).apply()
            if (release == null) {
                p.edit().remove(CACHE_RELEASE).apply()
                val unchanged = mutableState.value.copy(
                    updateAvailable = false, latestVersionName = null, latestVersionCode = 0,
                    latestApkUrl = null, latestSha256 = null, releaseNotes = null
                )
                mutableState.value = unchanged
                unchanged
            } else {
                applyReleaseInfo(appContext, release)
            }
        } catch (_: Exception) {
            if (cached.isNullOrBlank()) mutableState.value
            else runCatching { applyReleaseInfo(appContext, JSONObject(cached)) }.getOrElse { mutableState.value }
        }
    }

    /**
     * Restore this device's single server profile. Android Keystore tokens are erased
     * on uninstall, so a fresh token replaces the old token for this same Android ID.
     * The username is returned by the server and does not need to be remembered.
     */
    fun recoverProfile(context: Context): Boolean {
        mutableState.value = mutableState.value.copy(isLoading = true, error = null)
        val token = generateToken()
        return try {
            val result = callApi(
                JSONObject()
                    .put("action", "recover")
                    .put("device_hash", deviceHash(context))
                    .put("device_token", token)
            )
            if (!result.optBoolean("ok")) {
                throw IllegalStateException(errorMessage(result.optString("code"), 409))
            }
            val recoveredUsername = result.optString("username").trim()
            require(recoveredUsername.isNotBlank()) { "اطلاعات حساب قبلی از سرور دریافت نشد." }
            val recoveredPhone = if (result.isNull("phone")) "" else result.optString("phone")
            saveToken(context, token)
            prefs(context).edit()
                .putString("username", recoveredUsername)
                .putString("phone", recoveredPhone)
                .apply()
            mutableState.value = mutableState.value.copy(
                isRegistered = true, username = recoveredUsername, phone = recoveredPhone, error = null
            )
            refreshStatus(context)
            true
        } catch (error: Exception) {
            if (error.localizedMessage == errorMessage("device_not_registered", 404)) {
                mutableState.value = MoghimiLicenseUiState()
                false
            } else {
                mutableState.value = mutableState.value.copy(
                    isLoading = false,
                    error = error.localizedMessage ?: "بازیابی حساب دستگاه انجام نشد."
                )
                throw error
            }
        }
    }

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
            setRequestProperty("User-Agent", "MoghimiV2Ray-Android/2.0.7")
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
        "device_not_registered" -> "برای این دستگاه حساب قبلی ثبت نشده است."
        "update_required" -> "نسخهٔ برنامه قدیمی شده است. برای ادامه، نسخهٔ جدید را از سرور دانلود کن: https://atrwhvyhopifugphfcpe.supabase.co/storage/v1/object/public/app-releases/MoghimiV2Ray_1.0.0_arm64-v8a%20%281%29.apk"
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

    private fun hasInternetConnection(context: Context): Boolean {
        return runCatching {
            val manager = context.applicationContext
                .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = manager.activeNetwork ?: return@runCatching false
            val capabilities = manager.getNetworkCapabilities(network) ?: return@runCatching false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }.getOrDefault(false)
    }

    private fun noInternetState(context: Context): MoghimiLicenseUiState {
        val cached = loadCachedStatus(context)
        val previous = cached ?: mutableState.value
        return previous.copy(
            isRegistered = hasProfile(context),
            username = username(context).ifBlank { previous.username },
            phone = phone(context).ifBlank { previous.phone },
            isLoading = false,
            networkAvailable = false,
            error = "اینترنت قطع است؛ اتصال اینترنت را بررسی کنید و دوباره تلاش کنید."
        )
    }

    @Synchronized
    fun refreshStatus(context: Context, force: Boolean = false): MoghimiLicenseUiState {
        val appContext = context.applicationContext
        if (!hasProfile(appContext)) {
            val empty = MoghimiLicenseUiState(
                networkAvailable = hasInternetConnection(appContext)
            )
            mutableState.value = empty
            return empty
        }

        if (!hasInternetConnection(appContext)) {
            val offline = noInternetState(appContext)
            mutableState.value = offline
            return offline
        }

        val now = System.currentTimeMillis()
        val verifiedAt = prefs(appContext).getLong(VERIFIED_AT, 0L)
        if (!force && verifiedAt > 0L && now >= verifiedAt && now - verifiedAt < STATUS_CACHE_TTL_MS) {
            val cached = loadCachedStatus(appContext)
            if (cached != null) {
                val recent = cached.copy(
                    isLoading = false,
                    networkAvailable = true,
                    isServerVerified = true,
                    lastVerifiedAtMs = verifiedAt,
                    error = null
                )
                mutableState.value = recent
                return recent
            }
        }

        mutableState.value = mutableState.value.copy(
            isRegistered = true,
            isLoading = true,
            networkAvailable = true,
            error = null
        )
        return try {
            val result = callApi(baseRequest(appContext, "status"))
            if (!result.optBoolean("ok")) {
                val code = result.optString("code")
                if (code == "device_blocked" || code == "device_not_found") {
                    val denied = mutableState.value.copy(
                        isRegistered = true,
                        accessAllowed = false,
                        isLoading = false,
                        networkAvailable = true,
                        isServerVerified = true,
                        lastVerifiedAtMs = System.currentTimeMillis(),
                        error = errorMessage(code, 403)
                    )
                    mutableState.value = denied
                    return denied
                }
                throw IllegalStateException(errorMessage(code, 403))
            }
            val verifiedAtNow = System.currentTimeMillis()
            val updated = parseStatus(appContext, result).copy(
                networkAvailable = true,
                isServerVerified = true,
                lastVerifiedAtMs = verifiedAtNow,
                error = null
            )
            mutableState.value = updated
            prefs(appContext).edit()
                .putLong(VERIFIED_AT, verifiedAtNow)
                .putString(CACHE_STATUS, result.toString())
                .apply()
            synchronized(meterLock) {
                localRemainingBytes = minOf(localRemainingBytes, updated.totalRemainingBytes)
            }
            updated
        } catch (error: Exception) {
            val message = error.localizedMessage ?: "ارتباط با سرور بررسی اشتراک برقرار نشد."
            val isAuthoritativeDenial = message == errorMessage("device_blocked", 401) ||
                message == errorMessage("device_not_registered", 401) ||
                message == errorMessage("device_not_found", 403)
            if (isAuthoritativeDenial) {
                val denied = mutableState.value.copy(
                    isRegistered = true,
                    accessAllowed = false,
                    isLoading = false,
                    networkAvailable = true,
                    isServerVerified = true,
                    lastVerifiedAtMs = System.currentTimeMillis(),
                    error = message
                )
                mutableState.value = denied
                return denied
            }
            val cached = loadCachedStatus(appContext)
            val fallback = (cached ?: mutableState.value).copy(
                isRegistered = true,
                username = username(appContext),
                phone = phone(appContext),
                isLoading = false,
                networkAvailable = true,
                error = "سرور بررسی اشتراک فعلاً پاسخ نمی‌دهد؛ اتصال را دوباره بررسی کنید."
            )
            mutableState.value = fallback
            mutableState.value
        }
    }

    private fun parseStatus(context: Context, result: JSONObject): MoghimiLicenseUiState {
        return MoghimiLicenseUiState(
            isRegistered = true,
            username = result.optString("username", username(context)),
            phone = if (result.isNull("phone")) phone(context) else result.optString("phone"),
            freeUsedBytes = result.optLong("free_used_bytes"),
            freeLimitBytes = result.optLong("free_limit_bytes", MOGHIMI_FREE_LIMIT_BYTES),
            freeRemainingBytes = result.optLong("free_remaining_bytes"),
            paidRemainingBytes = result.optLong("paid_remaining_bytes"),
            paidUsedBytes = result.optLong("paid_used_bytes"),
            paidGrantedBytes = result.optLong("paid_granted_bytes"),
            paidExpiresAt = result.optString("paid_expires_at").takeIf { it.isNotBlank() && it != "null" },
            accessAllowed = result.optBoolean("access_allowed"),
            networkAvailable = true,
            isServerVerified = true,
            lastVerifiedAtMs = prefs(context).getLong(VERIFIED_AT, 0L),
            updateAvailable = mutableState.value.updateAvailable,
            latestVersionName = mutableState.value.latestVersionName,
            latestVersionCode = mutableState.value.latestVersionCode,
            latestApkUrl = mutableState.value.latestApkUrl,
            latestSha256 = mutableState.value.latestSha256,
            releaseNotes = mutableState.value.releaseNotes
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

    /**
     * A fresh service process must verify entitlement itself. The UI process and the
     * foreground VPN service do not share an in-memory StateFlow.
     */
    fun beginUsageSession(context: Context): Boolean {
        val verified = refreshStatus(context.applicationContext)
        synchronized(meterLock) {
            meterSessionId = UUID.randomUUID().toString()
            meterSequence = 0L
            meterRxTotal = 0L
            meterTxTotal = 0L
            meterReportedRxTotal = 0L
            meterReportedTxTotal = 0L
            localRemainingBytes = if (verified.isRegistered && verified.accessAllowed) {
                verified.totalRemainingBytes
            } else {
                0L
            }
            lastMeterEntitlementCheckMs = android.os.SystemClock.elapsedRealtime()
        }
        if (!verified.isRegistered || !verified.accessAllowed || verified.totalRemainingBytes <= 0L) {
            stopTunnelForQuota(context)
            return false
        }
        return true
    }

    /**
     * Receives byte deltas from Xray's reset-on-query counters. Upload and download
     * are accumulated locally, then reported using monotonic cumulative counters.
     * Reports are chunked below the server's 256 MiB safety ceiling so a slow network
     * cannot permanently poison a usage session with an oversized report.
     */
    fun recordTraffic(context: Context, rxDelta: Long, txDelta: Long): Boolean {
        if (rxDelta <= 0L && txDelta <= 0L) {
            val now = android.os.SystemClock.elapsedRealtime()
            val shouldRecheck = synchronized(meterLock) {
                if (now - lastMeterEntitlementCheckMs >= ENTITLEMENT_RECHECK_INTERVAL_MS) {
                    lastMeterEntitlementCheckMs = now
                    true
                } else {
                    false
                }
            }
            if (shouldRecheck) {
                // Check time expiry and administrative revocation even when the user
                // leaves the tunnel idle and no byte counter increments.
                val updated = refreshStatus(context.applicationContext)
                synchronized(meterLock) {
                    localRemainingBytes = minOf(localRemainingBytes, updated.totalRemainingBytes)
                }
            }
            val allowed = synchronized(meterLock) {
                localRemainingBytes > 0L && mutableState.value.accessAllowed
            }
            if (!allowed) stopTunnelForQuota(context)
            return allowed
        }

        var reachedLocalLimit = false
        synchronized(meterLock) {
            if (meterSessionId.isNullOrBlank()) {
                // This fallback is deliberately fail-closed. Normal startup calls
                // beginUsageSession() after validating the device on the service process.
                meterSessionId = UUID.randomUUID().toString()
                meterSequence = 0L
                meterRxTotal = 0L
                meterTxTotal = 0L
                meterReportedRxTotal = 0L
                meterReportedTxTotal = 0L
                localRemainingBytes = 0L
            }
            val before = localRemainingBytes
            meterRxTotal = Math.addExact(meterRxTotal, rxDelta.coerceAtLeast(0L))
            meterTxTotal = Math.addExact(meterTxTotal, txDelta.coerceAtLeast(0L))
            val consumed = Math.addExact(rxDelta.coerceAtLeast(0L), txDelta.coerceAtLeast(0L))
            localRemainingBytes = (localRemainingBytes - consumed).coerceAtLeast(0L)
            reachedLocalLimit = before > 0L && localRemainingBytes == 0L
        }

        // Stop the tunnel immediately when the last locally verified byte is observed.
        // The final measured counters are still submitted below for server accounting.
        if (reachedLocalLimit) stopTunnelForQuota(context)

        try {
            while (true) {
                val session: String
                val sequence: Long
                val reportRx: Long
                val reportTx: Long

                synchronized(meterLock) {
                    val pendingRx = (meterRxTotal - meterReportedRxTotal).coerceAtLeast(0L)
                    val pendingTx = (meterTxTotal - meterReportedTxTotal).coerceAtLeast(0L)
                    if (pendingRx == 0L && pendingTx == 0L) {
                        return mutableState.value.accessAllowed && localRemainingBytes > 0L
                    }

                    val rxChunk = minOf(pendingRx, MAX_USAGE_REPORT_BYTES)
                    val txChunk = minOf(pendingTx, MAX_USAGE_REPORT_BYTES - rxChunk)
                    sequence = meterSequence + 1L
                    reportRx = meterReportedRxTotal + rxChunk
                    reportTx = meterReportedTxTotal + txChunk
                    session = meterSessionId!!
                }

                val request = baseRequest(context, "usage")
                    .put("session_id", session)
                    .put("sequence", sequence)
                    .put("rx_total", reportRx)
                    .put("tx_total", reportTx)
                val result = callApi(request)
                val accepted = result.optBoolean("ok") &&
                    (result.optBoolean("duplicate") || result.has("accepted_bytes"))
                if (!accepted) {
                    throw IllegalStateException(errorMessage(result.optString("code"), 409))
                }

                val releaseState = mutableState.value
                val updated = MoghimiLicenseUiState(
                    isRegistered = true,
                    username = username(context),
                    phone = phone(context),
                    freeUsedBytes = result.optLong("free_used_bytes"),
                    freeLimitBytes = result.optLong("free_limit_bytes", MOGHIMI_FREE_LIMIT_BYTES),
                    freeRemainingBytes = result.optLong("free_remaining_bytes"),
                    paidRemainingBytes = result.optLong("paid_remaining_bytes"),
                    paidUsedBytes = result.optLong("paid_used_bytes"),
                    paidGrantedBytes = result.optLong("paid_granted_bytes"),
                    paidExpiresAt = result.optString("paid_expires_at")
                        .takeIf { it.isNotBlank() && it != "null" },
                    accessAllowed = result.optBoolean("access_allowed"),
                    updateAvailable = releaseState.updateAvailable,
                    latestVersionName = releaseState.latestVersionName,
                    latestVersionCode = releaseState.latestVersionCode,
                    latestApkUrl = releaseState.latestApkUrl,
                    latestSha256 = releaseState.latestSha256,
                    releaseNotes = releaseState.releaseNotes
                )

                synchronized(meterLock) {
                    meterSequence = sequence
                    meterReportedRxTotal = reportRx
                    meterReportedTxTotal = reportTx
                    localRemainingBytes = minOf(localRemainingBytes, updated.totalRemainingBytes)
                }
                mutableState.value = updated

                if (!updated.accessAllowed || updated.totalRemainingBytes <= 0L) {
                    stopTunnelForQuota(context)
                    return false
                }
            }
        } catch (error: Exception) {
            val allowed = synchronized(meterLock) {
                localRemainingBytes > 0L && mutableState.value.accessAllowed
            }
            if (!allowed) stopTunnelForQuota(context)
            // Unacknowledged cumulative bytes are retained in memory. The next meter
            // tick retries them with a chunk size accepted by the backend.
            return allowed
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
        val appContext = context.applicationContext
        if (!hasInternetConnection(appContext)) {
            mutableState.value = noInternetState(appContext)
            return false
        }
        if (!hasProfile(appContext)) return false
        val release = checkForUpdate(appContext)
        if (release.updateAvailable) return false
        val updated = refreshStatus(appContext)
        if (!updated.networkAvailable) return false
        if (updated.error != null) {
            val verifiedAt = prefs(appContext).getLong(VERIFIED_AT, 0L)
            val age = System.currentTimeMillis() - verifiedAt
            return updated.accessAllowed && age in 0..OFFLINE_GRACE_MS
        }
        return !updated.updateAvailable && updated.accessAllowed && updated.totalRemainingBytes > 0
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
