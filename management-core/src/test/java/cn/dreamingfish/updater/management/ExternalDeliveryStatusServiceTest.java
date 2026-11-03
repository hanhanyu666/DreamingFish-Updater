package cn.dreamingfish.updater.management;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ExternalDeliveryStatusServiceTest {
    @TempDir Path temporary;
    @Test void exportDoesNotPretendTheRemoteHostReceivedTheNewPolicy() throws Exception {
        var fixture=new ManagementFixture(temporary); fixture.createProject();
        var service=new ExternalDeliveryStatusService(fixture.paths,fixture.database,fixture.json);
        service.exported("demo", 1);
        service.uploaded("demo", 1L, "s3");
        service.exported("demo", 2);
        assertEquals(2L,service.read("demo").exportedRevision());
        assertEquals(1L,service.read("demo").uploadedRevision());
        assertEquals("s3",service.read("demo").provider());
        service.uploaded("demo",2L,"webdav");
        assertEquals(2L,service.read("demo").uploadedRevision());
        assertThrows(ManagementException.class,()->service.uploaded("demo",2L,"https://secret.example/token"));
    }
    @Test void damagedStatusIsPreservedInsteadOfBeingReplacedBySuccess() throws Exception {
        var fixture=new ManagementFixture(temporary); fixture.createProject();
        var service=new ExternalDeliveryStatusService(fixture.paths,fixture.database,fixture.json);
        service.exported("demo",1);
        Path file=fixture.paths.playerPrograms().resolve("demo/external-delivery.json");
        Files.writeString(file,"broken");
        assertFalse(service.read("demo").warning().isBlank());
        assertThrows(ManagementException.class,()->service.uploaded("demo",1L,"s3"));
        assertEquals("broken",Files.readString(file));
    }
}
