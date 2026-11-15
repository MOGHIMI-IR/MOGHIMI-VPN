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

# Add only the platform APIs used by the entitlement gate.
if "import android.widget.Toast\n" not in source:
    replace_once(
        "import android.os.Bundle\n",
        "import android.os.Bundle\nimport android.widget.Toast\n",
        "Toast import"
    )
if "import com.v2ray.ang.MoghimiLicenseManager\n" not in source:
    replace_once(
        "import com.v2ray.ang.AngApplication\n",
        "import com.v2ray.ang.AngApplication\nimport com.v2ray.ang.MoghimiLicenseManager\n",
        "subscription manager import"
    )

# Keep device entitlement checking in one place and prevent duplicate start taps.
replace_once(
    "class MainActivity : HelperBaseComponentActivity() {\n\n    private var moghimiSyncRunning = false\n",
    "class MainActivity : HelperBaseComponentActivity() {\n\n    private var entitlementCheckInFlight = false\n    private var moghimiSyncRunning = false\n",
    "entitlement gate state"
)

# Do not start the service from the VPN permission callback without checking the
# device's current quota again.
replace_once(
    "if (it.resultCode == RESULT_OK) startV2Ray()",
    "if (it.resultCode == RESULT_OK) requestServiceStart()",
    "recheck quota after Android VPN consent"
)

# Route the upstream updater to the website; no APK-install permission is added.
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
    "route update action to official website"
)

# Use the branded Compose dashboard as the main screen.
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

# Settings and in-app restart actions must not bypass the subscription gate.
replace_once(
    "if (restartService) LauncherManager.restartService(this)",
    "if (restartService) restartServiceIfAllowed()",
    "settings restart entitlement gate"
)
replace_once(
    "MainAction.RestartService -> LauncherManager.restartServiceOrStart(this, ::requestServiceStart)",
    "MainAction.RestartService -> restartServiceIfAllowed()",
    "manual restart entitlement gate"
)

old_request = """    private fun requestServiceStart() {
        if (!SettingsManager.isVpnMode()) {
            startV2Ray()
            return
        }
        val intent = VpnService.prepare(this)
        if (intent == null) startV2Ray() else requestVpnPermission.launch(intent)
    }"""
new_request = """    private fun requestServiceStart() {
        if (entitlementCheckInFlight) return
        if (moghimiSyncRunning) {
            Toast.makeText(this, "دریافت کانفیگ‌ها هنوز تمام نشده است.", Toast.LENGTH_SHORT).show()
            return
        }
        entitlementCheckInFlight = true
        lifecycleScope.launch {
            try {
                val allowed = withContext(Dispatchers.IO) {
                    MoghimiLicenseManager.canStartConnection(this@MainActivity)
                }
                if (!allowed) {
                    Toast.makeText(
                        this@MainActivity,
                        "سهمیه رایگان تمام شده یا اشتراک فعال نیست. وضعیت پروفایل را بررسی کن.",
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }
                if (!SettingsManager.isVpnMode()) {
                    startV2Ray()
                    return@launch
                }
                val intent = VpnService.prepare(this@MainActivity)
                if (intent == null) startV2Ray() else requestVpnPermission.launch(intent)
            } catch (error: Exception) {
                Toast.makeText(
                    this@MainActivity,
                    "بررسی اشتراک ناموفق بود: ${error.localizedMessage ?: "خطای نامشخص"}",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                entitlementCheckInFlight = false
            }
        }
    }

    private fun restartServiceIfAllowed() {
        if (!mainViewModel.uiState.value.isRunning) {
            requestServiceStart()
            return
        }
        if (entitlementCheckInFlight) return
        entitlementCheckInFlight = true
        lifecycleScope.launch {
            try {
                val allowed = withContext(Dispatchers.IO) {
                    MoghimiLicenseManager.canStartConnection(this@MainActivity)
                }
                if (allowed) {
                    LauncherManager.restartService(this@MainActivity)
                } else {
                    LauncherManager.stopService(this@MainActivity)
                    Toast.makeText(
                        this@MainActivity,
                        "حجم یا اعتبار اشتراک پایان یافته است.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } finally {
                entitlementCheckInFlight = false
            }
        }
    }"""
replace_once(old_request,new_request,"authoritative subscription check before starting/restarting VPN")

activity_file.write_text(source, encoding="utf-8")
print("Moghimi update action opens the official website; VPN start and restart require a current entitlement.")
