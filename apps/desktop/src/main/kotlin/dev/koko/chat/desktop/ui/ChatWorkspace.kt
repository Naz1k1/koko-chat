package dev.koko.chat.desktop.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.koko.chat.desktop.chat.ChatUiState
import dev.koko.chat.desktop.contact.ContactUiState
import dev.koko.chat.desktop.presentation.DesktopScreenModel
import dev.koko.chat.desktop.session.SessionUiState

/** 真实单聊工作区：已保存消息和待发送消息分开展示，不将 MQ 发布成功显示成对方已读。 */
@Composable
internal fun ChatWorkspace(session:SessionUiState,state:ChatUiState,closing:Boolean,model:DesktopScreenModel,
                           contacts:ContactUiState = ContactUiState(loading = false)) {
    var showContacts by remember(session.user?.id) { mutableStateOf(false) }
    var peer by remember { mutableStateOf("") }
    var draft by remember(state.selectedId) { mutableStateOf("") }
    val selected=state.conversations.find { it.id==state.selectedId }
    val list=rememberLazyListState()
    val total=state.messages.size+state.pending.size
    LaunchedEffect(state.selectedId,total) { if(total>0) list.animateScrollToItem(total-1) }
    Row(Modifier.fillMaxSize().background(Color(0xFFF6F8F6))) {
        Column(Modifier.width(296.dp).fillMaxHeight().background(Color.White).padding(22.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("koko-chat",fontSize=24.sp,fontWeight=FontWeight.Bold,color=Color(0xFF213D38))
            Text("${session.user?.nickname} · @${session.user?.account}",fontSize=12.sp)
            val unreadRequests = contacts.requests.count { it.receiverId == session.user?.id && it.status == "PENDING" }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!showContacts, { showContacts = false }, label = { Text("会话") })
                FilterChip(showContacts, { showContacts = true }, label = { Text(if (unreadRequests > 0) "联系人 · $unreadRequests" else "联系人") })
            }
            OutlinedTextField(peer,{peer=it},label={Text("对方的准确账号")},singleLine=true,modifier=Modifier.fillMaxWidth())
            Button({showContacts=false;model.createConversation(peer.trim())},enabled=!closing && !state.creating && peer.isNotBlank(),modifier=Modifier.fillMaxWidth()) { Text(if(state.creating) "正在创建…" else "发起单聊") }
            HorizontalDivider()
            Text("会话 · ${state.conversations.size}",fontSize=12.sp,color=Color(0xFF77817D))
            LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                items(state.conversations,key={it.id}) { conversation ->
                    Column(Modifier.fillMaxWidth().background(if(conversation.id==selected?.id) Color(0xFFE2EFE8) else Color.Transparent,RoundedCornerShape(12.dp))
                        .clickable { showContacts = false; model.selectConversation(conversation.id) }.padding(14.dp)) {
                        Text(conversation.nickname,fontWeight=FontWeight.SemiBold)
                        Text("@${conversation.account}",fontSize=11.sp,color=Color(0xFF77817D))
                    }
                }
            }
            Text(session.message,fontSize=11.sp,color=Color(0xFF167565))
            Row { TextButton(model::openSettings,enabled=!closing) { Text("服务设置") };TextButton(model::logout,enabled=!closing && !session.busy) { Text("退出登录") } }
        }
        VerticalDivider()
        if (showContacts) Box(Modifier.weight(1f).fillMaxHeight()) {
            ContactsPane(session, contacts, closing, model) { account ->
                model.createConversation(account)
                showContacts = false
            }
        } else Column(Modifier.weight(1f).fillMaxHeight().padding(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(selected?.nickname ?: "开始一段对话",fontSize=23.sp,fontWeight=FontWeight.SemiBold,color=Color(0xFF213D38))
            Text(state.notice,fontSize=11.sp,color=Color(0xFF77817D))
            HorizontalDivider()
            if(selected==null) {
                Box(Modifier.weight(1f).fillMaxWidth(),contentAlignment=Alignment.Center) { Text("输入对方账号，创建你的第一个单聊",color=Color(0xFF77817D)) }
            } else LazyColumn(Modifier.weight(1f).fillMaxWidth(),state=list,verticalArrangement=Arrangement.spacedBy(12.dp)) {
                items(state.messages,key={"message-${it.id}"}) { message ->
                    val mine=message.senderId==session.user?.id
                    MessageBubble(message.text,if(mine) "已保存到服务器" else selected.nickname,mine)
                }
                items(state.pending,key={"pending-${it.clientMsgId}"}) { pending ->
                    Column(Modifier.fillMaxWidth(),horizontalAlignment=Alignment.End) {
                        MessageBubble(pending.text,if(pending.status=="FAILED") pending.error ?: "发送失败" else "待确认 · 自动重试中",true)
                        if(pending.status=="FAILED") TextButton({model.retryMessage(pending.clientMsgId)}) { Text("重试") }
                    }
                }
            }
            Row(verticalAlignment=Alignment.Bottom,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(draft,{draft=it},placeholder={Text("输入消息…")},enabled=selected!=null && !closing,
                    modifier=Modifier.weight(1f).heightIn(min=88.dp,max=144.dp),maxLines=5)
                Button(onClick={val text=draft;model.sendMessage(text) { if(draft==text) draft="" }},enabled=selected!=null && draft.isNotBlank() && !closing && !state.creating) { Text("发送") }
            }
            Text("断线时消息会保存在本机，连接恢复后自动发送。",fontSize=10.sp,color=Color(0xFF77817D))
        }
    }
}

@Composable
private fun MessageBubble(text:String,status:String,mine:Boolean) {
    Column(Modifier.fillMaxWidth(),horizontalAlignment=if(mine) Alignment.End else Alignment.Start) {
        Text(text,modifier=Modifier.widthIn(max=440.dp).background(if(mine) Color(0xFFDCEEE2) else Color.White,RoundedCornerShape(14.dp)).padding(14.dp),fontSize=14.sp)
        Text(status,fontSize=10.sp,color=Color(0xFF77817D),modifier=Modifier.padding(top=4.dp))
    }
}
