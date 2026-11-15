package com.v2ray.ang.ui.main

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.MoghimiLicenseManager
import com.v2ray.ang.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun MoghimiProfilePage() {
    val context = LocalContext.current
    val license by MoghimiLicenseManager.state.collectAsStateWithLifecycle()
    var username by rememberSaveable { mutableStateOf(MoghimiLicenseManager.username(context)) }
    var phone by rememberSaveable { mutableStateOf(MoghimiLicenseManager.phone(context)) }
    var activationCode by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { MoghimiLicenseManager.refreshStatus(context) }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("پروفایل و اشتراک", style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.ExtraBold)
        Text("حساب دستگاه، مصرف ماهانه و وضعیت اشتراک از این بخش قابل مدیریت است.",
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)

        if (!license.isRegistered) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("ساخت پروفایل دستگاه", color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("برای فعال‌شدن سهمیه رایگان، نام کاربری تلگرام را دقیق وارد کن. شماره موبایل اختیاری است.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = username, onValueChange = { username = it.trimStart('@') },
                        modifier = Modifier.fillMaxWidth(), label = { Text("نام کاربری تلگرام") },
                        placeholder = { Text("username") }, singleLine = true
                    )
                    OutlinedTextField(
                        value = phone, onValueChange = { phone = it.filter(Char::isDigit).take(11) },
                        modifier = Modifier.fillMaxWidth(), label = { Text("شماره موبایل (اختیاری)") },
                        placeholder = { Text("09xxxxxxxxx") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone)
                    )
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                    Button(
                        onClick = {
                            busy = true; error = null; message = null
                            kotlinx.coroutines.MainScope().launch {
                                try {
                                    withContext(Dispatchers.IO) {
                                        MoghimiLicenseManager.register(context, username, phone.ifBlank { null })
                                    }
                                    message = "پروفایل دستگاه ثبت شد."
                                } catch (e: Exception) {
                                    error = e.localizedMessage ?: "ثبت پروفایل انجام نشد."
                                } finally { busy = false }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(), enabled = !busy && username.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) { Text(if (busy) "در حال ثبت…" else "ثبت پروفایل و دریافت سهمیه", fontWeight = FontWeight.Bold) }
                }
            }
        } else {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("@" + license.username, color = MaterialTheme.colorScheme.onSurface,
                                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text(if (license.phone.isBlank()) "شماره‌ای ثبت نشده" else license.phone,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        }
                        Surface(shape = RoundedCornerShape(12.dp),
                            color = if (license.accessAllowed) Color(0xFF143D36) else Color(0xFF47321D)) {
                            Text(if (license.accessAllowed) "فعال" else "نیازمند اشتراک",
                                color = if (license.accessAllowed) Color(0xFF69E2BB) else Color(0xFFFFD47A),
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("سهمیه رایگان این ماه", color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall)
                    Text(
                        MoghimiLicenseManager.formatBytes(license.freeUsedBytes) + " از " +
                            MoghimiLicenseManager.formatBytes(license.freeLimitBytes),
                        color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold
                    )
                    val fraction = (license.freeUsedBytes.toFloat() / license.freeLimitBytes.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
                    Box(Modifier.fillMaxWidth().height(9.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))) {
                        Box(Modifier.fillMaxWidth(fraction).height(9.dp)
                            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp)))
                    }
                    Text("باقی‌مانده رایگان: " + MoghimiLicenseManager.formatBytes(license.freeRemainingBytes),
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    Spacer(Modifier.height(6.dp))
                    Text("اشتراک خریداری‌شده", color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall)
                    Text(MoghimiLicenseManager.formatBytes(license.paidRemainingBytes),
                        color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
                    license.paidExpiresAt?.let {
                        Text("اعتبار تا: " + it.replace("T", " ").take(16),
                            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                    }
                    if (!license.accessAllowed) {
                        Text("سهمیه قابل‌استفاده تمام شده است. فهرست سرورها تا فعال‌شدن اشتراک نمایش داده نمی‌شود.",
                            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    Button(
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/Moghimi_vpn_bot"))
                            runCatching { context.startActivity(intent) }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("خرید اشتراک از بات تلگرام", fontWeight = FontWeight.Bold) }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("فعال‌سازی اشتراک", color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("کد یک‌بارمصرفی را که پس از تأیید رسید از بات گرفته‌ای وارد کن.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = activationCode, onValueChange = { activationCode = it.uppercase().filter { c -> c.isLetterOrDigit() }.take(64) },
                        modifier = Modifier.fillMaxWidth(), label = { Text("کد فعال‌سازی") }, singleLine = true
                    )
                    Button(
                        onClick = {
                            busy = true; error = null; message = null
                            kotlinx.coroutines.MainScope().launch {
                                try {
                                    withContext(Dispatchers.IO) {
                                        MoghimiLicenseManager.claimActivationCode(context, activationCode)
                                    }
                                    activationCode = ""
                                    message = "اشتراک فعال شد."
                                } catch (e: Exception) {
                                    error = e.localizedMessage ?: "فعال‌سازی انجام نشد."
                                } finally { busy = false }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(), enabled = !busy && activationCode.length >= 32
                    ) { Text(if (busy) "در حال بررسی…" else "فعال‌سازی کد") }
                }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
        message?.let { Text(it, color = Color(0xFF63DDB6), fontSize = 12.sp) }
        license.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 11.sp) }
    }
}
