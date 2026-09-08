package dev.koko.chat.attachment;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import java.net.URI;
import java.time.Duration;

/** RustFS 使用 AWS S3 v2 签名与 path-style；对象保持私有，通过业务接口读取。 */
@Component @Profile("local")
public class RustFsStorage implements AutoCloseable {
    private final S3Client client;private final String bucket;private volatile boolean ready;
    public RustFsStorage(@Value("${RUSTFS_ENDPOINT:http://127.0.0.1:9000}") String endpoint,
                         @Value("${RUSTFS_ACCESS_KEY}") String access,@Value("${RUSTFS_SECRET_KEY}") String secret,
                         @Value("${RUSTFS_BUCKET:koko-chat}") String bucket) {
        this.client=client(endpoint,access,secret);this.bucket=bucket;
    }
    public static S3Client client(String endpoint,String access,String secret) {
        return S3Client.builder().endpointOverride(URI.create(endpoint)).region(Region.US_EAST_1).forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(access,secret)))
                .httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(3)).socketTimeout(Duration.ofSeconds(20)))
                .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(30)).apiCallAttemptTimeout(Duration.ofSeconds(20))).build();
    }
    private synchronized void ensureBucket() {
        if(ready) return;
        try { client.headBucket(b -> b.bucket(bucket)); }
        catch(S3Exception e) {
            if(e.statusCode()!=404) throw e;
            try { client.createBucket(b -> b.bucket(bucket)); }
            catch(BucketAlreadyOwnedByYouException exists) { /* 多进程首次启动竞争创建同一个私有桶。 */ }
        }
        ready=true;
    }
    public void put(String key,byte[] bytes,String type) {
        ensureBucket();client.putObject(b -> b.bucket(bucket).key(key).contentType(type),RequestBody.fromBytes(bytes));
    }
    public ResponseInputStream<GetObjectResponse> get(String key) { ensureBucket();return client.getObject(b -> b.bucket(bucket).key(key)); }
    public void delete(String key) { client.deleteObject(b -> b.bucket(bucket).key(key)); }
    /** 只检查服务可达和桶访问；尚未创建桶的 404 不视为存储宕机，不创建测试对象。 */
    public boolean healthy() {
        try { client.headBucket(b -> b.bucket(bucket).overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(3)).apiCallAttemptTimeout(Duration.ofSeconds(2))));return true; }
        catch(S3Exception unavailable) { return unavailable.statusCode()==404; }
    }
    @Override public void close() { client.close(); }
}
