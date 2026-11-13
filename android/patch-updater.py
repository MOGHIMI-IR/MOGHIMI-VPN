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

activity_file.write_text(source, encoding="utf-8")
print("Moghimi update action opens the official website; no in-app APK installer is added.")
