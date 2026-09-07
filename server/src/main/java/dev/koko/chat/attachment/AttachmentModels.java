package dev.koko.chat.attachment;

/** 附件契约只返回业务编号，不向客户端暴露对象存储密钥或任意对象路径。 */
public final class AttachmentModels {
    private AttachmentModels() {}
    public record Create(String clientUploadId,String membershipEpoch,String name,long size,String sha256,String kind) {}
    public record Row(String id,long ownerId,long conversationId,String membershipEpoch,String objectKey,String name,long size,
                      String sha256,String kind,String contentType,String status,Long messageId) {}
    public record Reference(String id,String name,long size,String sha256,String kind,String contentType) {}
    public record View(Reference attachment,String status) {}
    public static Reference reference(Row row) { return new Reference(row.id(),row.name(),row.size(),row.sha256(),row.kind(),row.contentType()); }
}
