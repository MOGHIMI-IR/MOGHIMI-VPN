#!/usr/bin/env python3
from pathlib import Path
import shutil
import sys

if len(sys.argv) != 2:
    raise SystemExit("Usage: patch-updater.py <v2rayNG-project-dir>")

project = Path(sys.argv[1])
app = project / "app"
java = app / "src/main/java/com/v2ray/ang"

def replace_once(path: Path, old: str, new: str, label: str) -> None:
    source = path.read_text(encoding="utf-8")
    matches = source.count(old)
    if matches != 1:
        raise SystemExit(f"Expected one match for {label} in {path}; found {matches}")
    path.write_text(source.replace(old, new, 1), encoding="utf-8")

# Copy first-party Moghimi update checker and background worker into the upstream app.
source_dir = Path(__file__).parent / "src/main/java/com/v2ray/ang"
for name in ("MoghimiUpdateManager.kt", "MoghimiUpdateWorker.kt"):
    source = source_dir / name
    if not source.is_file():
        raise SystemExit(f"Missing updater source file: {source}")
    shutil.copy2(source, java / name)

# Android requires explicit user approval before a normal app can install an APK.
manifest = app / "src/main/AndroidManifest.xml"
replace_once(
    manifest,
    '<uses-permission android:name="android.permission.INTERNET" />',
    '<uses-permission android:name="android.permission.INTERNET" />\n'
    '    <uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />',
    "installer permission"
)

activity_file = java / "ui/main/MainActivity.kt"

# Imports for the update dialog, installer, notification worker and state.
replace_once(
    activity_file,
    "import android.content.Intent\n",
    """import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import com.v2ray.ang.MoghimiUpdateManager
import com.v2ray.ang.MoghimiUpdateWorker
""",
    "updater imports"
)

replace_once(
    activity_file,
    "    private var moghimiSyncRunning = false\n",
    """    private var moghimiSyncRunning = false
    private val updateInfoState = mutableStateOf<MoghimiUpdateManager.UpdateInfo?>(null)
    private val showUpdateDialogState = mutableStateOf(false)
    private val updateDownloadingState = mutableStateOf(false)
    private val updateProgressState = mutableIntStateOf(0)
    private val updateStatusState = mutableStateOf("")
    private var pendingMoghimiUpdateInstall: MoghimiUpdateManager.UpdateInfo? = null
""",
    "updater activity state"
)

replace_once(
    activity_file,
    "    private val profileEditorLauncher =\n",
    """    private val requestInstallSourcePermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val pending = pendingMoghimiUpdateInstall
            if (
                pending != null &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                packageManager.canRequestPackageInstalls()
            ) {
                pendingMoghimiUpdateInstall = null
                startMoghimiUpdateDownload(pending)
            } else {
                updateStatusState.value = "اجازه نصب از این برنامه فعال نشد؛ پس از فعال‌کردن مجوز دوباره تلاش کن."
            }
        }

    private val profileEditorLauncher =
""",
    "install permission launcher"
)

replace_once(
    activity_file,
    "        scheduleMoghimiBackgroundSync()\n",
    """        scheduleMoghimiBackgroundSync()
        scheduleMoghimiUpdateChecks()
        checkMoghimiUpdates(
            forcePrompt = intent.getBooleanExtra(MoghimiUpdateManager.EXTRA_OPEN_UPDATE, false)
        )
""",
    "update check at startup"
)

replace_once(
    activity_file,
    """            onNavigate = { route -> navigateTo(route) },
        )
    }

    private fun shareToClipboard(guid: String): Boolean =""",
    """            onNavigate = { route -> navigateTo(route) },
        )

        val updateToShow = updateInfoState.value
        if (showUpdateDialogState.value && updateToShow != null) {
            val isDownloading = updateDownloadingState.value
            AlertDialog(
                onDismissRequest = {
                    if (!isDownloading) showUpdateDialogState.value = false
                },
                title = {
                    Text(
                        text = "نسخه جدید منتشر شد",
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                text = {
                    Column {
                        Text(
                            text = "نسخه " + updateToShow.versionName +
                                " • بیلد " + updateToShow.buildNumber,
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = "فقط فایل مخصوص گوشی‌های ARM64 دریافت می‌شود.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (updateToShow.releaseNotes.isNotBlank()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = updateToShow.releaseNotes,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        if (isDownloading) {
                            Spacer(modifier = Modifier.height(12.dp))
                            LinearProgressIndicator(
                                progress = updateProgressState.intValue / 100f,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                text = "دریافت: " + updateProgressState.intValue + "%",
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                        if (updateStatusState.value.isNotBlank()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = updateStatusState.value,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = !isDownloading,
                        onClick = { startMoghimiUpdateDownload(updateToShow) }
                    ) {
                        Text(if (isDownloading) "در حال دریافت…" else "بروزرسانی")
                    }
                },
                dismissButton = {
                    TextButton(
                        enabled = !isDownloading,
                        onClick = { showUpdateDialogState.value = false }
                    ) {
                        Text("بعداً")
                    }
                }
            )
        }
    }

    private fun shareToClipboard(guid: String): Boolean =""",
    "update dialog"
)

