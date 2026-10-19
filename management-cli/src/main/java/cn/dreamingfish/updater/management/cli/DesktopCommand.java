package cn.dreamingfish.updater.management.cli;

import cn.dreamingfish.updater.management.ManagementException;
import cn.dreamingfish.updater.protocol.JsonCodec;
import cn.dreamingfish.updater.protocol.ProtocolException;
import picocli.CommandLine;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/** Owned loopback service for the desktop shell; its lifetime follows the stdin pipe. */
@CommandLine.Command(name = "desktop", description = "Run a desktop-owned loopback management service")
final class DesktopCommand implements Runnable {
    @CommandLine.ParentCommand
    ManagementCli root;

    @Override
    public void run() {
        var home = root.settingsFile().getParent();
        try {
            Files.createDirectories(home);
            home = home.toRealPath();
            var lockPath = home.resolve("desktop-backend.lock");
            try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                 FileLock lock = acquire(channel);
                 AdminWebServer server = new AdminWebServer(root, new InetSocketAddress("127.0.0.1", 0))) {
                Thread shutdown = new Thread(server::close, "dfs-desktop-shutdown");
                Runtime.getRuntime().addShutdownHook(shutdown);
                try {
                    server.start();
                    root.printJson(Map.of("type", "ready", "version", ManagementCli.VERSION,
                            "pid", ProcessHandle.current().pid(), "port", server.address().getPort(),
                            "url", "http://127.0.0.1:" + server.address().getPort() + "/",
                            "home", home.toString(),"buildId",buildId()));
                    String line;
                    while ((line = root.input().readLine()) != null) {
                        if (line.isBlank()) continue;
                        if (!shutdownRequested(line)) {
                            root.printJson(Map.of("type", "error", "message", "不支持的桌面控制命令"));
                            continue;
                        }
                        if (!server.closeForDesktop(Duration.ofSeconds(10))) {
                            root.printJson(Map.of("type", "busy", "message", "正在处理文件操作，请稍后退出"));
                            continue;
                        }
                        root.printJson(Map.of("type", "stopped"));
                        return;
                    }
                    // The owner disappeared. Give an in-flight mutation time to finish.
                    server.closeForDesktop(Duration.ofSeconds(10));
                } finally {
                    try { Runtime.getRuntime().removeShutdownHook(shutdown); }
                    catch (IllegalStateException ignored) { /* JVM shutdown is already underway. */ }
                }
            }
        } catch (IOException e) {
            throw new ManagementException("无法运行桌面管理服务: " + e.getMessage(), e);
        }
    }

    private static String buildId(){
        try(var input=DesktopCommand.class.getResourceAsStream("/META-INF/dreamingfish/build/management-cli.json")){
            if(input==null)return "development";
            Map<?,?> info=new JsonCodec().read(input,Map.class);return String.valueOf(info.get("buildId"));
        }catch(IOException error){throw new ManagementException("管理核心构建标识不可读",error);}
    }

    private static boolean shutdownRequested(String line) {
        if (line.length() > 4096) return false;
        try {
            Map<?, ?> request = new JsonCodec().read(line.getBytes(StandardCharsets.UTF_8), Map.class);
            return request != null && request.size() == 1 && "shutdown".equals(request.get("command"));
        } catch (ProtocolException invalid) {
            return false;
        }
    }

    private static FileLock acquire(FileChannel channel) throws IOException {
        try {
            FileLock lock = channel.tryLock();
            if (lock != null) return lock;
        } catch (OverlappingFileLockException ignored) { }
        throw new ManagementException("这个管理目录已经在另一个桌面管理端中打开");
    }
}
