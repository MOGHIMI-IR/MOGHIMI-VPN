package com.v2ray.ang.ui.main

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val MOGHIMI_SUPPORT_CARD = "6037998221406080"
private const val MOGHIMI_APP_REPOSITORY = "https://github.com/MOGHIMI-IR/MOGHIMI-VPN"
private const val MOGHIMI_BOT_REPOSITORY = "https://github.com/MOGHIMI-IR/bot-telegram_moghimi"
private const val GITHUB_STAR_GUIDE =
    "https://github.com/MOGHIMI-IR/MOGHIMI-VPN/blob/main/docs/STAR_GUIDE_FA.md"

private object MoghimiSupportPromptSession {
    @Volatile var dismissed: Boolean = false
}

private fun copySupportCard(context: Context) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("شماره کارت حمایت", MOGHIMI_SUPPORT_CARD))
    Toast.makeText(context, "شماره کارت کپی شد", Toast.LENGTH_SHORT).show()
}

private fun openSupportLink(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }.onFailure {
        Toast.makeText(context, "بازکردن پیوند ممکن نشد", Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun MoghimiStartupSupportDialog() {
    var visible by remember { mutableStateOf(!MoghimiSupportPromptSession.dismissed) }
    if (!visible) return
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = {
            MoghimiSupportPromptSession.dismissed = true
            visible = false
        },
        title = {
            Text("حمایت از ادامهٔ توسعه", fontWeight = FontWeight.ExtraBold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("برای ادامهٔ توسعه و نگهداری مقیمی V2Ray، می‌توانی از پروژه حمایت کنی.")
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            "شماره کارت",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            MOGHIMI_SUPPORT_CARD,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Bold,
                            fontSize = 19.sp
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { copySupportCard(context) }) {
                Text("کپی شماره کارت")
            }
        },
        dismissButton = {
            TextButton(onClick = {
                MoghimiSupportPromptSession.dismissed = true
                visible = false
            }) {
                Text("بستن")
            }
        },
        containerColor = MaterialTheme.colorScheme.surface
    )
}

@Composable
fun MoghimiSupportSettingsSection() {
    val context = LocalContext.current

    Card(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 20.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "حمایت از مقیمی V2Ray",
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold
            )
            Text(
                "با کمک مالی یا یک ستاره در GitHub، به ادامهٔ توسعه و نگهداری برنامه کمک می‌کنی.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
            Text("شماره کارت", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(13.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                Text(
                    MOGHIMI_SUPPORT_CARD,
                    modifier = Modifier.padding(14.dp),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Button(onClick = { copySupportCard(context) }, modifier = Modifier.fillMaxWidth()) {
                Text("کپی شماره کارت")
            }
            OutlinedButton(
                onClick = { openSupportLink(context, MOGHIMI_APP_REPOSITORY) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("⭐ رفتن به مخزن و ثبت ستاره")
            }
            OutlinedButton(
                onClick = { openSupportLink(context, GITHUB_STAR_GUIDE) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("آموزش فارسی ستاره‌دادن")
            }
            TextButton(
                onClick = { openSupportLink(context, MOGHIMI_APP_REPOSITORY) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("مشاهدهٔ مخزن برنامه")
            }
            TextButton(
                onClick = { openSupportLink(context, MOGHIMI_BOT_REPOSITORY) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("مشاهدهٔ مخزن بات تلگرام")
            }
        }
    }
}
