package dev.koko.chat.attachment;

import java.sql.*;
import java.util.*;

/** 联调脚本专用入口：只删除随机测试账号拥有的对象，不提供 HTTP 清理接口。 */
public final class AttachmentTestCleanup {
    private AttachmentTestCleanup() {}
    public static void main(String[] args) throws Exception {
        if(args.length!=1 || !args[0].matches("dsk_[a-f0-9]{20}")) throw new IllegalArgumentException("需要脚本生成的测试账号前缀");
        Map<String,String> env=System.getenv();
        String url="jdbc:mysql://"+env.getOrDefault("MYSQL_HOST","127.0.0.1")+":"+env.getOrDefault("MYSQL_PORT","3306")+"/"+env.getOrDefault("MYSQL_DATABASE","koko_chat")+"?useSSL=false&allowPublicKeyRetrieval=true";
        try(var db=DriverManager.getConnection(url,env.getOrDefault("MYSQL_USERNAME","koko"),env.get("MYSQL_PASSWORD"));
            var s3=RustFsStorage.client(env.getOrDefault("RUSTFS_ENDPOINT","http://127.0.0.1:9000"),env.get("RUSTFS_ACCESS_KEY"),env.get("RUSTFS_SECRET_KEY"));
            var query=db.prepareStatement("SELECT a.id,a.object_key FROM attachment a JOIN app_user u ON u.id=a.owner_id WHERE u.account IN ("+String.join(",",Collections.nCopies(15,"?"))+")")) {
            for(int i=0;i<15;i++) query.setString(i+1,args[0]+(char)('a'+i));
            int count=0;
            try(var rows=query.executeQuery()) {
                while(rows.next()) {
                    String id=rows.getString(1),key=rows.getString(2);AttachmentService.validId(id);
                    if(!key.equals("attachments/"+id)) throw new IllegalStateException("测试对象路径不合法");
                    s3.deleteObject(b -> b.bucket(env.getOrDefault("RUSTFS_BUCKET","koko-chat")).key(key));
                    s3.deleteObject(b -> b.bucket(env.getOrDefault("RUSTFS_BUCKET","koko-chat")).key("thumbnails/"+id));count++;
                }
            }
            System.out.println("已清理本次联调附件对象："+count);
        }
    }
}
