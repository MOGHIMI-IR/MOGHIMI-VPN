package com.v2ray.ang.ui.main

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.R
import kotlinx.coroutines.delay

@Composable
fun MoghimiRootScreen(
    mainViewModel: MainViewModel,
    onCloudSyncClick: () -> Unit,
    onAction: (MainAction) -> Unit,
    onNavigate: (MainDestination) -> Unit
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    androidx.compose.runtime.CompositionLocalProvider(
        LocalLayoutDirection provides LayoutDirection.Rtl
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            Crossfade(
                targetState = selectedTab,
                animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                label = "moghimi-main-tabs"
            ) { page ->
                when (page) {
                    0 -> MoghimiConnectionPage(
                        mainViewModel = mainViewModel,
                        onConnectClick = { onAction(MainAction.ToggleService) },
                        onConfigsClick = { selectedTab = 1 },
                        onCloudSyncClick = onCloudSyncClick
                    )
                    1 -> MainScreen(
                        mainViewModel = mainViewModel,
                        onCloudSyncClick = onCloudSyncClick,
                        onAction = onAction,
                        onNavigate = onNavigate,
                        showConnectionBar = false
                    )
                    else -> MoghimiSettingsPage(onNavigate = onNavigate)
                }
            }

            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                contentColor = MaterialTheme.colorScheme.onSurface,
                tonalElevation = 8.dp
            ) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_moghimi_tab_connect),
                            contentDescription = null,
                            modifier = Modifier.size(23.dp)
                        )
                    },
                    label = { Text("اتصال") },
                    colors = moghimiNavigationColors()
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_moghimi_tab_configs),
                            contentDescription = null,
                            modifier = Modifier.size(23.dp)
                        )
                    },
                    label = { Text("کانفیگ‌ها") },
                    colors = moghimiNavigationColors()
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_moghimi_tab_settings),
                            contentDescription = null,
                            modifier = Modifier.size(23.dp)
                        )
                    },
                    label = { Text("تنظیمات") },
                    colors = moghimiNavigationColors()
                )
            }
        }
    }
}

@Composable
private fun moghimiNavigationColors() = NavigationBarItemDefaults.colors(
    selectedIconColor = Color(0xFF101827),
    selectedTextColor = MaterialTheme.colorScheme.primary,
    indicatorColor = MaterialTheme.colorScheme.primary,
    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
)

