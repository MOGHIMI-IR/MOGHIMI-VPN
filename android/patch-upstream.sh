#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
UPSTREAM="$ROOT/android/upstream"
PROJECT="$UPSTREAM/V2rayNG"
APP="$PROJECT/app"
JAVA="$APP/src/main/java/com/v2ray/ang"
RES="$APP/src/main/res"

if [[ ! -f "$APP/build.gradle.kts" || ! -f "$JAVA/ui/main/MainActivity.kt" ]]; then
  echo "Pinned v2rayNG source was not checked out. Use actions/checkout with recursive submodules."
  exit 1
fi

mkdir -p "$JAVA"
cp "$ROOT/android/src/main/java/com/v2ray/ang/MoghimiCloudSync.kt" "$JAVA/MoghimiCloudSync.kt"
cp "$ROOT/android/src/main/java/com/v2ray/ang/MoghimiSyncWorker.kt" "$JAVA/MoghimiSyncWorker.kt"

python3 - "$PROJECT" <<'PY'
from pathlib import Path
import sys

project = Path(sys.argv[1])
app = project / "app"
java = app / "src/main/java/com/v2ray/ang"
res = app / "src/main/res"

def replace_once(path: Path, old: str, new: str, label: str):
    source = path.read_text(encoding="utf-8")
    count = source.count(old)
    if count != 1:
        raise SystemExit(f"Expected one match for {label} in {path}; found {count}")
    path.write_text(source.replace(old, new, 1), encoding="utf-8")

# Give the Moghimi client its own Android package and version. Keep the Kotlin namespace:
# the upstream Xray/TUN implementation and manifest component names depend on it.
gradle = app / "build.gradle.kts"
source = gradle.read_text(encoding="utf-8")
for old, new, label in [
    ('applicationId = "com.v2ray.ang"', 'applicationId = "ir.moghimi.v2ray"', "applicationId"),
    ("versionCode = 755", "versionCode = 1", "versionCode"),
    ('versionName = "2.3.15"', 'versionName = "1.0.0"', "versionName"),
]:
    if source.count(old) != 1:
        raise SystemExit(f"Expected one Gradle match for {label}")
    source = source.replace(old, new, 1)
source = source.replace('outputFileName = "v2rayNG_', 'outputFileName = "MoghimiV2Ray_')
if 'ndkVersion = "30.0.16248370"' not in source:
    replace_marker = "compileSdk = 37"
    if source.count(replace_marker) != 1:
        raise SystemExit("Could not locate compileSdk for the pinned NDK version")
    source = source.replace(
        replace_marker,
        replace_marker + '\n    ndkVersion = "30.0.16248370"',
        1
    )
gradle.write_text(source, encoding="utf-8")

