package dev.koko.chat.desktop.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.input.PasswordVisualTransformation
import dev.koko.chat.desktop.session.SessionState
import dev.koko.chat.desktop.session.SessionUiState
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

/** 订阅页面状态并展示真实空态；设置保存与服务检查均由 ScreenModel 执行。 */
@Composable
fun DesktopApp(model: DesktopScreenModel, closing: Boolean, windowFocused: Boolean = false) {
    val state by model.state.collectAsState()
    val session by model.sessionState.collectAsState()
    val chat by model.chatState.collectAsState()
    val contacts by model.contactState.collectAsState()
    val groups by model.groupState.collectAsState()
    MaterialTheme(colorScheme = lightColorScheme(primary = Accent, background = Canvas, surface = Color.White)) {
        if (session.user != null && session.phase != SessionState.SIGNING_OUT) {
            ChatWorkspace(session, chat, closing, model, contacts, groups, windowFocused, state.settingsOpen)
        } else BoxWithConstraints(Modifier.fillMaxSize()) {
            val compact = maxHeight < 700.dp
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
                    Text("登录后输入对方账号，\n开始单聊并同步历史消息。", color = Muted, fontSize = 12.sp, lineHeight = 21.sp)
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
                        Text("单聊 · 0.1", color = Accent, fontSize = 11.sp, modifier = Modifier.background(Color(0xFFE6EEE7), RoundedCornerShape(20.dp)).padding(horizontal = 12.dp, vertical = 7.dp))
                    }
                    Spacer(Modifier.height(25.dp))
                    ServiceStatus(state, closing, model::checkService)
                    Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                        AccountPanel(session, state.initialized && !state.settingsSaving && !closing, compact, model)
                    }
                    if (!compact) Row(Modifier.fillMaxWidth().border(1.dp, Line, RoundedCornerShape(12.dp)).padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (closing) "正在关闭网络与本地存储…" else "登录后即可发起单聊", color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        Text("发送", color = Color(0xFFABB4AD), fontSize = 12.sp)
                    }
                }
            }
        }
        if (state.settingsOpen) SettingsDialog(state, model)
    }
}

/** 表单只持有本次输入；提交后立即清空密码，登录与连接状态完全由 SessionManager 驱动。 */
@Composable
private fun AccountPanel(session: SessionUiState, enabled: Boolean, compact: Boolean, model: DesktopScreenModel) {
    var registering by remember { mutableStateOf(false) }
    var account by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var nickname by remember { mutableStateOf("") }
    Column(Modifier.widthIn(max = 400.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp)) {
        if (session.user == null && session.phase != SessionState.SIGNING_OUT) {
            Text(if (registering) "创建你的账号" else "欢迎回来", color = Ink, fontSize = 25.sp, fontWeight = FontWeight.SemiBold)
            Text(if (registering) "注册成功后自动登录并连接聊天服务。" else "登录 koko-chat，连接你的桌面对话。", color = Muted, fontSize = 12.sp)
            OutlinedTextField(account, { account = it }, label = { Text("账号（字母、数字或下划线）") }, singleLine = true,
                enabled = enabled && !session.busy, modifier = Modifier.fillMaxWidth())
            if (registering) OutlinedTextField(nickname, { nickname = it }, label = { Text("昵称") }, singleLine = true,
                enabled = enabled && !session.busy, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(password, { password = it }, label = { Text("密码（8–128 字符）") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), enabled = enabled && !session.busy, modifier = Modifier.fillMaxWidth())
            Text(session.message, color = Muted, fontSize = 12.sp, lineHeight = 19.sp)
            Button(onClick = {
                val submitted = password
                password = ""
                model.signIn(account.trim(), submitted, nickname, registering)
            }, enabled = enabled && !session.busy && account.isNotBlank() && password.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                Text(if (session.busy) "正在处理…" else if (registering) "注册并登录" else "登录")
            }
            TextButton(onClick = { registering = !registering; password = "" }, enabled = enabled && !session.busy, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(if (registering) "已有账号？去登录" else "还没有账号？创建账号")
            }
        } else {
            Text(session.user?.nickname ?: "正在退出", color = Ink, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
            session.user?.let { Text("@${it.account}", color = Muted, fontSize = 13.sp) }
            if (session.phase == SessionState.CONNECTING || session.phase == SessionState.RECONNECTING) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(session.message, color = if (session.phase == SessionState.ONLINE) Accent else Muted, fontSize = 14.sp, lineHeight = 22.sp)
            Text("联系人和消息功能正在准备中。", color = Muted, fontSize = 12.sp)
            OutlinedButton(onClick = model::logout, enabled = enabled && !session.busy) { Text("退出登录") }
        }
    }
}

/** 按探针状态渲染连接提示，正在检查或退出时禁用按钮。 */
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

/** 输入框只保留编辑草稿，用户确认后再交给模型校验及持久化。 */
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
                Text("保存服务地址会先退出当前账号并关闭聊天连接。", fontSize = 12.sp, color = Muted)
                state.settingsError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            }
        },
        confirmButton = { Button(onClick = { model.saveSettings(api, im) }, enabled = !state.settingsSaving) { Text(if (state.settingsSaving) "保存中…" else "保存") } },
        dismissButton = { TextButton(onClick = model::closeSettings, enabled = !state.settingsSaving) { Text("取消") } },
    )
}
