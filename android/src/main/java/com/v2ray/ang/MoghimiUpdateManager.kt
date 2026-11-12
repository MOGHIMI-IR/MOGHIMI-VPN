package com.v2ray.ang

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.v2ray.ang.ui.main.MainActivity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

/**
 * Checks the Moghimi V2Ray GitHub release feed and downloads only the ARM64 APK
 * from the configured Supabase Storage object.
 *
 * GitHub is used for release metadata and the published SHA-256 digest only.
 * The APK binary itself is always fetched from the HTTPS Supabase URL below.
 */
object MoghimiUpdateManager {
    const val EXTRA_OPEN_UPDATE = "com.v2ray.ang.extra.OPEN_MOGHIMI_UPDATE"
    const val ARM64_APK_URL = ""

    private const val RELEASES_API =
        "https://api.github.com/repos/MOGHIMI-IR/MOGHIMI-VPN/releases?per_page=100"
    private const val APK_ASSET_NAME = "MoghimiV2Ray_1.0.0_arm64-v8a.apk"
    private const val BUILD_NUMBER_MODULUS = 1_000_000L
    private const val MAX_APK_SIZE_BYTES = 160L * 1024L * 1024L
    private const val PREFS_NAME = "moghimi_update_state"
    private const val KEY_NOTIFIED_BUILD = "notified_build"
    private const val KEY_PROMPTED_BUILD = "prompted_build"
    private const val CHANNEL_ID = "moghimi_app_updates"
    private const val NOTIFICATION_ID = 71002

    data class UpdateInfo(
        val buildNumber: Long,
        val versionName: String,
        val tagName: String,
        val sha256: String,
        val releaseNotes: String,
        val releaseUrl: String
    )

    /**
     * Returns an update only when the latest published release is newer than this
     * installation. ABI split version codes include a 4,000,000 offset in this app;
     * the final six digits are the CI run number used in the release tag.
     */
    fun checkForUpdate(context: Context): UpdateInfo? {
        if (!hasConfiguredDownloadUrl()) return null
        val response = requestText(
            RELEASES_API,
            accept = "application/vnd.github+json"
        )
        val releases = JSONArray(response)
        val buildTagPattern = Regex(
            "^moghimi-beta-([0-9]+)$",
            RegexOption.IGNORE_CASE
        )
        var release: JSONObject? = null
        var buildNumber = -1L

        // GitHub's release listing is ordered by creation date, which can differ from
        // our CI build number. Always select the numerically newest published Moghimi build.
        for (index in 0 until releases.length()) {
            val candidate = releases.optJSONObject(index) ?: continue
            if (candidate.optBoolean("draft", false)) continue
            val candidateBuild = buildTagPattern.find(candidate.optString("tag_name"))
                ?.groupValues?.getOrNull(1)?.toLongOrNull() ?: continue
            if (candidateBuild > buildNumber) {
                buildNumber = candidateBuild
                release = candidate
            }
        }

        val latestRelease = release ?: return null
        if (buildNumber <= installedBuildNumber(context)) return null

        val assets = latestRelease.optJSONArray("assets")
            ?: throw IOException("فهرست فایل‌های انتشار در دسترس نیست.")
        val apkAsset = findAsset(assets, APK_ASSET_NAME)
            ?: throw IOException("فایل ARM64 در انتشار جدید پیدا نشد.")

        val checksum = resolveChecksum(apkAsset, assets)
        if (!checksum.matches(Regex("^[0-9a-f]{64}$"))) {
            throw IOException("مقدار SHA-256 نسخه جدید معتبر نیست.")
        }

        val rawNotes = latestRelease.optString("body").trim()
        val notes = rawNotes.ifBlank {
            "بهبودها و رفع اشکال‌های نسخه جدید Moghimi V2Ray."
        }.take(700)

        return UpdateInfo(
            buildNumber = buildNumber,
            versionName = APK_ASSET_NAME
                .removePrefix("MoghimiV2Ray_")
                .removeSuffix("_arm64-v8a.apk"),
            tagName = latestRelease.optString("tag_name"),
            sha256 = checksum,
            releaseNotes = notes,
            releaseUrl = latestRelease.optString("html_url")
        )
    }

    fun shouldPrompt(context: Context, info: UpdateInfo): Boolean =
        preferences(context).getLong(KEY_PROMPTED_BUILD, 0L) < info.buildNumber

    fun markPrompted(context: Context, info: UpdateInfo) {
        preferences(context).edit().putLong(KEY_PROMPTED_BUILD, info.buildNumber).apply()
    }