# Apply the Moghimi palette. System dynamic colors default to off so Android 12+
# does not replace the navy/yellow/purple identity with the device wallpaper palette.
theme_file = java / "ui/compose/Theme.kt"
theme = theme_file.read_text(encoding="utf-8")
start = theme.index("private val DarkColor = darkColorScheme(")
marker = "\n)\n\n// Semantic Colors"
end = theme.index(marker, start) + 2
dark_scheme = """private val DarkColor = darkColorScheme(
    primary = Color(0xFFFFD84D),
    onPrimary = Color(0xFF111827),
    primaryContainer = Color(0xFF51431C),
    onPrimaryContainer = Color(0xFFFFEBA0),
    secondary = Color(0xFFA98BFF),
    onSecondary = Color(0xFF24164B),
    secondaryContainer = Color(0xFF392B61),
    onSecondaryContainer = Color(0xFFE8DEFF),
    tertiary = Color(0xFF63DDB6),
    onTertiary = Color(0xFF073A2C),
    tertiaryContainer = Color(0xFF155342),
    onTertiaryContainer = Color(0xFFB6F5DF),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF93000A),
    onError = Color(0xFF690005),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0B1226),
    onBackground = Color(0xFFF5F6FF),
    surface = Color(0xFF111C35),
    onSurface = Color(0xFFF5F6FF),
    surfaceVariant = Color(0xFF243453),
    onSurfaceVariant = Color(0xFFBAC6DF),
    outline = Color(0xFF7180A1),
    outlineVariant = Color(0xFF334363),
    inverseSurface = Color(0xFFE6E9F2),
    inverseOnSurface = Color(0xFF111827),
    inversePrimary = Color(0xFF66551C),
    scrim = Color(0xFF000000),
    surfaceTint = Color(0xFFFFD84D),
    surfaceContainerLowest = Color(0xFF080E1D),
    surfaceContainerLow = Color(0xFF0D1730),
    surfaceContainer = Color(0xFF141F39),
    surfaceContainerHigh = Color(0xFF1A2948),
    surfaceContainerHighest = Color(0xFF243453),
)"""
theme = theme[:start] + dark_scheme + theme[end:]
theme = theme.replace(
    'MmkvManager.decodeSettingsString(AppConfig.PREF_UI_MODE_NIGHT, "0") ?: "0"',
    'MmkvManager.decodeSettingsString(AppConfig.PREF_UI_MODE_NIGHT, "2") ?: "2"'
)
theme = theme.replace(
    "MmkvManager.decodeSettingsBool(AppConfig.PREF_DYNAMIC_COLOR, true)",
    "MmkvManager.decodeSettingsBool(AppConfig.PREF_DYNAMIC_COLOR, false)"
)
theme = theme.replace("val colorPing = Color(0xFF009966)", "val colorPing = Color(0xFF4CE0B2)")
theme = theme.replace("val colorPingRed = Color(0xFFFF0099)", "val colorPingRed = Color(0xFFFF5578)")
theme = theme.replace("val colorConfigType = Color(0xFFf97910)", "val colorConfigType = Color(0xFFA98BFF)")
theme = theme.replace("val colorFabActive = Color(0xFFf97910)", "val colorFabActive = Color(0xFFFFD84D)")
theme = theme.replace("val dividerColorDark = Color(0xFF424242)", "val dividerColorDark = Color(0xFF334363)")
theme_file.write_text(theme, encoding="utf-8")

# Brand the app labels in the default and Persian resources.
default_strings = res / "values/strings.xml"
replace_once(
    default_strings,
    '<string name="app_name" translatable="false">v2rayNG</string>',
    '<string name="app_name" translatable="false">Moghimi V2Ray</string>',
    "default app name"
)
replace_once(
    default_strings,
    '<string name="title_server">Config</string>',
    '<string name="title_server">Moghimi V2Ray</string>',
    "default toolbar title"
)

fa_strings = res / "values-fa/strings.xml"
fa_text = fa_strings.read_text(encoding="utf-8")
fa_text = fa_text.replace(
    '<string name="app_name" translatable="false">v2rayNG</string>',
    '<string name="app_name" translatable="false">Moghimi V2Ray</string>',
    1
)
fa_text = fa_text.replace(
    '<string name="title_server">فایل کانفیگ</string>',
    '<string name="title_server">مقیمی V2Ray</string>',
    1
)
fa_text = fa_text.replace(
    '<string name="notification_channel_service">سرویس v2rayNG</string>',
    '<string name="notification_channel_service">سرویس مقیمی V2Ray</string>',
    1
)
if 'name="moghimi_sync"' not in fa_text:
    fa_text = fa_text.replace(
        "</resources>",
        """    <string name="moghimi_sync">همگام‌سازی کانفیگ‌های سایت مقیمی</string>
    <string name="moghimi_sync_start">در حال دریافت کانفیگ‌های سایت مقیمی…</string>
    <string name="moghimi_sync_done">%1$d کانفیگ از %2$d مورد سایت همگام شد.</string>
    <string name="moghimi_sync_error">همگام‌سازی انجام نشد: %1$s</string>
    <string name="moghimi_empty_title">هنوز کانفیگی اضافه نشده</string>
    <string name="moghimi_empty_body">با دکمه ابر، کانفیگ‌های سایت مقیمی را دریافت کن؛ یا با دکمه + کانفیگ دستی و QR اضافه کن.</string>
</resources>"""
    )
fa_strings.write_text(fa_text, encoding="utf-8")

default_text = default_strings.read_text(encoding="utf-8")
if 'name="moghimi_sync"' not in default_text:
    default_text = default_text.replace(
        "</resources>",
        """    <string name="moghimi_sync">Sync Moghimi site configs</string>
    <string name="moghimi_sync_start">Syncing Moghimi site configs…</string>
    <string name="moghimi_sync_done">Synced %1$d configs from %2$d active entries.</string>
    <string name="moghimi_sync_error">Sync failed: %1$s</string>
    <string name="moghimi_empty_title">No configurations yet</string>
    <string name="moghimi_empty_body">Use the cloud button to fetch Moghimi site configs, or use + to add a config manually or by QR.</string>
</resources>"""
    )