replace_once(
    activity_file,
    "            MainDestination.CheckUpdate -> Intent(this, CheckUpdateActivity::class.java)\n",
    """            MainDestination.CheckUpdate -> {
                checkMoghimiUpdates(forcePrompt = true)
                return
            }
""",
    "route check update through Moghimi checker"
)

update_methods = """    private fun scheduleMoghimiUpdateChecks() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<MoghimiUpdateWorker>(12, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
            "moghimi-app-update-check",
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    private fun checkMoghimiUpdates(forcePrompt: Boolean) {
        lifecycleScope.launch {
            try {
                val available = withContext(Dispatchers.IO) {
                    MoghimiUpdateManager.checkForUpdate(applicationContext)
                }
                if (available == null) {
                    if (forcePrompt) {
                        Toast.makeText(
                            this@MainActivity,
                            "برنامه شما به‌روز است.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    return@launch
                }

                MoghimiUpdateManager.notifyIfNew(applicationContext, available)
                if (forcePrompt || MoghimiUpdateManager.shouldPrompt(applicationContext, available)) {
                    MoghimiUpdateManager.markPrompted(applicationContext, available)
                    updateInfoState.value = available
                    updateStatusState.value = ""
                    showUpdateDialogState.value = true
                }
            } catch (error: Exception) {
                if (forcePrompt) {
                    Toast.makeText(
                        this@MainActivity,
                        "بررسی نسخه جدید انجام نشد: " +
                            (error.localizedMessage ?: "خطای ارتباط"),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun startMoghimiUpdateDownload(info: MoghimiUpdateManager.UpdateInfo) {
        if (updateDownloadingState.value) return
        if (!MoghimiUpdateManager.hasConfiguredDownloadUrl()) {
            updateStatusState.value =
                "لینک ثابت دانلود ARM64 از سوپابیس هنوز تنظیم نشده است. پس از ثبت لینک، این دکمه دانلود و نصب را انجام می‌دهد."
            return
        }

        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !packageManager.canRequestPackageInstalls()
        ) {
            pendingMoghimiUpdateInstall = info
            val settingsIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + packageName)
            )
            requestInstallSourcePermission.launch(settingsIntent)
            updateStatusState.value = "برای ادامه، اجازه نصب برنامه از این منبع را در تنظیمات اندروید فعال کن."
            return
        }

        updateDownloadingState.value = true
        updateProgressState.intValue = 0
        updateStatusState.value = "در حال دریافت نسخه جدید از سوپابیس…"

        lifecycleScope.launch {
            try {
                val apkFile = withContext(Dispatchers.IO) {
                    MoghimiUpdateManager.downloadArm64Apk(
                        this@MainActivity,
                        info
                    ) { progress ->
                        runOnUiThread {
                            updateProgressState.intValue = progress
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    updateDownloadingState.value = false
                    updateStatusState.value =
                        "دانلود و بررسی سلامت فایل موفق بود؛ نصب را در پنجره اندروید تأیید کن."
                    val apkUri = FileProvider.getUriForFile(
                        this@MainActivity,
                        packageName + ".cache",
                        apkFile
                    )
                    val installIntent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(apkUri, "application/vnd.android.package-archive")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(installIntent)
                }
            } catch (error: Exception) {
                withContext(Dispatchers.Main) {
                    updateDownloadingState.value = false
                    updateStatusState.value =
                        "به‌روزرسانی انجام نشد: " +
                            (error.localizedMessage ?: "خطای ناشناخته")
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(MoghimiUpdateManager.EXTRA_OPEN_UPDATE, false)) {
            checkMoghimiUpdates(forcePrompt = true)
        }
    }

"""
share_anchor = "    private fun shareToClipboard(guid: String): Boolean ="
src = activity_file.read_text(encoding="utf-8")
if src.count(share_anchor) != 1:
    raise SystemExit("Expected one updater method insertion point in MainActivity")
activity_file.write_text(src.replace(share_anchor, update_methods + share_anchor, 1), encoding="utf-8")

print("Moghimi release updater patch applied.")
