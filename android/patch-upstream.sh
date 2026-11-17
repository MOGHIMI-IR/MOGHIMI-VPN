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
cp "$ROOT/android/src/main/java/com/v2ray/ang/MoghimiLicenseManager.kt" "$JAVA/MoghimiLicenseManager.kt"
cp "$ROOT/android/src/main/java/com/v2ray/ang/ui/main/MoghimiProfilePage.kt" "$JAVA/ui/main/MoghimiProfilePage.kt"
cp "$ROOT/android/src/main/java/com/v2ray/ang/MoghimiSyncWorker.kt" "$JAVA/MoghimiSyncWorker.kt"
cp "$ROOT/android/src/main/java/com/v2ray/ang/ui/main/MoghimiHomeHeader.kt" "$JAVA/ui/main/MoghimiHomeHeader.kt"
cp "$ROOT/android/src/main/java/com/v2ray/ang/ui/main/MoghimiDashboard.kt" "$JAVA/ui/main/MoghimiDashboard.kt"
cp "$ROOT/android/src/main/java/com/v2ray/ang/ui/main/MoghimiSupport.kt" "$JAVA/ui/main/MoghimiSupport.kt"
mkdir -p "$RES/font" "$RES/drawable" "$RES/drawable-nodpi"
cp "$ROOT/android/src/main/res/font/tanha.ttf" "$RES/font/tanha.ttf"
cp "$ROOT/android/src/main/res/drawable-nodpi/moghimi_logo.webp" "$RES/drawable-nodpi/moghimi_logo.webp"
cp "$ROOT"/android/src/main/res/drawable/*.xml "$RES/drawable/"

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
    ("versionCode = 755", 'versionCode = System.getenv("MOGHIMI_VERSION_CODE")?.toIntOrNull() ?: 1', "versionCode"),
    ('versionName = "2.3.15"', 'versionName = "2.0.8"', "versionName"),
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

signing_marker = "    buildTypes {\n"
signing_block = """    val moghimiStoreFile = System.getenv("MOGHIMI_SIGNING_STORE_FILE")
    val moghimiStorePassword = System.getenv("MOGHIMI_SIGNING_STORE_PASSWORD")
    val moghimiKeyAlias = System.getenv("MOGHIMI_SIGNING_KEY_ALIAS")
    val moghimiKeyPassword = System.getenv("MOGHIMI_SIGNING_KEY_PASSWORD")
    if (!moghimiStoreFile.isNullOrBlank()) {
        if (moghimiStorePassword.isNullOrBlank() ||
            moghimiKeyAlias.isNullOrBlank() ||
            moghimiKeyPassword.isNullOrBlank() ||
            !file(moghimiStoreFile).isFile
        ) {
            throw GradleException("Moghimi stable signing is enabled but its keystore or credentials are incomplete.")
        }
        signingConfigs {
            create("moghimiStable") {
                storeFile = file(moghimiStoreFile)
                storePassword = moghimiStorePassword
                keyAlias = moghimiKeyAlias
                keyPassword = moghimiKeyPassword
            }
        }
        buildTypes {
            getByName("debug") {
                signingConfig = signingConfigs.getByName("moghimiStable")
            }
            getByName("release") {
                signingConfig = signingConfigs.getByName("moghimiStable")
            }
        }
    }

"""
if source.count(signing_marker) != 1:
    raise SystemExit("Could not locate Android debug build type for stable signing")
source = source.replace(signing_marker, signing_block + signing_marker, 1)
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
font_imports = """import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
"""
if "import androidx.compose.ui.text.font.FontFamily" not in theme:
    theme = theme.replace(
        "import androidx.compose.material3.MaterialTheme\n",
        "import androidx.compose.material3.MaterialTheme\n" + font_imports,
        1
    )
    if "import com.v2ray.ang.R" not in theme:
        theme = theme.replace(
            "import com.v2ray.ang.AppConfig\n",
            "import com.v2ray.ang.AppConfig\nimport com.v2ray.ang.R\n",
            1
        )

tanha_typography = """
private val TanhaFontFamily = FontFamily(
    Font(R.font.tanha, FontWeight.Normal)
)

private val MoghimiTypography = Typography().let { base ->
    base.copy(
        displayLarge = base.displayLarge.copy(fontFamily = TanhaFontFamily),
        displayMedium = base.displayMedium.copy(fontFamily = TanhaFontFamily),
        displaySmall = base.displaySmall.copy(fontFamily = TanhaFontFamily),
        headlineLarge = base.headlineLarge.copy(fontFamily = TanhaFontFamily),
        headlineMedium = base.headlineMedium.copy(fontFamily = TanhaFontFamily),
        headlineSmall = base.headlineSmall.copy(fontFamily = TanhaFontFamily),
        titleLarge = base.titleLarge.copy(fontFamily = TanhaFontFamily),
        titleMedium = base.titleMedium.copy(fontFamily = TanhaFontFamily),
        titleSmall = base.titleSmall.copy(fontFamily = TanhaFontFamily),
        bodyLarge = base.bodyLarge.copy(fontFamily = TanhaFontFamily),
        bodyMedium = base.bodyMedium.copy(fontFamily = TanhaFontFamily),
        bodySmall = base.bodySmall.copy(fontFamily = TanhaFontFamily),
        labelLarge = base.labelLarge.copy(fontFamily = TanhaFontFamily),
        labelMedium = base.labelMedium.copy(fontFamily = TanhaFontFamily),
        labelSmall = base.labelSmall.copy(fontFamily = TanhaFontFamily)
    )
}

"""
if "private val MoghimiTypography" not in theme:
    theme = theme.replace("// Semantic Colors", tanha_typography + "// Semantic Colors", 1)
theme = theme.replace(
    """MaterialTheme(
            colorScheme = colorScheme
        )""",
    """MaterialTheme(
            colorScheme = colorScheme,
            typography = MoghimiTypography
        )""",
    1
)
theme_file.write_text(theme, encoding="utf-8")

# Default new installations to Persian, while keeping any language the user has already selected.
locale_file = java / "handler/AppLocaleManager.kt"
replace_once(
    locale_file,
    """    fun initialize(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {""",
    """    fun initialize(context: Context) {
        // One-time Moghimi locale migration: treat the legacy AUTO default as Persian,
        // but do not overwrite a language explicitly selected by the user.
        if (!MmkvManager.decodeSettingsBool("moghimi_persian_locale_migrated", false)) {
            val savedLanguage = MmkvManager.decodeSettingsString(AppConfig.PREF_LANGUAGE)
            if (savedLanguage.isNullOrBlank() || savedLanguage == Language.AUTO.code) {
                MmkvManager.encodeSettings(AppConfig.PREF_LANGUAGE, Language.PERSIAN.code)
            }
            MmkvManager.encodeSettings("moghimi_persian_locale_migrated", true)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {""",
    "one-time Persian locale migration"
)
replace_once(
    locale_file,
    "MmkvManager.decodeSettingsString(AppConfig.PREF_LANGUAGE) ?: Language.AUTO.code",
    "MmkvManager.decodeSettingsString(AppConfig.PREF_LANGUAGE) ?: Language.PERSIAN.code",
    "default Persian app locale"
)

# Apply the uploaded Moghimi emblem as the launcher icon.
manifest = app / "src/main/AndroidManifest.xml"
manifest_source = manifest.read_text(encoding="utf-8")
old_icon = 'android:icon="@mipmap/ic_launcher"'
application_start = manifest_source.find("<application")
if application_start < 0:
    raise SystemExit("Could not locate the Android application manifest element")
icon_position = manifest_source.find(old_icon, application_start)
if icon_position < 0:
    raise SystemExit("Could not locate the application launcher icon attribute")
new_icon = 'android:icon="@drawable/moghimi_logo"'
manifest_source = (
    manifest_source[:icon_position]
    + new_icon
    + manifest_source[icon_position + len(old_icon):]
)
manifest.write_text(manifest_source, encoding="utf-8")

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
    '<string name="title_server">کانفیگ‌ها</string>',
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
    '<string name="title_server">کانفیگ‌ها</string>',
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

replace_once(
    main_screen_file,
    """    onNavigate: (MainDestination) -> Unit,
) {""",
    """    onNavigate: (MainDestination) -> Unit,
    showConnectionBar: Boolean = true,
) {""",
    "optional legacy connection bar"
)

# Add the compact branded header and empty state.
main_screen = main_screen_file.read_text(encoding="utf-8")
imports = """import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.v2ray.ang.R
import com.v2ray.ang.MoghimiCloudSync
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
    "    val shareQRCodeBitmap = uiState.shareQRCodeBitmap\n",
    """    val shareQRCodeBitmap = uiState.shareQRCodeBitmap
    val cloudGroupStateFlow = remember(mainViewModel) {
        mainViewModel.serverGroupState(MoghimiCloudSync.CLOUD_GROUP_ID)
    }
    val cloudGroupState by cloudGroupStateFlow.collectAsStateWithLifecycle()
""",
    "Moghimi cloud server count"
)

replace_once(
    main_screen_file,
    """                ) {
                    if (groups.size > 1) {""",
    """                ) {
                    MoghimiHomeHeader(
                        isRunning = isRunning,
                        serverCount = cloudGroupState.rows.size,
                        onCloudSyncClick = onCloudSyncClick
                    )
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
                    MoghimiHomeHeader(
                        isRunning = isRunning,
                        serverCount = cloudGroupState.rows.size,
                        onCloudSyncClick = onCloudSyncClick
                    )
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

replace_once(
    main_screen_file,
    """            MainBottomBar(
                displayText = displayText,
                isRunning = isRunning,
                isDarkTheme = isDarkTheme,
                onAction = onAction,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .onSizeChanged { bottomBarHeightPx = it.height }
            )""",
    """            if (showConnectionBar) {
                MainBottomBar(
                    displayText = displayText,
                    isRunning = isRunning,
                    isDarkTheme = isDarkTheme,
                    onAction = onAction,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .onSizeChanged { bottomBarHeightPx = it.height }
                )
            }""",
    "conditional legacy connection bar"
)

main_screen = main_screen_file.read_text(encoding="utf-8").rstrip()
main_screen += """

@Composable
private fun MoghimiBrandBanner(isRunning: Boolean) {
    val statusColor = if (isRunning) Color(0xFF63DDB6) else Color(0xFFFFD84D)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        shape = RoundedCornerShape(25.dp),
        color = Color.Transparent,
        shadowElevation = 9.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        listOf(
                            Color(0xFF101D38),
                            Color(0xFF20234D),
                            Color(0xFF39265B)
                        )
                    )
                )
                .padding(horizontal = 16.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFFFFD84D)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "MG",
                    color = Color(0xFF10172A),
                    fontWeight = FontWeight.Black,
                    fontSize = 16.sp
                )
            }
            Spacer(modifier = Modifier.width(13.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "MOGHIMI V2RAY",
                    color = Color(0xFFFFFFFF),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 1.1.sp
                )
                Text(
                    text = "سرویس‌های اختصاصی مقیمی",
                    color = Color(0xFFC7D2FE),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = if (isRunning) "CONNECTED" else "READY",
                    color = statusColor,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.ExtraBold
                )
                Text(
                    text = if (isRunning) "اتصال برقرار است" else "آماده اتصال",
                    color = Color(0xFFE4E8F7),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
"""
main_screen_file.write_text(main_screen, encoding="utf-8")



# Rounded server cards with a clear selected state.
pager_file = java / "ui/main/MainServerPager.kt"
pager = pager_file.read_text(encoding="utf-8")
if "import androidx.compose.foundation.border" not in pager:
    pager = pager.replace(
        "import androidx.compose.foundation.background\n",
        "import androidx.compose.foundation.background\nimport androidx.compose.foundation.border\n",
        1
    )
if "import androidx.compose.foundation.shape.RoundedCornerShape" not in pager:
    pager = pager.replace(
        "import androidx.compose.foundation.shape.CircleShape\n",
        "import androidx.compose.foundation.shape.CircleShape\nimport androidx.compose.foundation.shape.RoundedCornerShape\n",
        1
    )
if "import androidx.compose.ui.graphics.Color" not in pager:
    pager = pager.replace(
        "import androidx.compose.material3.MaterialTheme\n",
        "import androidx.compose.material3.MaterialTheme\nimport androidx.compose.ui.graphics.Color\n",
        1
    )
replace_once(
    pager_file,
    """        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .semantics {""",
    """        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 5.dp)
            .background(
                color = if (isSelected) Color(0xFF202D52)
                else MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(18.dp)
            )
            .border(
                width = 1.dp,
                color = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.82f)
                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.38f),
                shape = RoundedCornerShape(18.dp)
            )
            .clip(RoundedCornerShape(18.dp))
            .height(IntrinsicSize.Min)
            .semantics {""",
    "rounded server list cards"
)
pager = pager.replace("ItemDivider()", "Spacer(modifier = Modifier.height(6.dp))")
pager_file.write_text(pager, encoding="utf-8")

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
import com.v2ray.ang.MoghimiAccessDeniedException
import com.v2ray.ang.MoghimiCloudSync
import com.v2ray.ang.MoghimiSyncWorker
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
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
                    MoghimiCloudSync.sync(applicationContext)
                }
                mainViewModel.setupGroupTab(forceRefresh = true).join()
                if (autoSelect && result.importedProfiles > 0) {
                    mainViewModel.onAction(
                        MainAction.SelectGroup(MoghimiCloudSync.CLOUD_GROUP_ID)
                    )
                    val cloudServers = withTimeoutOrNull(15_000L) {
                        mainViewModel.serversForGroup(MoghimiCloudSync.CLOUD_GROUP_ID)
                            .first { servers -> servers.isNotEmpty() }
                    }
                    if (!cloudServers.isNullOrEmpty()) {
                        val selectedGuid = mainViewModel.uiState.value.selectedGuid
                        if (cloudServers.none { it.guid == selectedGuid }) {
                            mainViewModel.onAction(
                                MainAction.SelectServer(cloudServers.first().guid)
                            )
                        }
                    }
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
                if (error is MoghimiAccessDeniedException) {
                    // The sync service removed its local server group after an authoritative
                    // denial. Refresh both the cached groups and selected tab immediately.
                    mainViewModel.setupGroupTab(forceRefresh = true).join()
                    mainViewModel.onAction(MainAction.SelectGroup(""))
                }
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
query_permission = """    <uses-permission
        android:name="android.permission.QUERY_ALL_PACKAGES"
        tools:ignore="PackageVisibilityPolicy,QueryAllPackagesPermission" />
"""
if manifest_text.count(query_permission) != 1:
    raise SystemExit("Expected one broad package-visibility permission in upstream manifest")
manifest_text = manifest_text.replace(query_permission, "", 1)

root_tag = '    tools:ignore="MissingLeanbackLauncher">'
query_block = root_tag + """
    
    <!-- Only expose user-launchable apps for the optional per-app proxy picker. -->
    <queries>
        <intent>
            <action android:name="android.intent.action.MAIN" />
            <category android:name="android.intent.category.LAUNCHER" />
        </intent>
    </queries>"""
if manifest_text.count(root_tag) != 1:
    raise SystemExit("Could not locate manifest root for scoped package visibility")
manifest_text = manifest_text.replace(root_tag, query_block, 1)

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


# Feed byte counters from the same Xray stats query used by the speed notification.
# Metering stays active even when the user disables the visual speed notification.
notification_file = java / "handler/NotificationManager.kt"
replace_once(
    notification_file,
    "import com.v2ray.ang.core.CoreServiceManager\n",
    "import com.v2ray.ang.core.CoreServiceManager\nimport com.v2ray.ang.MoghimiLicenseManager\n",
    "Moghimi traffic meter import"
)
replace_once(
    notification_file,
    "    private var speedNotificationJob: Job? = null\n",
    "    private var speedNotificationJob: Job? = null\n    private var showSpeedStats = false\n",
    "independent visual speed preference"
)
replace_once(
    notification_file,
    """    fun startSpeedNotification() {
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_SPEED_ENABLED) != true) return
        if (speedNotificationJob != null || CoreServiceManager.isRunning() == false) return

        var lastZeroSpeed = false

        speedNotificationJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                lastZeroSpeed = updateSpeedNotificationOnce(lastZeroSpeed)
                delay(QUERY_INTERVAL_MS)
            }
        }
    }""",
    """    fun startSpeedNotification() {
        showSpeedStats = MmkvManager.decodeSettingsBool(AppConfig.PREF_SPEED_ENABLED) == true
        if (speedNotificationJob != null || CoreServiceManager.isRunning() == false) return

        speedNotificationJob = CoroutineScope(Dispatchers.IO).launch {
            val service = getService() ?: return@launch
            // Validate on the VPN service process's IO dispatcher, never block service main.
            if (!MoghimiLicenseManager.beginUsageSession(service.applicationContext)) {
                return@launch
            }
            var lastZeroSpeed = false
            while (isActive && CoreServiceManager.isRunning()) {
                lastZeroSpeed = updateSpeedNotificationOnce(lastZeroSpeed)
                delay(QUERY_INTERVAL_MS)
            }
        }
    }""",
    "start quota metering independently from speed notification setting"
)
replace_once(
    notification_file,
    """    fun stopSpeedNotification() {
        speedNotificationJob?.let {
            it.cancel()
            speedNotificationJob = null
            updateNotification("", 0, 0)
        }
    }""",
    """    fun stopSpeedNotification() {
        // Hide speed text only. The same Xray counters are also the subscription meter,
        // so the polling job must keep running while the VPN tunnel is active.
        showSpeedStats = false
        updateNotification("", 0, 0)
    }""",
    "keep quota meter active when speed notification is hidden"
)
replace_once(
    notification_file,
    """        val proxyTotal = proxyUplink + proxyDownlink
        val directTotal = directUplink + directDownlink
        val zeroSpeed = proxyTotal + directTotal == 0L
        if (!zeroSpeed || !lastZeroSpeed) {""",
    """        val proxyTotal = proxyUplink + proxyDownlink
        val directTotal = directUplink + directDownlink
        val zeroSpeed = proxyTotal + directTotal == 0L

        // Xray reports byte deltas for every outbound, including direct routes.
        // Blocked traffic is excluded above; both upload and download are billed.
        getService()?.let { service ->
            MoghimiLicenseManager.recordTraffic(
                service.applicationContext,
                proxyDownlink + directDownlink,
                proxyUplink + directUplink
            )
        }

        if (showSpeedStats && (!zeroSpeed || !lastZeroSpeed)) {""",
    "record exact Xray outbound upload and download deltas"
)

print("Moghimi branding and Xray traffic meter patch applied.")
PY

python3 "$ROOT/android/patch-updater.py" "$PROJECT"

# Keep Xray byte counters enabled for subscription accounting even when the
# user has turned off the optional notification speed display.
python3 - "$PROJECT" <<'PY'
from pathlib import Path
import sys

project = Path(sys.argv[1])
java = project / "app/src/main/java/com/v2ray/ang"

def replace_once(path: Path, old: str, new: str, label: str):
    source = path.read_text(encoding="utf-8")
    count = source.count(old)
    if count != 1:
        raise SystemExit(f"Expected one match for {label}; found {count}")
    path.write_text(source.replace(old, new, 1), encoding="utf-8")

core_config = java / "core/CoreConfigManager.kt"
replace_once(
    core_config,
    """        // Inject or remove traffic statistics configuration based on user preference
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_SPEED_ENABLED) == true) {
            if (!json.has("stats")) {
                json.add("stats", JsonObject())
            }
            if (!json.has("policy")) {
                val policyObj = JsonObject()
                val systemObj = JsonObject()
                systemObj.addProperty("statsOutboundUplink", true)
                systemObj.addProperty("statsOutboundDownlink", true)
                policyObj.add("system", systemObj)
                json.add("policy", policyObj)
            }
        } else {
            json.remove("stats")
            // Keep user-defined policy levels, only strip the stats-related system block
            json.get("policy")?.takeIf { it.isJsonObject }?.asJsonObject?.let { policy ->
                policy.remove("system")
                if (policy.entrySet().isEmpty()) {
                    json.remove("policy")
                }
            }
        }""",
    """        // Xray traffic counters are required for Moghimi subscription accounting.
        // NotificationManager separately controls whether speed values are shown.
        if (!json.has("stats")) {
            json.add("stats", JsonObject())
        }
        val policyObj = json.get("policy")?.takeIf { it.isJsonObject }?.asJsonObject
            ?: JsonObject().also { json.add("policy", it) }
        val systemObj = policyObj.get("system")?.takeIf { it.isJsonObject }?.asJsonObject
            ?: JsonObject().also { policyObj.add("system", it) }
        systemObj.addProperty("statsOutboundUplink", true)
        systemObj.addProperty("statsOutboundDownlink", true)""",
    "keep custom-profile Xray counters enabled"
)

replace_once(
    core_config,
    """    private fun applySpeedDisabled(v2rayConfig: V2rayConfig) {
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_SPEED_ENABLED) != true) {
            v2rayConfig.stats = null
            v2rayConfig.policy?.system = null
        }
    }""",
    """    private fun applySpeedDisabled(v2rayConfig: V2rayConfig) {
        // Do not strip stats or policy.system: the Moghimi subscription meter
        // needs reset-on-query Xray counters. Visual speed text remains optional.
    }""",
    "preserve Xray counters in generated profiles"
)

vm = java / "ui/main/MainViewModel.kt"
replace_once(
    vm,
    "import java.util.concurrent.ConcurrentHashMap\n",
    "import java.util.UUID\nimport java.util.concurrent.ConcurrentHashMap\n",
    "ping request UUID import"
)
replace_once(
    vm,
    "    private var bulkTestJob: Job? = null\n",
    "    private var bulkTestJob: Job? = null\n    private var currentPingTimeoutJob: Job? = null\n    private var currentPingRequestId: String? = null\n",
    "single-server ping timeout state"
)
replace_once(
    vm,
    """            is MainServiceEvent.MeasureDelayResult -> {
                if (!uiState.value.isRunning) return
                _uiState.update { it.copy(isTesting = false, status = MainStatus.ConnectionTest(event.result)) }
            }""",
    """            is MainServiceEvent.MeasureDelayResult -> {
                currentPingTimeoutJob?.cancel()
                currentPingTimeoutJob = null
                currentPingRequestId = null
                if (!uiState.value.isRunning) return
                _uiState.update { it.copy(isTesting = false, status = MainStatus.ConnectionTest(event.result)) }
            }""",
    "clear ping timeout after real result"
)
replace_once(
    vm,
    """            is MainServiceEvent.MeasureDelayCancelled -> {
                //if (testRequests.completeCurrent(event.requestId)) resetTestStatus()
            }""",
    """            is MainServiceEvent.MeasureDelayCancelled -> {
                if (event.requestId == currentPingRequestId) {
                    currentPingTimeoutJob?.cancel()
                    currentPingTimeoutJob = null
                    currentPingRequestId = null
                    resetTestStatus()
                }
            }""",
    "handle ping cancellation event"
)
replace_once(
    vm,
    """    fun testCurrentServerRealPing() {
        if (!uiState.value.isRunning) return
        _uiState.update { it.copy(isTesting = true, status = MainStatus.Testing) }
        dataSource.testCurrentServerRealPing("")
    }""",
    """    fun testCurrentServerRealPing() {
        if (!uiState.value.isRunning) return
        currentPingTimeoutJob?.cancel()
        val requestId = UUID.randomUUID().toString()
        currentPingRequestId = requestId
        _uiState.update { it.copy(isTesting = true, status = MainStatus.Testing) }
        currentPingTimeoutJob = viewModelScope.launch {
            delay(15_000L)
            if (currentPingRequestId == requestId && uiState.value.isRunning && uiState.value.isTesting) {
                currentPingRequestId = null
                currentPingTimeoutJob = null
                resetTestStatus()
            }
        }
        dataSource.testCurrentServerRealPing(requestId)
    }""",
    "add single-server ping timeout"
)

print("Moghimi Xray metering and real-ping timeout patches applied.")
PY


# Protect Moghimi-owned cloud profiles from per-server and bulk export/share actions.
python3 - "$PROJECT" <<'PY'
from pathlib import Path
import sys

project = Path(sys.argv[1])
java = project / "app/src/main/java/com/v2ray/ang"

def replace_once(path: Path, old: str, new: str, label: str):
    source = path.read_text(encoding="utf-8")
    count = source.count(old)
    if count != 1:
        raise SystemExit(f"Expected one match for {label} in {path}; found {count}")
    path.write_text(source.replace(old, new, 1), encoding="utf-8")

# The group ID is the ownership boundary: personal/user-imported configs remain exportable.
menu_file = java / "ui/main/MainImportMenu.kt"
replace_once(
    menu_file,
    """internal fun serverMenuActions(
    isComplexProfile: Boolean,
    includeManagementActions: Boolean,
): List<ServerMenuAction> = ServerMenuAction.entries.filter { action ->
    (includeManagementActions || action.isShareAction) && (!isComplexProfile || action.supportsComplexProfiles)
}""",
    """internal fun serverMenuActions(
    isComplexProfile: Boolean,
    includeManagementActions: Boolean,
    includeShareActions: Boolean = true,
): List<ServerMenuAction> = ServerMenuAction.entries.filter { action ->
    val allowedByActionType = if (action.isShareAction) includeShareActions else includeManagementActions
    allowedByActionType && (!isComplexProfile || action.supportsComplexProfiles)
}""",
    "server menu share-action filtering"
)
replace_once(
    menu_file,
    """    more: Boolean,
    onDismiss: () -> Unit,""",
    """    more: Boolean,
    allowShareActions: Boolean = true,
    onDismiss: () -> Unit,""",
    "ShareMethodDialog allowShareActions parameter"
)
replace_once(
    menu_file,
    """        includeManagementActions = more,
    )""",
    """        includeManagementActions = more,
        includeShareActions = allowShareActions,
    )""",
    "filter share methods in dialog"
)

# Pass cloud ownership to each server row. In list mode hide the share shortcut;
# in grid mode keep the More menu for edit/delete, but filter its share actions above.
pager_file = java / "ui/main/MainServerPager.kt"
replace_once(
    pager_file,
    """    val canReorder = groupId.isNotEmpty() && searchQuery.isEmpty()
    val actions = remember(
        onSelectServer,
        onEditServer,
        onShareServer,
        onMoreServer,
        onRemoveServer,
    ) {
        ServerRowActions(
            select = onSelectServer,
            edit = onEditServer,
            share = onShareServer,
            more = onMoreServer,
            remove = onRemoveServer,
        )
    }""",
    """    val canReorder = groupId.isNotEmpty() && searchQuery.isEmpty()
    val allowShareActions = groupId != com.v2ray.ang.MoghimiCloudSync.CLOUD_GROUP_ID
    val actions = remember(
        onSelectServer,
        onEditServer,
        onShareServer,
        onMoreServer,
        onRemoveServer,
        allowShareActions,
    ) {
        ServerRowActions(
            select = onSelectServer,
            edit = onEditServer,
            share = onShareServer,
            more = onMoreServer,
            remove = onRemoveServer,
            allowShareActions = allowShareActions,
        )
    }""",
    "pass cloud ownership to server rows"
)
replace_once(
    pager_file,
    """    val more: (String, ProfileItem) -> Unit,
    val remove: (String, String) -> Unit,
)""",
    """    val more: (String, ProfileItem) -> Unit,
    val remove: (String, String) -> Unit,
    val allowShareActions: Boolean,
)""",
    "store export permission in row actions"
)
replace_once(
    pager_file,
    """                } else {
                    IconButton(onClick = { actions.share(row.guid, row.profile) }, Modifier.size(36.dp)) {
                        Icon(
                            painterResource(R.drawable.ic_share_24dp),
                            stringResource(R.string.title_configuration_share),
                            Modifier.size(24.dp)
                        )
                    }
                    IconButton(onClick = { actions.edit(row.guid, row.profile) }, Modifier.size(36.dp)) {""",
    """                } else {
                    if (actions.allowShareActions) {
                        IconButton(onClick = { actions.share(row.guid, row.profile) }, Modifier.size(36.dp)) {
                            Icon(
                                painterResource(R.drawable.ic_share_24dp),
                                stringResource(R.string.title_configuration_share),
                                Modifier.size(24.dp)
                            )
                        }
                    }
                    IconButton(onClick = { actions.edit(row.guid, row.profile) }, Modifier.size(36.dp)) {""",
    "hide share shortcut for Moghimi server rows"
)

# Preserve the ownership bit while opening the server actions dialog.
screen_file = java / "ui/main/MainScreen.kt"
replace_once(
    screen_file,
    """@Composable
fun MainScreen(""",
    """private data class ServerActionTarget(
    val guid: String,
    val profile: ProfileItem,
    val more: Boolean,
    val allowShareActions: Boolean,
)

@Composable
fun MainScreen(""",
    "server action target model"
)
replace_once(
    screen_file,
    """    var shareTarget by remember { mutableStateOf<Triple<String, ProfileItem, Boolean>?>(null) }""",
    """    var shareTarget by remember { mutableStateOf<ServerActionTarget?>(null) }""",
    "server action state"
)
replace_once(
    screen_file,
    """    if (shareTarget != null) {
        val (guid, profile, more) = shareTarget!!
        ShareMethodDialog(
            guid = guid,
            profile = profile,
            more = more,
            onDismiss = { shareTarget = null },
            onAction = onAction,
            onRemove = removeServer,
        )
    }""",
    """    if (shareTarget != null) {
        val target = shareTarget!!
        ShareMethodDialog(
            guid = target.guid,
            profile = target.profile,
            more = target.more,
            allowShareActions = target.allowShareActions,
            onDismiss = { shareTarget = null },
            onAction = onAction,
            onRemove = removeServer,
        )
    }""",
    "apply ownership filtering to share dialog"
)
replace_once(
    screen_file,
    """                            onShareServer = { guid, profile ->
                                shareTarget = Triple(guid, profile, false)
                            },
                            onMoreServer = { guid, profile ->
                                shareTarget = Triple(guid, profile, true)
                            },""",
    """                            onShareServer = { guid, profile ->
                                shareTarget = ServerActionTarget(
                                    guid, profile, false,
                                    group.id != MoghimiCloudSync.CLOUD_GROUP_ID
                                )
                            },
                            onMoreServer = { guid, profile ->
                                shareTarget = ServerActionTarget(
                                    guid, profile, true,
                                    group.id != MoghimiCloudSync.CLOUD_GROUP_ID
                                )
                            },""",
    "set sharing permission per group"
)

# Defence in depth: reject export events at the Activity layer as well as hiding UI.
activity_file = java / "ui/main/MainActivity.kt"
replace_once(
    activity_file,
    """                    is MainAction.ShareClipboard -> shareToClipboard(action.guid)
                    is MainAction.ShareFullContent -> shareFullContentAsync(action.guid)""",
    """                    is MainAction.ShareQRCode -> {
                        if (!isMoghimiCloudProfile(action.guid)) mainViewModel.onAction(action)
                    }
                    is MainAction.ShareClipboard -> {
                        if (!isMoghimiCloudProfile(action.guid)) shareToClipboard(action.guid)
                    }
                    is MainAction.ShareFullContent -> {
                        if (!isMoghimiCloudProfile(action.guid)) shareFullContentAsync(action.guid)
                    }""",
    "block direct share events for Moghimi profiles"
)
replace_once(
    activity_file,
    """    private fun shareToClipboard(guid: String): Boolean =""",
    """    private fun isMoghimiCloudProfile(guid: String): Boolean =
        MmkvManager.decodeServerConfig(guid)?.subscriptionId == MoghimiCloudSync.CLOUD_GROUP_ID

    private fun shareToClipboard(guid: String): Boolean =""",
    "activity export guard helper"
)

# The global Export All action must skip provider-owned profiles but keep personal profiles.
view_model_file = java / "ui/main/MainViewModel.kt"
replace_once(
    view_model_file,
    """                    val ret = dataSource.shareNonCustomConfigsToClipboard(list)""",
    """                    val personalServerList = list.filter { guid ->
                        val profile = dataSource.decodeServerConfig(guid)
                        profile != null &&
                            profile.subscriptionId != com.v2ray.ang.MoghimiCloudSync.CLOUD_GROUP_ID
                    }
                    val ret = dataSource.shareNonCustomConfigsToClipboard(personalServerList)""",
    "exclude Moghimi group from Export All"
)

print("Moghimi cloud server export/share restrictions applied.")

# Improve upstream-network handover recovery.
core_manager_file = java / "core/CoreServiceManager.kt"
core_manager = core_manager_file.read_text(encoding="utf-8")
old_handover = "onHandover = { reloadCore() },"
new_handover = "onHandover = { reloadCoreWithRetry() },"
if old_handover in core_manager:
    if core_manager.count(old_handover) != 1:
        raise SystemExit("Expected exactly one network handover callback")
    core_manager = core_manager.replace(old_handover, new_handover, 1)
elif new_handover not in core_manager:
    raise SystemExit("Could not locate network handover callback")
handover_retry = """    private fun reloadCoreWithRetry() {
        for (attempt in 0 until 3) {
            if (reloadCore()) return
            LogUtil.w(
                AppConfig.TAG,
                "StartCore-Manager: Network handover reload attempt ${attempt + 1} failed"
            )
            if (attempt < 2) {
                try {
                    Thread.sleep(1_000L * (attempt + 1))
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
            }
        }
        LogUtil.e(AppConfig.TAG, "StartCore-Manager: Network handover recovery failed after retries")
    }

"""
marker = "    private fun reloadCore(): Boolean {"
if "private fun reloadCoreWithRetry()" not in core_manager:
    if core_manager.count(marker) != 1:
        raise SystemExit("Could not locate CoreServiceManager reloadCore insertion point")
    core_manager = core_manager.replace(marker, handover_retry + marker, 1)
core_manager_file.write_text(core_manager, encoding="utf-8")


PY

echo "Prepared branded Android source under $PROJECT"
