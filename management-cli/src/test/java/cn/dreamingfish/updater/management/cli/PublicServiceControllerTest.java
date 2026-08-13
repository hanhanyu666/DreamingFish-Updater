package cn.dreamingfish.updater.management.cli;

import cn.dreamingfish.updater.management.ManagementException;
import cn.dreamingfish.updater.management.PublicFileServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.StringReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublicServiceControllerTest {
    @TempDir
    Path temporary;

    @Test
    void recognizesHealthyServiceStartedByAnotherManagementProcess() throws Exception {
        ManagementCli root = rootWithPort(availablePort());
        ManagementCli.Services services = root.services();
        try (PublicFileServer external = new PublicFileServer(
                services.database(), services.objects(),
                new InetSocketAddress("127.0.0.1", root.settings().httpPort()));
             PublicServiceController controller = new PublicServiceController(root)) {
            external.start();

            PublicServiceController.Status status = controller.status();
            assertTrue(status.running());
            assertFalse(status.managed());
            assertFalse(status.controllable());
            assertTrue(status.portOccupied());
            assertTrue(status.detail().contains("另一个管理端进程"));

            PublicServiceController.Status afterStart = controller.start();
            assertTrue(afterStart.running());
            assertFalse(afterStart.managed());
            assertThrows(ManagementException.class, controller::stop,
                    "没有安全控制通道时不能误停另一个进程中的服务");
        }
    }

    @Test
    void stopsAndRestartsARegisteredServiceFromAnotherManagementProcess()
            throws Exception {
        ManagementCli root = rootWithPort(availablePort());
        ManagementCli.Services services = root.services();
        try (PublicFileServer external = new PublicFileServer(
                services.database(), services.objects(),
                new InetSocketAddress("127.0.0.1", root.settings().httpPort()))) {
            external.start();
            try (PublicServiceControl ignored = PublicServiceControl.register(
                    root, root.settings().httpHost(), root.settings().httpPort(),
                    external::close);
                 PublicServiceController controller =
                         new PublicServiceController(root)) {
                PublicServiceController.Status status = controller.status();
                assertTrue(status.running());
                assertFalse(status.managed());
                assertTrue(status.controllable());

                PublicServiceController.Status stopped = controller.stop();
                assertFalse(stopped.running());
                assertFalse(stopped.portOccupied());

                PublicServiceController.Status restarted = controller.start();
                assertTrue(restarted.running());
                assertTrue(restarted.managed());
                assertTrue(restarted.controllable());
            }
        }
    }

    @Test
    void reportsOccupiedButUnhealthyPortBeforeTryingToBind() throws Exception {
        try (ServerSocket occupied = new ServerSocket(0, 16, InetAddress.getLoopbackAddress())) {
            ManagementCli root = rootWithPort(occupied.getLocalPort());
            try (PublicServiceController controller = new PublicServiceController(root)) {
                PublicServiceController.Status status = controller.status();
                assertFalse(status.running());
                assertFalse(status.managed());
                assertTrue(status.portOccupied());
                assertTrue(status.detail().contains("没有正常响应"));

                ManagementException error = assertThrows(
                        ManagementException.class, controller::start);
                assertTrue(error.getMessage().contains("已被占用"));
                assertFalse(error.getMessage().contains("Unable to bind"));
            }
        }
    }

    private ManagementCli rootWithPort(int port) {
        ManagementCli root = new ManagementCli(
                temporary.resolve("admin/management-settings-" + port + ".json"),
                new StringReader(""));
        root.saveSettings(root.settings().withHttp("127.0.0.1", port));
        return root;
    }

    private static int availablePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }
}
