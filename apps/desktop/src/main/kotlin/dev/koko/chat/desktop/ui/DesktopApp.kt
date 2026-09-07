package dev.koko.chat.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.koko.chat.desktop.presentation.DesktopScreenModel
import dev.koko.chat.desktop.presentation.DesktopUiState
import dev.koko.chat.desktop.presentation.ProbeStatus

private val Ink = Color(0xFF213D38)
private val Accent = Color(0xFF167565)
private val Muted = Color(0xFF77817D)
private val Canvas = Color(0xFFF6F8F6)
private val Line = Color(0xFFE5EAE5)

@Composable
fun DesktopApp(model: DesktopScreenModel, closing: Boolean) {
    val state by model.state.collectAsState()
    MaterialTheme(colorScheme = lightColorScheme(primary = Accent, background = Canvas, surface = Color.White)) {
        Row(Modifier.fillMaxSize().background(Canvas)) {
            Column(Modifier.width(76.dp).fillMaxHeight().background(Ink).padding(vertical = 26.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(42.dp).background(Color(0xFFDAF2D3), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                    Text("k", color = Ink, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(44.dp))
                Text("会话", color = Color.White, fontSize = 13.sp)
                Spacer(Modifier.weight(1f))
                Text("桌面端", color = Color(0xFFB2C7BE), fontSize = 11.sp)
            }
            Column(Modifier.width(276.dp).fillMaxHeight().background(Color.White).padding(24.dp)) {
                Text("koko-chat", color = Ink, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text("让对话，慢慢发生。", color = Muted, fontSize = 12.sp)
                Spacer(Modifier.height(32.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("会话", color = Ink, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text("0", color = Muted, fontSize = 12.sp)
                }
                Spacer(Modifier.height(18.dp))
                HorizontalDivider(color = Line)
                Spacer(Modifier.height(58.dp))
                Text("这里还没有会话", color = Ink, fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))
                Text("账号与聊天功能将在后续接入。\n当前没有加载任何聊天数据。", color = Muted, fontSize = 12.sp, lineHeight = 21.sp)
                Spacer(Modifier.weight(1f))
                HorizontalDivider(color = Line)
                Spacer(Modifier.height(16.dp))
                Text(state.storageMessage, color = if (state.storageReady) Muted else Color(0xFF9E7031), fontSize = 11.sp)
                Text("仅保存非敏感服务设置", color = Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = model::openSettings, enabled = state.initialized && !closing, modifier = Modifier.fillMaxWidth()) { Text("服务设置") }
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(Line))
            Column(Modifier.weight(1f).fillMaxHeight().padding(32.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("工作空间", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text("工程骨架 · 0.1", color = Accent, fontSize = 11.sp, modifier = Modifier.background(Color(0xFFE6EEE7), RoundedCornerShape(20.dp)).padding(horizontal = 12.dp, vertical = 7.dp))
                }
                Spacer(Modifier.height(25.dp))
                ServiceStatus(state, closing, model::checkService)
                Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(82.dp).background(Color(0xFFE4EDE4), RoundedCornerShape(28.dp)), contentAlignment = Alignment.Center) {
                        Text("聊", fontSize = 30.sp, color = Accent, fontWeight = FontWeight.Light)
                    }
                    Spacer(Modifier.height(25.dp))
                    Text("对话，从这里开始", color = Ink, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(12.dp))
                    Text("先检查服务，让桌面端与后端见个面。\n登录、联系人与消息收发尚未接入。", color = Muted, textAlign = TextAlign.Center, fontSize = 13.sp, lineHeight = 23.sp)
                    Spacer(Modifier.height(25.dp))
                    Text("未登录 · IM 未连接", color = Muted, fontSize = 12.sp)
                }
                Row(Modifier.fillMaxWidth().border(1.dp, Line, RoundedCornerShape(12.dp)).padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (closing) "正在关闭网络与本地存储…" else "消息输入区将在聊天功能接入后开放", color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Text("发送", color = Color(0xFFABB4AD), fontSize = 12.sp)
                }
            }
        }
        if (state.settingsOpen) SettingsDialog(state, model)
    }
}

@Composable
private fun ServiceStatus(state: DesktopUiState, closing: Boolean, onCheck: () -> Unit) {
    val dot = when (state.probeStatus) {
        ProbeStatus.REACHABLE -> Accent
        ProbeStatus.FAILED, ProbeStatus.UNHEALTHY -> Color(0xFFB27136)
        else -> Color(0xFF9BA79F)
    }
    Row(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(12.dp)).border(1.dp, Line, RoundedCornerShape(12.dp)).padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(dot, CircleShape))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(state.probeMessage, color = Ink, fontSize = 12.sp)
            Text(state.service?.let { "${it.system.name} ${it.system.version} · ${it.system.stage}" } ?: state.settings.apiBaseUrl, color = Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 5.dp))
            if (state.probeStatus == ProbeStatus.REACHABLE) Text("服务检查不代表已登录或已连接聊天", color = Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
        }
        TextButton(onClick = onCheck, enabled = state.initialized && !state.settingsSaving && state.probeStatus != ProbeStatus.CHECKING && !closing) {
            Text(if (state.probeStatus == ProbeStatus.CHECKING) "检查中…" else "检查服务", fontSize = 12.sp)
        }
    }
}

@Composable
private fun SettingsDialog(state: DesktopUiState, model: DesktopScreenModel) {
    var api by remember { mutableStateOf(state.settings.apiBaseUrl) }
    var im by remember { mutableStateOf(state.settings.imUrl) }
    AlertDialog(
        onDismissRequest = model::closeSettings,
        title = { Text("服务设置") },
        text = {
            Column(Modifier.width(430.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("默认地址用于本机开发。远程部署使用 HTTPS / WSS。", fontSize = 12.sp, color = Muted)
                OutlinedTextField(api, { api = it }, label = { Text("HTTP 服务地址") }, singleLine = true, enabled = !state.settingsSaving, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(im, { im = it }, label = { Text("IM WebSocket 地址") }, singleLine = true, enabled = !state.settingsSaving, modifier = Modifier.fillMaxWidth())
                Text("当前仅检查 HTTP 信息与健康状态；IM 地址预留给后续登录与连接功能。", fontSize = 12.sp, color = Muted)
                state.settingsError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            }
        },
        confirmButton = { Button(onClick = { model.saveSettings(api, im) }, enabled = !state.settingsSaving) { Text(if (state.settingsSaving) "保存中…" else "保存") } },
        dismissButton = { TextButton(onClick = model::closeSettings, enabled = !state.settingsSaving) { Text("取消") } },
    )
}
