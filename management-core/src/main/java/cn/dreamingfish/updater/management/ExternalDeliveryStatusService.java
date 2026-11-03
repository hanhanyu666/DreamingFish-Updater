package cn.dreamingfish.updater.management;

import cn.dreamingfish.updater.protocol.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.Base64;

/** Reports only the last built-in upload, never claims that every external host is current. */
public final class ExternalDeliveryStatusService {
    public record Status(Long exportedRevision, Instant exportedAt, Long uploadedRevision, Instant uploadedAt, String provider, String warning) {
        public Status { warning=warning==null?"":warning; }
        public Status(Long exportedRevision,Instant exportedAt,Long uploadedRevision,Instant uploadedAt,String provider) { this(exportedRevision,exportedAt,uploadedRevision,uploadedAt,provider,""); }
    }
    private final ManagementPaths paths; private final ManagementDatabase database; private final JsonCodec json;
    public ExternalDeliveryStatusService(ManagementPaths paths, ManagementDatabase database, JsonCodec json) {
        this.paths=paths;this.database=database;this.json=json;
    }
    private Path file(String projectId) { database.requireProject(projectId); return paths.playerPrograms().resolve(projectId).resolve("external-delivery.json"); }
    public Status read(String projectId) {
        Path file=file(projectId);
        try {
            PathSafety.assertSafePathTree(file);
            if(!Files.exists(file,LinkOption.NOFOLLOW_LINKS))return new Status(null,null,null,null,"");
            if(Files.size(file)>16*1024)throw new IOException("Delivery record is too large");
            var status=json.read(file,Status.class);
            if (status == null || status.exportedRevision()!=null && (status.exportedRevision()<0 || status.exportedAt()==null)
                    || status.uploadedRevision()!=null && status.uploadedRevision()<0
                    || status.provider()==null || !java.util.Set.of("", "s3", "webdav").contains(status.provider())
                    || !status.warning().isEmpty()) throw new IOException("Invalid delivery record");
            return status;
        }catch(IOException | ProtocolException error){return new Status(null,null,null,null,"","外部托管投递记录不可用，请保留原文件后核对；当前不能确认已上传状态");}
    }
    public Long capture(String projectId,Path output) {
        var project=database.requireProject(projectId);
        Path file=output.resolve("v1/projects/"+projectId+"/player/policy.signed");
        try {
            PathSafety.assertSafePathTree(file);
            if(!Files.exists(file,LinkOption.NOFOLLOW_LINKS))return null;
            if(Files.size(file)>64*1024)throw new IOException("Policy is too large");
            var signed=SignedDocument.decode(Files.readAllBytes(file),64*1024);
            if(!CryptoSupport.verify(signed.payload(),Base64.getDecoder().decode(signed.signature()),CryptoSupport.decodePublicKey(project.publicKey())))
                throw new ManagementException("待上传策略的项目签名无效");
            var policy=json.read(signed.payload(),PlayerUpdatePolicy.class);policy.validate(projectId);return policy.revision();
        }catch(IOException error){throw new ManagementException("无法核对待上传的程序更新策略",error);}
    }
    public void exported(String projectId,long revision) { save(projectId,revision,null,null); }
    public void uploaded(String projectId,Long revision,String provider) { save(projectId,null,revision,provider); }
    private void save(String projectId,Long exported,Long uploaded,String provider) {
        database.requireProject(projectId);
        if(exported!=null && exported<0 || uploaded!=null && uploaded<0
                || provider!=null && !java.util.Set.of("s3","webdav").contains(provider))
            throw new ManagementException("投递状态参数无效");
        try(var lock=ProjectLock.acquire(paths.locks().resolve(projectId+".lock"))) {
            var old=read(projectId);
            if(!old.warning().isEmpty())throw new ManagementException(old.warning());
            var next=new Status(exported==null?old.exportedRevision():exported,exported==null?old.exportedAt():Instant.now(),
                    provider==null?old.uploadedRevision():uploaded,provider==null?old.uploadedAt():Instant.now(),provider==null?old.provider():provider);
            AtomicFiles.write(file(projectId),json.writePretty(next));
        }catch(IOException error){throw new ManagementException("投递已完成，但本机状态记录写入失败，请核对原托管位置",error);}
    }
}
