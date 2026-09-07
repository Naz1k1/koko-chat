package dev.koko.chat.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.koko.chat.desktop.contact.ContactUiState
import dev.koko.chat.desktop.network.FriendRequestInfo
import dev.koko.chat.desktop.presentation.DesktopScreenModel
import dev.koko.chat.desktop.session.SessionUiState

/** 好友管理与聊天共用工作区；按钮状态来自已确认的服务端结果，申请附言按普通文本展示。 */
@Composable
internal fun ContactsPane(session: SessionUiState, state: ContactUiState, closing: Boolean,
                          model: DesktopScreenModel, onChat: (String) -> Unit) {
    var account by remember(session.user?.id) { mutableStateOf("") }
    var greeting by remember(session.user?.id) { mutableStateOf("") }
    var tab by remember { mutableStateOf(0) }
    val incoming = state.requests.filter { it.receiverId == session.user?.id }
    val outgoing = state.requests.filter { it.senderId == session.user?.id }
    val enabled = !closing && !state.busy
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("联系人", fontSize = 23.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF213D38))
                Text(state.notice, fontSize = 11.sp, color = Color(0xFF77817D))
            }
            TextButton(model::refreshContacts, enabled = enabled && !state.loading) { Text(if (state.loading) "同步中…" else "刷新") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(account, { account = it }, label = { Text("对方账号") }, singleLine = true,
                enabled = enabled, modifier = Modifier.weight(0.9f))
            OutlinedTextField(greeting, { greeting = it }, label = { Text("附言（选填）") }, singleLine = true,
                enabled = enabled, modifier = Modifier.weight(1.1f), isError = greeting.length > 255)
            Button(onClick = {
                val sentAccount = account; val sentGreeting = greeting
                model.applyFriend(sentAccount.trim(), sentGreeting.trim()) {
                    if (account == sentAccount && greeting == sentGreeting) { account = ""; greeting = "" }
                }
            }, enabled = enabled && account.isNotBlank() && greeting.length <= 255) { Text("添加好友") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("好友 ${state.friends.size}", "收到的申请 ${incoming.count { it.status == "PENDING" }}", "发出的申请").forEachIndexed { index, title ->
                FilterChip(selected = tab == index, onClick = { tab = index }, label = { Text(title) })
            }
        }
        HorizontalDivider()
        val requests = if (tab == 1) incoming else outgoing
        if ((tab == 0 && state.friends.isEmpty()) || (tab != 0 && requests.isEmpty())) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(if (state.loading) "正在加载…" else when (tab) {
                    0 -> "还没有好友，输入账号发送第一份申请"
                    1 -> "暂时没有收到好友申请"
                    else -> "你还没有发出好友申请"
                }, color = Color(0xFF77817D), fontSize = 13.sp)
            }
        } else LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (tab == 0) items(state.friends, key = { it.id }) { friend ->
                Row(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(12.dp)).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(friend.remark?.takeIf { it.isNotBlank() } ?: friend.nickname, fontWeight = FontWeight.SemiBold)
                        Text("@${friend.account}", fontSize = 12.sp, color = Color(0xFF77817D))
                    }
                    TextButton({ onChat(friend.account) }, enabled = enabled) { Text("发消息") }
                }
            } else items(requests, key = { it.id }) { request ->
                RequestCard(request, tab == 1, enabled, model)
            }
        }
    }
}

@Composable
private fun RequestCard(request: FriendRequestInfo, incoming: Boolean, enabled: Boolean, model: DesktopScreenModel) {
    Column(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(12.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(if (incoming) request.senderNickname else request.receiverNickname, fontWeight = FontWeight.SemiBold)
                Text("@${if (incoming) request.senderAccount else request.receiverAccount}", fontSize = 12.sp, color = Color(0xFF77817D))
            }
            if (incoming && request.status == "PENDING") {
                TextButton({ model.decideFriend(request.id, false) }, enabled = enabled) { Text("拒绝") }
                Button({ model.decideFriend(request.id, true) }, enabled = enabled) { Text("接受") }
            } else Text(when (request.status) {
                "ACCEPTED" -> "已成为好友"
                "REJECTED" -> if (incoming) "已拒绝" else "对方已拒绝"
                "CANCELLED" -> "已取消"
                else -> "等待对方处理"
            }, fontSize = 12.sp, color = Color(0xFF77817D))
        }
        if (!request.greeting.isNullOrBlank()) Text(request.greeting, fontSize = 13.sp)
    }
}
