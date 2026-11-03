package cn.dreamingfish.updater.engine;

import cn.dreamingfish.updater.protocol.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ConfigPatchEngineTest {
    @TempDir Path temporary;
    @Test void configurationIsMergedBackedUpVerifiedAndRetainedWhenRuleStops() throws Exception {
        try(var server=new TestUpdateServer()) {
            Path instance=Files.createDirectories(temporary.resolve("instance"));
            server.bundle(instance,server.release(1,"baseline"),false);
            String original="{\"accounts\":[{\"accessToken\":\"local-only\"}],\"memory\":6144,\"theme\":\"old\"}";
            Files.writeString(instance.resolve("hmcl.json"),original);
            var rule=server.file("hmcl.json.dfs-patch.json","{\"schemaVersion\":1,\"format\":\"json\",\"set\":{\"theme\":\"blue\"}}",FilePolicy.ENFORCED);
            var base=server.release(2,"patch",rule);
            var release=new ReleaseManifest(1,"demo",base.releaseId(),2,base.createdAt(),"1.1","0.3.3","patch",Set.of(ProtocolConstants.CAPABILITY_CONFIG_PATCH),Branding.empty(),base.files());
            server.serve(release);
            var request=UpdateRequest.defaults(instance,instance.resolve("DreamingFishUpdater"),server.binding(),"0.3.3",ProtocolConstants.RELEASE_CAPABILITIES);
            var result=new UpdateEngine().update(request,null);
            assertEquals(UpdateOutcome.UPDATED,result.outcome());
            var merged=new JsonCodec().read(Files.readAllBytes(instance.resolve("hmcl.json")),com.fasterxml.jackson.databind.JsonNode.class);
            assertEquals("blue",merged.path("theme").asText()); assertEquals(6144,merged.path("memory").asInt());
            assertEquals("local-only",merged.path("accounts").get(0).path("accessToken").asText());
            assertFalse(Files.exists(instance.resolve(rule.path())));
            assertEquals(UpdateOutcome.UP_TO_DATE,new UpdateEngine().update(request,null).outcome());
            var archives=new ArchiveCatalog(instance,request.playerHome()).list();
            assertTrue(archives.stream().flatMap(a->a.files().stream()).anyMatch(f->f.originalPath().equals(rule.path())));
            server.serve(server.release(3,"no-patch"));
            new UpdateEngine().update(request,null);
            assertEquals("blue",new JsonCodec().read(Files.readAllBytes(instance.resolve("hmcl.json")),com.fasterxml.jackson.databind.JsonNode.class).path("theme").asText());
        }
    }
}
