package dev.koko.chat.desktop.platform

import java.awt.FileDialog
import java.awt.Frame
import java.awt.KeyboardFocusManager
import java.nio.file.Path

/** 使用系统文件选择器；仅在用户点击时打开，不执行收到的文件。 */
object FileDialogs {
    fun choose(image:Boolean):Path? = dialog(if(image) "选择 PNG / JPEG 图片" else "选择文件",FileDialog.LOAD,null,image)
    fun save(name:String):Path? = dialog("保存附件",FileDialog.SAVE,name,false)
    private fun dialog(title:String,mode:Int,name:String?,image:Boolean):Path? {
        val owner=KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow as? Frame
        val dialog=FileDialog(owner,title,mode)
        return try {
            if(name!=null) dialog.file=name
            if(image) dialog.setFilenameFilter { _,file -> file.substringAfterLast('.').lowercase() in setOf("png","jpg","jpeg") }
            dialog.isVisible=true
            dialog.file?.let { Path.of(dialog.directory,it) }
        } finally { dialog.dispose() }
    }
}
