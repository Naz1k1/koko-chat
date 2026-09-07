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
import androidx.compose.ui.graphics.toComposeImageBitmap
import dev.koko.chat.desktop.platform.FileDialogs
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import dev.koko.chat.desktop.chat.ChatUiState
import dev.koko.chat.desktop.contact.ContactUiState
import dev.koko.chat.desktop.group.GroupUiState
import dev.koko.chat.desktop.presentation.DesktopScreenModel
import dev.koko.chat.desktop.session.SessionUiState

/** 真实单聊工作区：已保存消息和待发送消息分开展示，不将 MQ 发布成功显示成对方已读。 */
@Composable
internal fun ChatWorkspace(session:SessionUiState,state:ChatUiState,closing:Boolean,model:DesktopScreenModel,
                           contacts:ContactUiState = ContactUiState(loading = false),
                           groups:GroupUiState = GroupUiState(),windowFocused:Boolean=false,settingsOpen:Boolean=false,onReadVisible:(String,String,Long)->Unit=model::readVisible,
                           onLoadOlder:()->Unit=model::loadOlderMessages,onShowLatest:()->Unit=model::showLatestMessages,
                           messageListState:LazyListState=rememberLazyListState()) {
    var showContacts by remember(session.user?.id) { mutableStateOf(false) }
    var peer by remember { mutableStateOf("") }
    var draft by remember(state.selectedId) { mutableStateOf("") }
    val selected=state.conversations.find { it.id==state.selectedId }
    val list=messageListState
    val total=state.messages.size+state.pending.size
    var positioned by remember(state.selectedId,selected?.membershipEpoch) { mutableStateOf(false) }
    var appliedRevision by remember(state.selectedId,selected?.membershipEpoch) { mutableStateOf(state.latestRevision) }
    // 仅初次打开或显式跳回最新时定位；向前插入由 LazyColumn 的消息稳定 key 保持像素位置。
    LaunchedEffect(state.selectedId,selected?.membershipEpoch,total,state.latestRevision) {
        if(total>0 && (!positioned || appliedRevision!=state.latestRevision)) {
            list.scrollToItem(total-1);positioned=true;appliedRevision=state.latestRevision
        }
    }
    val currentMessages by rememberUpdatedState(state.messages)
    val reportRead by rememberUpdatedState(onReadVisible)
    val reading=windowFocused && !closing && !settingsOpen && !showContacts && groups.dialog==null && state.preview==null
    LaunchedEffect(reading,selected?.id,selected?.membershipEpoch,state.messages) {
        if(reading && selected!=null) snapshotFlow {
            if(list.isScrollInProgress) null else list.layoutInfo.visibleItemsInfo
                .filter { it.offset+it.size>list.layoutInfo.viewportStartOffset && it.offset+it.size<=list.layoutInfo.viewportEndOffset }
                .mapNotNull { item -> currentMessages.find { "message-${it.id}"==item.key }?.seq?.toLong() }.maxOrNull()
        }.collectLatest { seq ->
            // 短暂经过或滚动中的消息不触发 READ；切换窗口、页面会取消这次等待。
            if(seq!=null) { delay(500);reportRead(selected.id,selected.membershipEpoch,seq) }
        }
    }
    Row(Modifier.fillMaxSize().background(Color(0xFFF6F8F6))) {
        Column(Modifier.width(296.dp).fillMaxHeight().background(Color.White).padding(22.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("koko-chat",fontSize=24.sp,fontWeight=FontWeight.Bold,color=Color(0xFF213D38))
            Text("${session.user?.nickname} · @${session.user?.account}",fontSize=12.sp)
            val unreadRequests = contacts.requests.count { it.receiverId == session.user?.id && it.status == "PENDING" }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!showContacts, { showContacts = false; model.closeGroupDialog() }, label = { Text("会话") })
                FilterChip(showContacts, { showContacts = true; model.closeGroupDialog() }, label = { Text(if (unreadRequests > 0) "联系人 · $unreadRequests" else "联系人") })
            }
            OutlinedTextField(peer,{peer=it},label={Text("对方的准确账号")},singleLine=true,modifier=Modifier.fillMaxWidth())
            Button({showContacts=false;model.closeGroupDialog();model.createConversation(peer.trim())},enabled=!closing && !state.creating && peer.isNotBlank(),modifier=Modifier.fillMaxWidth()) { Text(if(state.creating) "正在创建…" else "发起单聊") }
            TextButton({showContacts=false;model.openCreateGroup()},enabled=!closing,modifier=Modifier.fillMaxWidth()) { Text("创建群聊") }
            HorizontalDivider()
            Text("会话 · ${state.conversations.size}",fontSize=12.sp,color=Color(0xFF77817D))
            LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                items(state.conversations,key={it.id}) { conversation ->
                    Column(Modifier.fillMaxWidth().background(if(conversation.id==selected?.id) Color(0xFFE2EFE8) else Color.Transparent,RoundedCornerShape(12.dp))
                        .clickable { showContacts = false; model.closeGroupDialog(); model.selectConversation(conversation.id) }.padding(14.dp)) {
                        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            Text(conversation.nickname,fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f),maxLines=1)
                            if(conversation.unreadCount.toLong()>0) Badge {
                                Text(if(conversation.unreadCount.toLong()>99) "99+" else conversation.unreadCount)
                            }
                        }
                        Text(if(conversation.type=="GROUP") "群聊" else "@${conversation.account}",fontSize=11.sp,color=Color(0xFF77817D))
                    }
                }
            }
            Text(session.message,fontSize=11.sp,color=Color(0xFF167565))
            Row { TextButton(model::openSettings,enabled=!closing) { Text("服务设置") };TextButton(model::logout,enabled=!closing && !session.busy) { Text("退出登录") } }
        }
        VerticalDivider()
        if(groups.dialog!=null) Box(Modifier.weight(1f).fillMaxHeight()) {
            GroupPane(session,groups,contacts,closing,model)
        } else if (showContacts) Box(Modifier.weight(1f).fillMaxHeight()) {
            ContactsPane(session, contacts, closing, model) { account ->
                model.createConversation(account)
                showContacts = false
            }
        } else Column(Modifier.weight(1f).fillMaxHeight().padding(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Text(selected?.nickname ?: "开始一段对话",fontSize=23.sp,fontWeight=FontWeight.SemiBold,color=Color(0xFF213D38),modifier=Modifier.weight(1f))
                if(selected?.type=="GROUP") TextButton(model::openGroupManagement,enabled=!closing) { Text("群成员") }
            }
            Text(state.notice,fontSize=11.sp,color=Color(0xFF77817D))
            HorizontalDivider()
            if(selected!=null) Box(Modifier.fillMaxWidth().height(36.dp),contentAlignment=Alignment.Center) {
                if(state.hasOlderMessages || state.historyError!=null || state.loadingOlder) TextButton(
                    onClick=onLoadOlder,enabled=!closing && !state.loadingOlder) {
                    Text(when { state.loadingOlder -> "正在加载…";state.historyError!=null -> "加载失败，点击重试";else -> "加载更早消息" },fontSize=12.sp)
                } else Text(when {
                    state.messages.isEmpty() && selected.latestSeq.toLong()<selected.visibleFromSeq.toLong() -> "还没有消息"
                    state.messages.isEmpty() || state.messages.first().seq.toLong()>selected.visibleFromSeq.toLong() -> "更早消息同步中…"
                    else -> "已到当前可见历史起点"
                },fontSize=11.sp,color=Color(0xFF77817D))
            }
            if(selected==null) {
                Box(Modifier.weight(1f).fillMaxWidth(),contentAlignment=Alignment.Center) { Text("输入对方账号，创建你的第一个单聊",color=Color(0xFF77817D)) }
            } else LazyColumn(Modifier.weight(1f).fillMaxWidth(),state=list,verticalArrangement=Arrangement.spacedBy(12.dp)) {
                items(state.messages,key={"message-${it.id}"}) { message ->
                    val mine=message.senderId==session.user?.id
                    val sender=if(selected.type=="GROUP") groups.detail?.takeIf { it.id==selected.id }?.members?.find { it.userId==message.senderId }?.nickname ?: "成员 ${message.senderId}" else selected.nickname
                    val status=if(!mine) sender else if(selected.type=="DIRECT" && message.seq.toLong()<=(selected.peerLastReadSeq?.toLong()?:0L)) "对方已读" else "已保存到服务器"
                    MessageBubble(message.text,status,mine)
                    message.attachment?.let { attachment ->
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=if(mine) Arrangement.End else Arrangement.Start) {
                            Text("%.1f KiB".format(attachment.size/1024.0),fontSize=11.sp,modifier=Modifier.align(Alignment.CenterVertically))
                            if(attachment.kind=="IMAGE") TextButton({model.openAttachment(message)},enabled=!closing && !state.attachmentBusy) { Text("查看图片") }
                            TextButton({FileDialogs.save(attachment.name)?.let { model.openAttachment(message,it) }},enabled=!closing && !state.attachmentBusy) { Text("保存文件") }
                        }
                    }
                }
                items(state.pending,key={"pending-${it.clientMsgId}"}) { pending ->
                    Column(Modifier.fillMaxWidth(),horizontalAlignment=Alignment.End) {
                        MessageBubble(pending.text,if(pending.status=="FAILED") pending.error ?: "发送失败" else "待确认 · 自动重试中",true)
                        if(pending.status=="FAILED") TextButton({model.retryMessage(pending.clientMsgId)}) { Text("重试") }
                    }
                }
            }
            if(selected!=null && list.canScrollForward) TextButton(onShowLatest,enabled=!closing && !state.loadingOlder,modifier=Modifier.align(Alignment.End)) { Text("查看最新消息") }
            Row(verticalAlignment=Alignment.Bottom,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(draft,{draft=it},placeholder={Text("输入消息…")},enabled=selected!=null && !closing,
                    modifier=Modifier.weight(1f).heightIn(min=88.dp,max=144.dp),maxLines=5)
                Button(onClick={val text=draft;model.sendMessage(text) { if(draft==text) draft="" }},enabled=selected!=null && draft.isNotBlank() && !closing && !state.creating) { Text("发送") }
            }
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                TextButton({FileDialogs.choose(true)?.let { model.sendFile(it,"IMAGE") }},enabled=selected!=null && !closing && !state.attachmentBusy) { Text("图片") }
                TextButton({FileDialogs.choose(false)?.let { model.sendFile(it,"FILE") }},enabled=selected!=null && !closing && !state.attachmentBusy) { Text("文件") }
                Text(if(state.attachmentBusy) "正在处理附件…" else "最多 10 MiB · 断线后自动重试",fontSize=10.sp,color=Color(0xFF77817D))
            }
        }
    }
    state.preview?.let { preview ->
        val bitmap=remember(preview) { runCatching { org.jetbrains.skia.Image.makeFromEncoded(preview.bytes).toComposeImageBitmap() }.getOrNull() }
        AlertDialog(onDismissRequest=model::closeAttachmentPreview,title={Text(preview.attachment.name,maxLines=2)},
            text={ if(bitmap!=null) Image(bitmap,preview.attachment.name,Modifier.widthIn(max=600.dp).heightIn(max=420.dp)) else Text("图片解码失败，请保存文件后查看") },
            confirmButton={TextButton(model::closeAttachmentPreview) { Text("关闭") }})
    }
}

@Composable
private fun MessageBubble(text:String,status:String,mine:Boolean) {
    Column(Modifier.fillMaxWidth(),horizontalAlignment=if(mine) Alignment.End else Alignment.Start) {
        Text(text,modifier=Modifier.widthIn(max=440.dp).background(if(mine) Color(0xFFDCEEE2) else Color.White,RoundedCornerShape(14.dp)).padding(14.dp),fontSize=14.sp)
        Text(status,fontSize=10.sp,color=Color(0xFF77817D),modifier=Modifier.padding(top=4.dp))
    }
}