@Composable
private fun MoghimiConnectionPage(
    mainViewModel: MainViewModel,
    onConnectClick: () -> Unit,
    onConfigsClick: () -> Unit,
    onCloudSyncClick: () -> Unit
) {
    val uiState by mainViewModel.uiState.collectAsStateWithLifecycle()
    val selectedGroupFlow = remember(mainViewModel, uiState.selectedGroupId) {
        mainViewModel.serverGroupState(uiState.selectedGroupId)
    }
    val selectedGroup by selectedGroupFlow.collectAsStateWithLifecycle()
    val selectedServer = selectedGroup.rows.firstOrNull { it.guid == uiState.selectedGuid }
    val running = uiState.isRunning
    val measuredPingMillis =
        (uiState.status as? MainStatus.ConnectionTest)?.result?.delayMillis
    val pingStatusText = when {
        !running || selectedServer == null -> "—"
        uiState.isTesting -> "در حال سنجش"
        measuredPingMillis == null -> "در انتظار"
        measuredPingMillis >= 0L -> "$measuredPingMillis ms"
        else -> "ناموفق"
    }

    // Read the current tunnel's real latency, not the saved profile TCP test.
    // Re-check after connecting and every 30 seconds while the tunnel stays active.
    LaunchedEffect(running, selectedServer?.guid) {
        if (!running || selectedServer == null) return@LaunchedEffect
        delay(1_200L)
        while (true) {
            if (!mainViewModel.uiState.value.isTesting) {
                mainViewModel.testCurrentServerRealPing()
            }
            delay(30_000L)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "مقیمیv2ray",
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 23.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Text(
                    text = "اتصال امن، سریع و هوشمند",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp
                )
            }
            Image(
                painter = painterResource(R.drawable.moghimi_logo),
                contentDescription = "نشان مقیمی",
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
            )
        }

        Spacer(Modifier.height(12.dp))

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.38f)
            )
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 15.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(if (running) Color(0xFF50E0B5) else Color(0xFFFFD34D))
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (running) "وضعیت: متصل" else "وضعیت: قطع",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Text(
                        text = if (running) "ارتباط از طریق تونل امن برقرار است" else "برای شروع، دکمهٔ اتصال را بزن",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp
                    )
                }
                Column(
                    horizontalAlignment = Alignment.End,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(enabled = running && !uiState.isTesting) {
                            mainViewModel.testCurrentServerRealPing()
                        }
                        .padding(start = 8.dp, top = 3.dp, bottom = 3.dp)
                ) {
                    Text(
                        text = "پینگ واقعی",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = pingStatusText,
                        color = when {
                            !running -> MaterialTheme.colorScheme.onSurfaceVariant
                            measuredPingMillis != null && measuredPingMillis >= 0L ->
                                Color(0xFF50E0B5)
                            else -> Color(0xFFFFD34D)
                        },
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        MoghimiConnectionOrb(
            connected = running,
            onClick = onConnectClick
        )

        Text(
            text = if (running) "اتصال شما فعال است" else "برای اتصال لمس کنید",
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = if (running) "برای پایان ارتباط، دکمهٔ مرکزی را لمس کنید" else "با انتخاب کانفیگ، اتصال خود را آغاز کن",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall
        )

        Spacer(Modifier.height(22.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(Modifier.padding(18.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "کانفیگ انتخاب‌شده",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.height(5.dp))
                        Text(
                            text = selectedServer?.remarks ?: "هنوز کانفیگی انتخاب نشده",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(13.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                    ) {
                        Text(
                            text = selectedServer?.typeDescription ?: "بدون انتخاب",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = onConfigsClick,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(15.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = Color(0xFF101827)
                        )
                    ) {
                        Text("انتخاب کانفیگ", fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = onCloudSyncClick,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(15.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    ) {
                        Text("دریافت از سایت", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun MoghimiConnectionOrb(
    connected: Boolean,
    onClick: () -> Unit
) {
    val transition = rememberInfiniteTransition(label = "moghimi-orb")
    val pulse by transition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "orb-pulse"
    )
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(9000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "orb-rotation"
    )
    val accent = if (connected) Color(0xFF50E0B5) else Color(0xFFFFD34D)
    val secondary = Color(0xFF9B83FF)

    Box(
        modifier = Modifier.size(264.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2f, size.height / 2f)
            val radius = size.minDimension * 0.43f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(accent.copy(alpha = 0.22f), Color.Transparent),
                    center = c,
                    radius = radius * 1.25f
                ),
                radius = radius * 1.25f,
                center = c
            )
            drawCircle(
                color = accent.copy(alpha = 0.16f),
                radius = radius * pulse,
                center = c,
                style = Stroke(width = 2.dp.toPx())
            )
            drawCircle(
                color = secondary.copy(alpha = 0.16f),
                radius = radius * 0.82f,
                center = c,
                style = Stroke(width = 1.dp.toPx())
            )
            rotate(rotation, pivot = c) {
                drawArc(
                    color = accent.copy(alpha = 0.95f),
                    startAngle = 205f,
                    sweepAngle = 88f,
                    useCenter = false,
                    topLeft = Offset(c.x - radius, c.y - radius),
                    size = Size(radius * 2f, radius * 2f),
                    style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round)
                )
                drawCircle(
                    color = accent,
                    radius = 4.dp.toPx(),
                    center = Offset(c.x, c.y - radius)
                )
            }
            rotate(-rotation * 0.62f, pivot = c) {
                drawArc(
                    color = secondary.copy(alpha = 0.9f),
                    startAngle = 26f,
                    sweepAngle = 68f,
                    useCenter = false,
                    topLeft = Offset(c.x - radius * 0.82f, c.y - radius * 0.82f),
                    size = Size(radius * 1.64f, radius * 1.64f),
                    style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
                )
            }
        }

        Column(
            modifier = Modifier
                .size(154.dp)
                .shadow(22.dp, CircleShape)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        if (connected) {
                            listOf(Color(0xFF174C47), Color(0xFF102A42), Color(0xFF16243B))
                        } else {
                            listOf(Color(0xFF5B4B1C), Color(0xFF263050), Color(0xFF18243E))
                        }
                    )
                )
                .border(
                    width = 2.dp,
                    color = accent.copy(alpha = 0.82f),
                    shape = CircleShape
                )
                .clickable(
                    onClick = onClick,
                    role = Role.Button
                )
                .semantics {
                    contentDescription = if (connected) "قطع اتصال وی‌پی‌ان" else "اتصال وی‌پی‌ان"
                    role = Role.Button
                },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                painter = painterResource(
                    if (connected) R.drawable.ic_stop_24dp else R.drawable.ic_play_24dp
                ),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(37.dp)
            )
            Spacer(Modifier.height(7.dp))
            Text(
                text = if (connected) "قطع اتصال" else "اتصال",
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.ExtraBold
            )
        }
    }
}

private data class MoghimiSettingShortcut(
    val title: String,
    val summary: String,
    val destination: MainDestination
)

@Composable
private fun MoghimiSettingsPage(
    onNavigate: (MainDestination) -> Unit
) {
    val context = LocalContext.current
    val shortcuts = listOf(
        MoghimiSettingShortcut("تنظیمات برنامه", "ظاهر، زبان و رفتار برنامه", MainDestination.Settings),
        MoghimiSettingShortcut("اشتراک‌ها", "مدیریت و به‌روزرسانی اشتراک‌ها", MainDestination.Subscriptions),
        MoghimiSettingShortcut("مسیریابی", "قوانین عبور ترافیک", MainDestination.Routing),
        MoghimiSettingShortcut("پروکسی برنامه‌ها", "انتخاب برنامه‌های عبوری از تونل", MainDestination.PerAppProxy),
        MoghimiSettingShortcut("پشتیبان‌گیری و بازیابی", "ذخیره یا بازگرداندن اطلاعات", MainDestination.BackupRestore),
        MoghimiSettingShortcut("گزارش اتصال", "بررسی رخدادها و خطاهای برنامه", MainDestination.Logcat),
        MoghimiSettingShortcut("بررسی به‌روزرسانی", "مشاهدهٔ نسخه‌های جدید", MainDestination.CheckUpdate),
        MoghimiSettingShortcut("دربارهٔ برنامه", "اطلاعات مقیمیv2ray و مجوزها", MainDestination.About)
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 18.dp)
    ) {
        Text(
            text = "تنظیمات",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.ExtraBold
        )
        Text(
            text = "مدیریت بخش‌های مختلف مقیمیv2ray",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 18.dp)
        )

        shortcuts.forEachIndexed { index, item ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp)
                    .clickable { onNavigate(item.destination) },
                shape = RoundedCornerShape(19.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 15.dp, vertical = 15.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        modifier = Modifier.size(43.dp),
                        shape = RoundedCornerShape(14.dp),
                        color = if (index % 2 == 0) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                        } else {
                            MaterialTheme.colorScheme.secondary.copy(alpha = 0.14f)
                        }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                painter = painterResource(
                                    if (index % 2 == 0) R.drawable.ic_moghimi_tab_settings
                                    else R.drawable.ic_moghimi_tab_configs
                                ),
                                contentDescription = null,
                                tint = if (index % 2 == 0) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.secondary
                                },
                                modifier = Modifier.size(21.dp)
                            )
                        }
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 13.dp)
                    ) {
                        Text(
                            text = item.title,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Text(
                            text = item.summary,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 3.dp)
                        )
                    }
                    Text(
                        text = "‹",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 25.sp
                    )
                 }
            }
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 10.dp)
                .clickable {
                    runCatching {
                        context.startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://t.me/Moghimi_v2ray")
                            )
                        )
                    }
                },
            shape = RoundedCornerShape(19.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 15.dp, vertical = 15.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    modifier = Modifier.size(43.dp),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.14f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "✈",
                            color = MaterialTheme.colorScheme.secondary,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 13.dp)
                ) {
                    Text(
                        text = "کانال تلگرام مقیمی",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Text(
                        text = "@Moghimi_v2ray",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 3.dp)
                    )
                }
                Text(
                    text = "‹",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 25.sp
                )
            }
        }
    }
}