    /**
     * Posts one notification for each new build. It does not mark a build as notified
     * when Android notification permission is unavailable, so a later check can retry.
     */
    fun notifyIfNew(context: Context, info: UpdateInfo) {
        val notificationManager = NotificationManagerCompat.from(context)
        if (!notificationManager.areNotificationsEnabled()) return

        if (
            Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val prefs = preferences(context)
        if (prefs.getLong(KEY_NOTIFIED_BUILD, 0L) >= info.buildNumber) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val systemManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "به‌روزرسانی‌های مقیمی",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "اعلان انتشار نسخه‌های جدید برنامه"
            }
            systemManager.createNotificationChannel(channel)
        }

        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            action = EXTRA_OPEN_UPDATE
            putExtra(EXTRA_OPEN_UPDATE, true)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            71002,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("نسخه جدید مقیمی V2Ray منتشر شد")
            .setContentText("نسخه " + info.versionName + " آماده دریافت است.")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "نسخه " + info.versionName +
                        " منتشر شده است. برای بررسی و به‌روزرسانی برنامه لمس کنید."
                )
            )
            .setContentIntent(pendingIntent)
            .addAction(
                android.R.drawable.stat_sys_download,
                "بررسی و دانلود",
                pendingIntent
            )
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        try {
            notificationManager.notify(NOTIFICATION_ID, notification)
            prefs.edit().putLong(KEY_NOTIFIED_BUILD, info.buildNumber).apply()
        } catch (_: SecurityException) {
            // Notification permission may have changed between the permission check and post.
        }
    }

    fun hasConfiguredDownloadUrl(): Boolean = configuredDownloadUrl() != null

    /**
     * Downloads the configured Supabase object, verifies it against the digest published
     * by the corresponding GitHub release, and returns a file in app-private cache.
     */
    fun downloadArm64Apk(
        context: Context,
        info: UpdateInfo,
        onProgress: (Int) -> Unit
    ): File {
        val downloadUrl = configuredDownloadUrl()
            ?: throw IOException("لینک دانلود ARM64 سوپابیس هنوز تنظیم نشده است.")

        val connection = (URL(downloadUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/vnd.android.package-archive, application/octet-stream")
            setRequestProperty("User-Agent", "MoghimiV2Ray-Android-Updater")
        }

        val updateDirectory = File(context.cacheDir, "moghimi-update")
        if (!updateDirectory.exists() && !updateDirectory.mkdirs()) {
            connection.disconnect()
            throw IOException("ساخت پوشه موقت به‌روزرسانی ناموفق بود.")
        }

        val partialFile = File(updateDirectory, APK_ASSET_NAME + ".part")
        val finalFile = File(updateDirectory, APK_ASSET_NAME)
        partialFile.delete()

        try {
            val status = connection.responseCode
            if (status !in 200..299) {
                throw IOException("دریافت فایل از سوپابیس ناموفق بود (HTTP " + status + ").")
            }

            val contentType = connection.contentType.orEmpty().lowercase(Locale.US)
            if (contentType.contains("text/html") || contentType.contains("application/json")) {
                throw IOException("سوپابیس به‌جای فایل APK، محتوای نامعتبر برگرداند.")
            }

            val expectedLength = connection.contentLengthLong
            if (expectedLength > MAX_APK_SIZE_BYTES) {
                throw IOException("حجم فایل APK از حد مجاز بیشتر است.")
            }

            val digest = MessageDigest.getInstance("SHA-256")
            var downloaded = 0L

            connection.inputStream.use { input ->
                FileOutputStream(partialFile).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        downloaded += count
                        if (downloaded > MAX_APK_SIZE_BYTES) {
                            throw IOException("حجم فایل دریافتی بیش از حد مجاز است.")
                        }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                        if (expectedLength > 0L) {
                            onProgress(
                                ((downloaded * 100L) / expectedLength)
                                    .toInt()
                                    .coerceIn(0, 100)
                            )
                        }
                    }
                    output.fd.sync()
                }
            }

            if (expectedLength > 0L && downloaded != expectedLength) {
                throw IOException("دانلود کامل نشد؛ اندازه فایل دریافتی مطابقت ندارد.")
            }
            if (downloaded < 1_000_000L) {
                throw IOException("فایل دریافتی بیش از حد کوچک است و APK معتبر به نظر نمی‌رسد.")
            }

            val actualDigest = digest.digest().joinToString("") {
                "%02x".format(it.toInt() and 0xff)
            }
            if (!actualDigest.equals(info.sha256, ignoreCase = true)) {
                throw IOException("بررسی سلامت فایل ناموفق بود؛ فایل سوپابیس با انتشار جدید یکسان نیست.")
            }

            if (finalFile.exists() && !finalFile.delete()) {
                throw IOException("پاک‌سازی فایل به‌روزرسانی قبلی ناموفق بود.")
            }
            if (!partialFile.renameTo(finalFile)) {
                partialFile.copyTo(finalFile, overwrite = true)
                partialFile.delete()
            }

            verifyDownloadedApk(context, info, finalFile)
            onProgress(100)
            return finalFile
        } catch (error: Exception) {
            partialFile.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }


    /**
     * Validate the file before opening Android's installer. The SHA-256 check is performed
     * during download; these checks verify that it is the expected Moghimi app/build/signer.
     */
    private fun verifyDownloadedApk(context: Context, info: UpdateInfo, apkFile: File) {
        val flags = if (Build.VERSION.SDK_INT >= 28) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        val archive = context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, flags)
            ?: throw IOException("فایل دریافتی ساختار APK معتبر ندارد.")

        if (archive.packageName != context.packageName) {
            throw IOException("این فایل متعلق به برنامه مقیمی نیست؛ نصب متوقف شد.")
        }
        if (archive.versionName != info.versionName) {
            throw IOException("نسخه داخل فایل با نسخه انتشار GitHub مطابقت ندارد.")
        }

        val archiveCode = if (Build.VERSION.SDK_INT >= 28) {
            archive.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            archive.versionCode.toLong()
        }
        if (archiveCode % BUILD_NUMBER_MODULUS != info.buildNumber) {
            throw IOException("شماره بیلد فایل APK با انتشار جدید مطابقت ندارد.")
        }

        val installedInfo = context.packageManager.getPackageInfo(context.packageName, flags)
        val installedSigners = signingCertificateDigests(installedInfo)
        val downloadedSigners = signingCertificateDigests(archive)
        if (installedSigners.isEmpty() || downloadedSigners.isEmpty() ||
            installedSigners != downloadedSigners
        ) {
            throw IOException(
                "امضای این APK با نسخه نصب‌شده متفاوت است. برای به‌روزرسانی مستقیم، " +
                    "نسخه‌ها باید با یک کلید امضای ثابت ساخته شوند."
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun signingCertificateDigests(packageInfo: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            packageInfo.signingInfo?.apkContentsSigners?.toList().orEmpty()
        } else {
            packageInfo.signatures?.toList().orEmpty()
        }
        return signatures.map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }.toSet()
    }

    private fun configuredDownloadUrl(): String? {
        val value = ARM64_APK_URL.trim()
        if (value.isBlank()) return null
        val parsed = runCatching { java.net.URI(value) }.getOrNull() ?: return null
        if (!parsed.scheme.equals("https", ignoreCase = true) || parsed.host.isNullOrBlank()) {
            return null
        }
        return value
    }

    private fun installedBuildNumber(context: Context): Long {
        val packageInfo: PackageInfo = if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageInfo(
                context.packageName,
                android.content.pm.PackageManager.PackageInfoFlags.of(0L)
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
        val code = if (Build.VERSION.SDK_INT >= 28) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }
        return code % BUILD_NUMBER_MODULUS
    }

    private fun resolveChecksum(apkAsset: JSONObject, assets: JSONArray): String {
        val digest = apkAsset.optString("digest")
            .removePrefix("sha256:")
            .lowercase(Locale.US)
        if (digest.matches(Regex("^[0-9a-f]{64}$"))) return digest

        val checksumAsset = findAsset(assets, "SHA256SUMS")
            ?: throw IOException("فایل بررسی سلامت نسخه در انتشار وجود ندارد.")
        val checksumUrl = checksumAsset.optString("browser_download_url")
        if (!checksumUrl.startsWith("https://")) {
            throw IOException("نشانی فایل بررسی سلامت معتبر نیست.")
        }
        val checksumText = requestText(checksumUrl, accept = "text/plain")
        val line = checksumText.lineSequence().firstOrNull {
            it.contains(APK_ASSET_NAME)
        } ?: throw IOException("هش فایل ARM64 در فهرست بررسی پیدا نشد.")
        val candidate = line.trim().split(Regex("\\s+")).firstOrNull().orEmpty()
        return candidate.lowercase(Locale.US)
    }

    private fun findAsset(assets: JSONArray, name: String): JSONObject? {
        for (index in 0 until assets.length()) {
            val asset = assets.optJSONObject(index) ?: continue
            if (asset.optString("name") == name) return asset
        }
        return null
    }

    private fun requestText(address: String, accept: String): String {
        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 9_000
            readTimeout = 12_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", accept)
            setRequestProperty("User-Agent", "MoghimiV2Ray-Android-Updater")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        }
        try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                throw IOException("بررسی نسخه جدید ناموفق بود (HTTP " + status + ").")
            }
            return body
        } finally {
            connection.disconnect()
        }
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
