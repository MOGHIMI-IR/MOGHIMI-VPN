#!/usr/bin/env python3
from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("Usage: patch-updater.py <v2rayNG-project-dir>")

project = Path(sys.argv[1])
activity_file = project / "app/src/main/java/com/v2ray/ang/ui/main/MainActivity.kt"
source = activity_file.read_text(encoding="utf-8")

def replace_once(old: str, new: str, label: str) -> None:
    global source
    count = source.count(old)
    if count != 1:
        raise SystemExit(f"Expected one match for {label}; found {count}")
    source = source.replace(old, new, 1)

# Do not ship a self-updater. REQUEST_INSTALL_PACKAGES is not appropriate for
# a VPN client whose core purpose is not installing APK packages. Send users
# to the official website; Android's normal browser/install flow handles updates.
replace_once(
    "import com.v2ray.ang.ui.checkupdate.CheckUpdateActivity\n",
    "",
    "remove upstream updater activity import"
)
replace_once(
    "MainDestination.CheckUpdate -> Intent(this, CheckUpdateActivity::class.java)",
    """MainDestination.CheckUpdate -> {
                Utils.openUri(this, "https://moghimi-ir.github.io/MG_V2RAY/")
                return
            }""",
    "route update action to official download site"
)

# Keep the Moghimi dashboard as the app's main screen.
replace_once(
    """        MainScreen(
            mainViewModel = mainViewModel,
            onCloudSyncClick = { syncMoghimiConfigs(autoSelect = true, silent = false) },
            onAction = { action ->""",
    """        MoghimiRootScreen(
            mainViewModel = mainViewModel,
            onCloudSyncClick = { syncMoghimiConfigs(autoSelect = true, silent = false) },
            onAction = { action ->""",
    "Moghimi dashboard entry point"
)


replace_once(
    """    private fun handleFabAction() {
        if (mainViewModel.uiState.value.isRunning) {
            LauncherManager.stopService(this)
        } else {
            requestServiceStart()
        }
    }""",
    """    private fun handleFabAction() {
        if (mainViewModel.uiState.value.isRunning) {
            LauncherManager.stopService(this)
            return
        }
        if (moghimiSyncRunning) {
            Toast.makeText(this, "در حال دریافت کانفیگ‌های سایت هستیم؛ کمی صبر کن.", Toast.LENGTH_SHORT).show()
            return
        }
        if (bestServerScanRunning) return

        bestServerScanRunning = true
        Toast.makeText(this, "در حال انتخاب بهترین سرور...", Toast.LENGTH_SHORT).show()
        mainViewModel.autoTestAllConfigsAndSelectBest { hasUsableConfig ->
            bestServerScanRunning = false
            if (hasUsableConfig) {
                requestServiceStart()
            } else {
                Toast.makeText(
                    this,
                    "کانفیگ پاسخ‌گو پیدا نشد. اتصال برقرار نشد؛ کانفیگ‌ها را بررسی کن.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }""",
    "connect flow"
)

dashboard_file = project / "app/src/main/java/com/v2ray/ang/ui/main/MoghimiDashboard.kt"
dashboard_source = dashboard_file.read_text(encoding="utf-8")

def replace_dashboard_once(old: str, new: str, label: str) -> None:
    global dashboard_source
    count = dashboard_source.count(old)
    if count != 1:
        raise SystemExit(f"Expected one dashboard match for {label}; found {count}")
    dashboard_source = dashboard_source.replace(old, new, 1)

replace_dashboard_once(
    """    val pingStatusText = when {
        !running || selectedServer == null -> "—"
        uiState.isTesting -> "در حال سنجش"
        measuredPingMillis == null -> "در انتظار"
        measuredPingMillis >= 0L -> "$measuredPingMillis ms"
        else -> "ناموفق"
    }""",
    """    val pingStatusText = when {
        !running && uiState.isTesting -> "در حال انتخاب"
        !running || selectedServer == null -> "—"
        uiState.isTesting -> "در حال سنجش"
        measuredPingMillis == null -> "در انتظار"
        measuredPingMillis >= 0L -> "$measuredPingMillis ms"
        else -> "ناموفق"
    }""",
    "disconnected scan ping label"
)
replace_dashboard_once(
    """text = if (running) "ارتباط از طریق تونل امن برقرار است" else "برای شروع، دکمهٔ اتصال را بزن",""",
    """text = when {
                            running -> "ارتباط از طریق تونل امن برقرار است"
                            uiState.isTesting -> "در حال انتخاب بهترین سرور..."
                            else -> "برای شروع، دکمهٔ اتصال را بزن"
                        },""",
    "connection card scan status"
)
replace_dashboard_once(
    """text = if (running) "اتصال شما فعال است" else "برای اتصال لمس کنید",""",
    """text = when {
                running -> "اتصال شما فعال است"
                uiState.isTesting -> "در حال انتخاب بهترین سرور..."
                else -> "برای اتصال لمس کنید"
            },""",
    "connect orb status line"
)
replace_dashboard_once(
    """text = if (running) "برای پایان ارتباط، دکمهٔ مرکزی را لمس کنید" else "با انتخاب کانفیگ، اتصال خود را آغاز کن",""",
    """text = when {
                running -> "برای پایان ارتباط، دکمهٔ مرکزی را لمس کنید"
                uiState.isTesting -> "بعد از انتخاب سرور، اتصال برقرار می‌شود"
                else -> "با انتخاب کانفیگ، اتصال خود را آغاز کن"
            },""",
    "connect orb subtitle"
)
dashboard_file.write_text(dashboard_source, encoding="utf-8")

activity_file.write_text(source, encoding="utf-8")
print("Moghimi update action opens the official website; no in-app APK installer is added.")