default_strings.write_text(default_text, encoding="utf-8")

# Add a cloud-sync action to the existing toolbar without removing manual import/QR actions.
top_bar = java / "ui/main/MainTopBar.kt"
replace_once(
    top_bar,
    "    onMenuClick: () -> Unit,\n    onAction: (MainAction) -> Unit,",
    "    onMenuClick: () -> Unit,\n    onCloudSyncClick: () -> Unit,\n    onAction: (MainAction) -> Unit,",
    "toolbar callback"
)
replace_once(
    top_bar,
    """            if (!showSearch) {
                IconButton(onClick = { onSearchToggle(true) }) {""",
    """            if (!showSearch) {
                IconButton(onClick = onCloudSyncClick) {
                    Icon(
                        painterResource(R.drawable.ic_cloud_download_24dp),
                        contentDescription = stringResource(R.string.moghimi_sync)
                    )
                }
                IconButton(onClick = { onSearchToggle(true) }) {""",
    "toolbar cloud action"
)

main_screen_file = java / "ui/main/MainScreen.kt"
main_screen = main_screen_file.read_text(encoding="utf-8")
replace_once(
    main_screen_file,
    "    mainViewModel: MainViewModel,\n    onAction: (MainAction) -> Unit,",
    "    mainViewModel: MainViewModel,\n    onCloudSyncClick: () -> Unit,\n    onAction: (MainAction) -> Unit,",
    "main screen cloud callback"
)
main_screen = main_screen_file.read_text(encoding="utf-8")
replace_once(
    main_screen_file,
    "                    onMenuClick = { scope.launch { drawerState.open() } },\n                    onAction = onAction,",
    "                    onMenuClick = { scope.launch { drawerState.open() } },\n                    onCloudSyncClick = onCloudSyncClick,\n                    onAction = onAction,",
    "main screen toolbar binding"
)

# Add the compact branded header and empty state.
main_screen = main_screen_file.read_text(encoding="utf-8")
imports = """import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
"""
if "import androidx.compose.material3.Surface" not in main_screen:
    main_screen = main_screen.replace(
        "import androidx.compose.foundation.layout.Box\n",
        "import androidx.compose.foundation.layout.Box\n" + imports,
        1
    )
main_screen_file.write_text(main_screen, encoding="utf-8")

replace_once(
    main_screen_file,
    """                ) {
                    if (groups.size > 1) {""",
    """                ) {
                    MoghimiBrandBanner()
                    if (groups.size > 1) {""",
    "brand banner in server view"
)
replace_once(
    main_screen_file,
    """            }
            MainBottomBar(
                displayText = displayText,""",
    """            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = innerPadding.calculateTopPadding()),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    MoghimiBrandBanner()
                    Text(
                        text = androidx.compose.ui.res.stringResource(R.string.moghimi_empty_title),
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 24.dp, start = 24.dp, end = 24.dp)
                    )
                    Text(
                        text = androidx.compose.ui.res.stringResource(R.string.moghimi_empty_body),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 10.dp, start = 28.dp, end = 28.dp)
                    )
                }
            }
            MainBottomBar(
                displayText = displayText,""",
    "empty state"
)

main_screen = main_screen_file.read_text(encoding="utf-8").rstrip()
main_screen += """

@Composable
private fun MoghimiBrandBanner() {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "MG",
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 15.sp
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "MOGHIMI V2RAY",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.ExtraBold
                )
                Text(
                    text = "اتصال امن • مدیریت کانفیگ‌ها",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Text(
                text = "X",
                color = MaterialTheme.colorScheme.secondary,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold
            )
        }
    }
}
"""
main_screen_file.write_text(main_screen, encoding="utf-8")

