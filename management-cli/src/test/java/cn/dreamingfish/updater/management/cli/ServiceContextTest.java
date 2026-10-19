package cn.dreamingfish.updater.management.cli;

import cn.dreamingfish.updater.management.ManagementException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.StringReader;
import java.nio.file.*;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ServiceContextTest {
    @TempDir Path temporary;
    @Test void requestsReuseOneInitializedContextAndRootSwitchRejectsLiveScopes()throws Exception {
        var root=new ManagementCli(temporary.resolve("home/management-settings.json"),new StringReader(""));
        var first=root.services();for(int i=0;i<100;i++)assertSame(first,root.services());assertEquals(1,root.serviceInitializations());
        Path next=Files.createDirectories(temporary.resolve("other-data"));
        try(var scope=root.requestScope()){
            assertThrows(ManagementException.class,()->root.saveSettings(root.settings().withDataDirectory(next.toString())));assertSame(first,root.services());
        }
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);String id=UUID.randomUUID().toString();
        try {
            first.tasks().submit(id,"demo","test-hold",null,id,false,()->{entered.countDown();release.await();return 1;});assertTrue(entered.await(5,TimeUnit.SECONDS));
            assertThrows(ManagementException.class,()->root.saveSettings(root.settings().withDataDirectory(next.toString())));assertEquals(first.paths().root(),root.services().paths().root());
        }finally{release.countDown();first.tasks().await(id);}
        root.saveSettings(root.settings().withDataDirectory(next.toString()));assertEquals(next,root.services().paths().root());assertEquals(2,root.serviceInitializations());assertTrue(Files.isRegularFile(first.paths().database()));root.closeServices();
    }
}
