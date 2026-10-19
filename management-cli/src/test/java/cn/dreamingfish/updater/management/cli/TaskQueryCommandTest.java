package cn.dreamingfish.updater.management.cli;

import cn.dreamingfish.updater.management.*;
import cn.dreamingfish.updater.protocol.JsonCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import java.io.StringReader;
import java.nio.file.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class TaskQueryCommandTest {
    @TempDir Path temporary;

    @Test void queryingFromAnotherCliDoesNotCreateAnExecutorOrRewriteTheTask()throws Exception {
        var paths=ManagementPaths.at(temporary.resolve("data"));paths.initialize();var json=new JsonCodec();
        var reader=new ManagementCli(temporary.resolve("reader/management-settings.json"),new StringReader(""));
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);String id=java.util.UUID.randomUUID().toString();
        try(var owner=new ApplicationTasks(paths,json)){
            owner.submit(id,"demo","scan",null,"same",false,()->{entered.countDown();release.await();return "done";});
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            try {
                Path file=paths.root().resolve("application-tasks/"+id+".json");byte[] before=Files.readAllBytes(file);
                assertEquals(0,new CommandLine(reader).execute("--data",paths.root().toString(),"tasks","--id",id));
                assertEquals(ApplicationTasks.State.RUNNING,owner.require(id).state());
                assertEquals(0,reader.serviceInitializations());assertArrayEquals(before,Files.readAllBytes(file));
                assertThrows(ManagementException.class,()->reader.cancelQueuedTask(id));
                assertArrayEquals(before,Files.readAllBytes(file));
            }finally{release.countDown();}
            owner.await(id);
        }
    }
}