# Automatic cloud sync at app launch, manually from toolbar, and periodically in background.
main_activity_file = java / "ui/main/MainActivity.kt"
activity = main_activity_file.read_text(encoding="utf-8")
activity = activity.replace(
    "import android.view.KeyEvent\n",
    "import android.view.KeyEvent\nimport android.widget.Toast\n",
    1
)
activity = activity.replace(
    "import androidx.lifecycle.lifecycleScope\n",
    """import androidx.lifecycle.lifecycleScope
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.v2ray.ang.MoghimiCloudSync
import com.v2ray.ang.MoghimiSyncWorker
import java.util.concurrent.TimeUnit
""",
    1
)
activity = activity.replace(
    "class MainActivity : HelperBaseComponentActivity() {\n",
    "class MainActivity : HelperBaseComponentActivity() {\n\n    private var moghimiSyncRunning = false\n",
    1
)
main_activity_file.write_text(activity, encoding="utf-8")
replace_once(
    main_activity_file,
    """        mainViewModel.onAction(MainAction.Initialize)

        checkAndRequestPermission(PermissionType.POST_NOTIFICATIONS) {}""",
    """        mainViewModel.onAction(MainAction.Initialize)
        scheduleMoghimiBackgroundSync()
        syncMoghimiConfigs(autoSelect = false, silent = true)

        checkAndRequestPermission(PermissionType.POST_NOTIFICATIONS) {}""",
    "startup cloud sync"
)
replace_once(
    main_activity_file,
    """        MainScreen(
            mainViewModel = mainViewModel,
            onAction = { action ->""",
    """        MainScreen(
            mainViewModel = mainViewModel,
            onCloudSyncClick = { syncMoghimiConfigs(autoSelect = true, silent = false) },
            onAction = { action ->""",
    "cloud sync callback into screen"
)

activity = main_activity_file.read_text(encoding="utf-8")
insertion = "    private fun shareToClipboard(guid: String): Boolean ="
if activity.count(insertion) != 1:
    raise SystemExit("Could not locate MainActivity insertion point")
methods = """    private fun scheduleMoghimiBackgroundSync() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<MoghimiSyncWorker>(6, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
            "moghimi-site-config-sync",
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    private fun syncMoghimiConfigs(autoSelect: Boolean, silent: Boolean) {
        if (moghimiSyncRunning) return
        moghimiSyncRunning = true

        if (!silent) {
            Toast.makeText(this, R.string.moghimi_sync_start, Toast.LENGTH_SHORT).show()
        }

        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    MoghimiCloudSync.sync()
                }
                mainViewModel.setupGroupTab(forceRefresh = true).join()
                if (autoSelect) {
                    mainViewModel.onAction(
                        MainAction.SelectGroup(MoghimiCloudSync.CLOUD_GROUP_ID)
                    )
                }
                if (!silent) {
                    Toast.makeText(
                        this@MainActivity,
                        getString(
                            R.string.moghimi_sync_done,
                            result.importedProfiles,
                            result.activeEntries
                        ),
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (error: Exception) {
                if (!silent) {
                    Toast.makeText(
                        this@MainActivity,
                        getString(
                            R.string.moghimi_sync_error,
                            error.localizedMessage ?: "Unknown error"
                        ),
                        Toast.LENGTH_LONG
                    ).show()
                }
            } finally {
                moghimiSyncRunning = false
            }
        }
    }

"""
activity = activity.replace(insertion, methods + insertion, 1)
main_activity_file.write_text(activity, encoding="utf-8")

# App icon: dark navy background, yellow MG monogram and purple accent.
manifest = app / "src/main/AndroidManifest.xml"
manifest_text = manifest.read_text(encoding="utf-8")
manifest_text = manifest_text.replace(
    'android:icon="@mipmap/ic_launcher"',
    'android:icon="@drawable/ic_moghimi_launcher"',
    1
)
manifest_text = manifest_text.replace(
    'android:roundIcon="@mipmap/ic_launcher_round"',
    'android:roundIcon="@drawable/ic_moghimi_launcher"',
    1
)
manifest.write_text(manifest_text, encoding="utf-8")

launcher = res / "drawable/ic_moghimi_launcher.xml"
launcher.write_text(
    """<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path android:fillColor="#0B1226" android:pathData="M0,0h108v108h-108z" />
    <path android:fillColor="#FFD84D"
        android:pathData="M12,82V26h13l17,23 17,-23h13v56H57V51L42,70 27,51v31z" />
    <path android:fillColor="#A98BFF"
        android:pathData="M81,32h13v8H82v28h12V57h-8v-8h17v21c0,5 -4,9 -9,9H81c-5,0 -9,-4 -9,-9V41c0,-5 4,-9 9,-9z" />
    <path android:fillColor="#A98BFF" android:pathData="M91,18a5,5 0,1 0,0.1 0z" />
</vector>
""",
    encoding="utf-8"
)

print("Moghimi branding and cloud sync patch applied.")
PY

echo "Prepared branded Android source under $PROJECT"
