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
import dev.koko.chat.desktop.group.GroupUiState
import dev.koko.chat.desktop.network.GroupMember
import dev.koko.chat.desktop.presentation.DesktopScreenModel
import dev.koko.chat.desktop.session.SessionUiState

/** 群管理在当前工作区展开；离开和移除前展示明确目标，后台始终重新校验群主与成员周期。 */
@Composable
internal fun GroupPane(session: SessionUiState, state: GroupUiState, contacts: ContactUiState,
                       closing: Boolean, model: DesktopScreenModel) {
    val creating=state.dialog=="CREATE"
    var title by remember(creating) { mutableStateOf("") }
    var selected by remember(creating,state.detail?.id) { mutableStateOf(setOf<String>()) }
    var inviting by remember(state.detail?.id) { mutableStateOf(false) }
    var removing by remember(state.detail?.id) { mutableStateOf<GroupMember?>(null) }
    var leaving by remember(state.detail?.id) { mutableStateOf(false) }
    val owner=state.detail?.ownerId==session.user?.id
    val available=contacts.friends.filter { friend -> creating || state.detail?.members?.none { it.userId==friend.id }==true }
    val selectedIds=selected.intersect(available.map { it.id }.toSet()).toList()
    val enabled=!closing && !state.busy && state.pending==null
    Column(Modifier.fillMaxSize().padding(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            Text(if(creating) "创建群聊" else state.detail?.title ?: "群管理",fontSize=23.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f))
            TextButton(model::closeGroupDialog,enabled=!state.busy) { Text("返回聊天") }
        }
        if(state.notice.isNotBlank()) Text(state.notice,fontSize=12.sp,color=Color(0xFF77817D))
        if(state.pending!=null && !state.busy) Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Button(model::retryGroupOperation) { Text("重试上次操作") }
            TextButton(model::dismissGroupOperation) { Text("停止重试并刷新") }
        }
        if(creating) OutlinedTextField(title,{title=it},label={Text("群名称")},singleLine=true,
            enabled=enabled,modifier=Modifier.fillMaxWidth(),isError=title.length>128)
        else if(state.detail!=null) Row(verticalAlignment=Alignment.CenterVertically) {
            FilterChip(!inviting,{inviting=false},label={Text("群成员 ${state.detail.members.size}/200")})
            if(owner) FilterChip(inviting,{inviting=true},label={Text("邀请好友")},modifier=Modifier.padding(start=8.dp))
            Spacer(Modifier.weight(1f));TextButton(model::refreshGroup,enabled=!state.busy) { Text("刷新") }
        }
        HorizontalDivider()
        if(creating || inviting) {
            Text("已选择 ${selectedIds.size} 位好友",fontSize=12.sp,color=Color(0xFF77817D))
            if(available.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(),contentAlignment=Alignment.Center) {
                Text(if(contacts.loading) "正在同步好友…" else "没有可邀请的好友，可先到联系人中添加",color=Color(0xFF77817D))
            } else LazyColumn(Modifier.weight(1f).fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                items(available,key={it.id}) { friend ->
                    Row(Modifier.fillMaxWidth().background(Color.White,RoundedCornerShape(12.dp)).padding(12.dp),verticalAlignment=Alignment.CenterVertically) {
                        Checkbox(friend.id in selectedIds,{ checked -> selected=if(checked) selected+friend.id else selected-friend.id },
                            enabled=enabled && (friend.id in selectedIds || selectedIds.size<199))
                        Column { Text(friend.nickname,fontWeight=FontWeight.SemiBold);Text("@${friend.account}",fontSize=12.sp,color=Color(0xFF77817D)) }
                    }
                }
            }
            Button(onClick={if(creating) model.createGroup(title,selectedIds) else {model.inviteGroup(selectedIds);selected=emptySet()}},
                enabled=enabled && if(creating) title.isNotBlank() && title.length<=128 else selectedIds.isNotEmpty()) {
                Text(if(creating) "创建群聊" else "邀请入群")
            }
        } else {
            val detail=state.detail
            if(detail==null) Box(Modifier.weight(1f).fillMaxWidth(),contentAlignment=Alignment.Center) { Text("正在确认群成员信息，请稍后刷新") }
            else LazyColumn(Modifier.weight(1f).fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                items(detail.members,key={it.userId}) { member ->
                    Row(Modifier.fillMaxWidth().background(Color.White,RoundedCornerShape(12.dp)).padding(14.dp),verticalAlignment=Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(member.nickname,fontWeight=FontWeight.SemiBold)
                            Text("@${member.account}${if(member.role=="OWNER") " · 群主" else ""}",fontSize=12.sp,color=Color(0xFF77817D))
                        }
                        if(owner && member.userId!=session.user?.id) TextButton({removing=member;leaving=false},enabled=enabled) { Text("移除") }
                    }
                }
            }
            if(removing!=null || leaving) {
                val target=removing
                Text(if(target!=null) "确定将 ${target.nickname} 移出群聊？" else if(owner) "解散后所有成员将无法继续在本群收发消息，确定解散？" else "确定退出群聊？重新加入后将从新的消息开始同步。",fontSize=13.sp)
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Button({if(target!=null) model.removeGroupMember(target) else model.leaveGroup(owner);removing=null;leaving=false},enabled=enabled) { Text("确定") }
                    TextButton({removing=null;leaving=false},enabled=enabled) { Text("取消") }
                }
            } else if(detail!=null) TextButton({leaving=true;removing=null},enabled=enabled) { Text(if(owner) "解散群聊" else "退出群聊") }
        }
    }
}
