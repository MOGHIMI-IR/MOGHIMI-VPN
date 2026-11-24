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
if "import com.v2ray.ang.MoghimiCloudSync\n" not in source:
    replace_once(
        "import com.v2ray.ang.MoghimiLicenseManager\n",
        "import com.v2ray.ang.MoghimiLicenseManager\nimport com.v2ray.ang.MoghimiCloudSync\n",
        "cloud sync import"
    )
if "import kotlinx.coroutines.flow.first\n" not in source:
    replace_once(
        "import kotlinx.coroutines.Dispatchers\n",
        "import kotlinx.coroutines.Dispatchers\nimport kotlinx.coroutines.flow.first\n",
        "flow first import"
    )
if "import kotlinx.coroutines.withTimeoutOrNull\n" not in source:
    replace_once(
        "import kotlinx.coroutines.withContext\n",
        "import kotlinx.coroutines.withContext\nimport kotlinx.coroutines.withTimeoutOrNull\n",
        "timeout import"
    )

# Keep device entitlement checking in one place and prevent duplicate start taps.
replace_once(
    "class MainActivity : HelperBaseComponentActivity() {\n\n    private var moghimiSyncRunning = false\n",
    "class MainActivity : HelperBaseComponentActivity() {\n\n    private var entitlementCheckInFlight = false\n    private var smartConnectInFlight = false\n    private var moghimiSyncRunning = false\n",
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
            onSmartConnectClick = { searching -> startSmartConnection(searching) },
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
new_request = """    private fun startSmartConnection(onSearchingChanged: (Boolean) -> Unit) {
        if (smartConnectInFlight || entitlementCheckInFlight) return
        smartConnectInFlight = true
        onSearchingChanged(true)
        lifecycleScope.launch {
            try {
                if (!awaitMoghimiConfigSync()) {
                    toastError("آماده‌سازی سرورها طول کشید؛ دوباره تلاش کنید.")
                    return@launch
                }
                val allowed = withContext(Dispatchers.IO) {
                    MoghimiLicenseManager.canStartConnection(this@MainActivity)
                }
                if (!allowed) {
                    val state = MoghimiLicenseManager.state.value
                    val reason = state.error ?: when {
                        !state.isRegistered -> "ابتدا پروفایل دستگاه را ثبت کنید."
                        !state.accessAllowed -> "سهمیه تمام شده یا اشتراک فعالی ندارید."
                        else -> "امکان بررسی اشتراک وجود ندارد؛ دوباره تلاش کنید."
                    }
                    toastError(reason)
                    return@launch
                }

                val groupId = MoghimiCloudSync.CLOUD_GROUP_ID
                mainViewModel.onAction(MainAction.Search(""))
                mainViewModel.onAction(MainAction.SelectGroup(groupId))
                withTimeoutOrNull(8_000L) {
                    mainViewModel.uiState.first { it.selectedGroupId == groupId }
                } ?: throw IllegalStateException("گروه سرورهای مقیمی آماده نشد.")
                var servers = withTimeoutOrNull(12_000L) {
                    mainViewModel.serversForGroup(groupId).first { it.isNotEmpty() }
                }.orEmpty()

                if (servers.isEmpty()) {
                    withContext(Dispatchers.IO) {
                        MoghimiCloudSync.sync(applicationContext)
                    }
                    mainViewModel.setupGroupTab(forceRefresh = true).join()
                    mainViewModel.onAction(MainAction.SelectGroup(groupId))
                    servers = withTimeoutOrNull(12_000L) {
                        mainViewModel.serversForGroup(groupId).first { it.isNotEmpty() }
                    }.orEmpty()
                }

                if (servers.isEmpty()) {
                    toastError("هیچ کانفیگ فعالی برای آزمایش پیدا نشد.")
                    return@launch
                }

                Toast.makeText(
                    this@MainActivity,
                    "در حال آزمایش ${servers.size} کانفیگ و انتخاب بهترین مسیر…",
                    Toast.LENGTH_SHORT
                ).show()

                mainViewModel.testAllRealPing()
                val testStarted = withTimeoutOrNull(5_000L) {
                    mainViewModel.uiState.first { it.isTesting }
                }
                if (testStarted == null) {
                    toastError("آزمایش سرورها شروع نشد؛ فهرست کانفیگ‌ها را دوباره همگام‌سازی کنید.")
                    return@launch
                }
                val finished = withTimeoutOrNull(90_000L) {
                    mainViewModel.uiState.first { !it.isTesting }
                }
                if (finished == null) {
                    mainViewModel.cancelAllPing()
                    toastError("آزمایش سرورها بیش از حد طول کشید؛ دوباره تلاش کنید.")
                    return@launch
                }

                val testedServers = mainViewModel.serversForGroup(groupId).value
                val best = testedServers
                    .filter { it.testDelayMillis > 0L }
                    .minByOrNull { it.testDelayMillis }
                if (best == null) {
                    toastError("هیچ‌کدام از کانفیگ‌ها پاسخ معتبر ندادند. اینترنت یا سرورها را بررسی کنید.")
                    return@launch
                }

                mainViewModel.updateSelectedGuid(best.guid)
                Toast.makeText(
                    this@MainActivity,
                    "بهترین کانفیگ انتخاب شد · ${best.testDelayMillis} ms",
                    Toast.LENGTH_SHORT
                ).show()
                requestServiceStart()
            } catch (error: Exception) {
                toastError(error.localizedMessage ?: "اتصال هوشمند انجام نشد.")
            } finally {
                onSearchingChanged(false)
                smartConnectInFlight = false
            }
        }
    }

    private suspend fun awaitMoghimiConfigSync(): Boolean {
        if (!moghimiSyncRunning) return true
        return withTimeoutOrNull(20_000L) {
            while (moghimiSyncRunning) kotlinx.coroutines.delay(100L)
            true
        } == true
    }

    private fun requestServiceStart() {
        if (entitlementCheckInFlight) return
        entitlementCheckInFlight = true
        lifecycleScope.launch {
            try {
                if (!awaitMoghimiConfigSync()) {
                    toastError("آماده‌سازی سرورها طول کشید؛ دوباره تلاش کنید.")
                    return@launch
                }
                val allowed = withContext(Dispatchers.IO) {
                    MoghimiLicenseManager.canStartConnection(this@MainActivity)
                }
                if (!allowed) {
                    val state = MoghimiLicenseManager.state.value
                    val reason = state.error ?: when {
                        !state.isRegistered -> "ابتدا پروفایل دستگاه را ثبت کنید."
                        !state.accessAllowed -> "سهمیه تمام شده یا اشتراک فعالی ندارید."
                        else -> "امکان بررسی اشتراک وجود ندارد؛ دوباره تلاش کنید."
                    }
                    Toast.makeText(this@MainActivity, reason, Toast.LENGTH_LONG).show()
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
