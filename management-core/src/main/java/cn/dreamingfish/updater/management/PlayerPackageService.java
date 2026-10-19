package cn.dreamingfish.updater.management;

import cn.dreamingfish.updater.protocol.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Inspects a bounded ZIP before the owner explicitly publishes its signed program. */
public final class PlayerPackageService {
    private static final long MAX_EXPANDED_BYTES = 4L * 1024 * 1024 * 1024;
    private final ManagementPaths paths;
    private final ManagementDatabase database;
    private final JsonCodec json;

    public PlayerPackageService(ManagementPaths paths, ManagementDatabase database, JsonCodec json) {
        this.paths = paths; this.database = database; this.json = json;
    }

    public PackageView stage(String projectId, String fileName, InputStream input, long length) {
        database.requireProject(projectId);
        if (fileName == null || !fileName.toLowerCase(Locale.ROOT).endsWith(".zip"))
            throw new ManagementException("请选择玩家端发行 ZIP");
        String id = UUID.randomUUID().toString();
        Path directory = directory(projectId, id);
        try (ProjectLock lock=ProjectLock.acquire(paths.locks().resolve("package-"+id+".lock"));
             StorageService.Lease budget=new StorageService(paths,database,json).reserve(Math.addExact(length<0?SourceFileService.MAX_UPLOAD_BYTES:length,MAX_EXPANDED_BYTES),0)) {
            Files.createDirectories(directory);
            Path zip = directory.resolve("package.zip");
            budget.copy(input, zip, length, SourceFileService.MAX_UPLOAD_BYTES);
            Path extracted = directory.resolve("bundle");
            extract(zip, extracted,budget);
            var resolved = PlayerProgramService.resolveSource(extracted, "", "");
            String platform = resolved.launcher().toLowerCase(Locale.ROOT).endsWith(".exe") ? "windows-x64" : "linux-x64";
            String relative = extracted.relativize(resolved.root()).toString().replace('\\', '/');
            boolean newer = new PlayerProgramService(paths, database, json).latest(projectId, platform)
                    .map(current -> SemanticVersion.parse(resolved.version()).compareTo(SemanticVersion.parse(current.version())) > 0).orElse(true);
            PackageView view = new PackageView(id, resolved.version(), platform, resolved.launcher(),
                    Files.size(zip), fileName, relative, newer, newer ? "" : "此版本不高于当前选中的玩家端程序，不能重复发布。");
            AtomicFiles.write(directory.resolve("package.json"), json.write(view));
            return view;
        } catch (IOException | RuntimeException error) {
            discard(projectId, id);
            throw error instanceof ManagementException known ? known
                    : new ManagementException("无法读取玩家端 ZIP：" + error.getMessage(), error);
        }
    }

    public StoredPlayerProgram publish(String projectId,String id,String minimumBootstrapVersion) {
        Path directory=directory(projectId,id);
        try(ProjectLock project=ProjectLock.acquire(paths.locks().resolve(projectId+".lock"));ProjectLock lock=ProjectLock.acquire(paths.locks().resolve("package-"+id+".lock"))) {
            var programs=new PlayerProgramService(paths,database,json);
            Path published=directory.resolve("published.json");
            if(Files.isRegularFile(published,LinkOption.NOFOLLOW_LINKS)){
                var previous=json.read(published,StoredPlayerProgram.class);return programs.read(projectId,previous.platform(),previous.version());
            }
            PackageView view=json.read(directory.resolve("package.json"),PackageView.class);
            if(!view.publishable())throw new ManagementException(view.problem());
            Path root=directory.resolve("bundle");if(!view.sourceRelative().isEmpty())root=PathSafety.resolveInside(root,view.sourceRelative());
            String minimum=minimumBootstrapVersion==null?"0.1.2":minimumBootstrapVersion;
            Path intent=directory.resolve("publish-intent.json");
            boolean retry=Files.isRegularFile(intent,LinkOption.NOFOLLOW_LINKS);
            if(retry){String expected=json.read(intent,PublishIntent.class).minimumBootstrapVersion();if(!minimum.equals(expected))throw new ManagementException("发布重试的 Bootstrap 约束与原请求不同");}
            else AtomicFiles.write(intent,json.write(new PublishIntent(minimum)));
            StoredPlayerProgram result;
            Path manifestPath=paths.playerProgramDirectory(projectId,view.platform(),view.version()).resolve("manifest.json");
            if(retry&&Files.isRegularFile(manifestPath,LinkOption.NOFOLLOW_LINKS)){
                result=programs.read(projectId,view.platform(),view.version());
                var manifest=json.read(result.manifestPath(),PlayerProgramManifest.class);
                if(!manifest.minimumBootstrapVersion().equals(minimum)||!manifest.launchPath().equals(view.launcher()))throw new ManagementException("已有发布与原请求身份不同，保留暂存待人工核对");
                Set<String> expected=new HashSet<>();
                for(var file:manifest.files()){
                    Path source=PathSafety.resolveInside(root,file.path());
                    if(!Files.isRegularFile(source,LinkOption.NOFOLLOW_LINKS)||Files.size(source)!=file.size()||!CryptoSupport.sha256(source).equals(file.sha256()))throw new ManagementException("已有发布内容与暂存不同，不能当作成功重试");
                    expected.add(file.path());
                }
                try(var files=Files.walk(root)){for(Path file:files.filter(p->Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)).toList())if(!expected.contains(root.relativize(file).toString().replace('\\','/')))throw new ManagementException("暂存有未发布的额外内容，请人工核对");}
            }else result=programs.publish(projectId,view.platform(),view.version(),root,view.launcher(),minimum);
            AtomicFiles.write(published,json.write(result));
            releaseCopies(directory);
            return result;
        }catch(IOException error){throw new ManagementException("玩家端暂存或发布收据不可用；保留原请求后复查",error);}
    }
    private record PublishIntent(String minimumBootstrapVersion){}
    public void discard(String projectId,String id) {
        Path directory=directory(projectId,id);
        try(ProjectLock lock=ProjectLock.acquire(paths.locks().resolve("package-"+id+".lock"))){
            if(Files.exists(directory.resolve("published.json"))){releaseCopies(directory);return;}
            if(Files.exists(directory.resolve("publish-intent.json")))throw new ManagementException("玩家程序发布结果待确认，不能丢弃原请求");
            AtomicFiles.deleteRecursively(directory);
        }catch(IOException error){throw new ManagementException("暂存清理未完成，请查看存储清理预览",error);}
    }
    private static void releaseCopies(Path directory)throws IOException {
        AtomicFiles.deleteRecursively(directory.resolve("bundle"));Files.deleteIfExists(directory.resolve("package.zip"));
        Files.deleteIfExists(directory.resolve("publish-intent.json"));
    }

    static void extract(Path zip, Path root) throws IOException {extract(zip,root,null);}
    private static void extract(Path zip,Path root,StorageService.Lease budget)throws IOException {
        PathSafety.createSafeDirectories(root);
        Set<String> names = new HashSet<>();
        long expanded = 0;
        int entries = 0;
        try (ZipInputStream input = new ZipInputStream(Files.newInputStream(zip))) {
            for (ZipEntry entry; (entry = input.getNextEntry()) != null;) {
                if (++entries > 20_000) throw new ManagementException("ZIP 文件数量过多");
                String raw = entry.getName();
                String normalized = PathSafety.normalizeManifestPath(entry.isDirectory()
                        ? raw.replaceAll("/+$", "") : raw);
                if (!names.add(ManagedPaths.fold(normalized)))
                    throw new ManagementException("ZIP 有重复路径：" + normalized);
                Path target = PathSafety.resolveInside(root, normalized);
                if (entry.isDirectory()) { PathSafety.createSafeDirectories(target); continue; }
                PathSafety.createSafeDirectories(target.getParent());
                try (OutputStream output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
                    byte[] buffer = new byte[128 * 1024];
                    for (int lengthRead; (lengthRead = input.read(buffer)) >= 0;) {
                        expanded += lengthRead;
                        if (expanded > MAX_EXPANDED_BYTES) throw new ManagementException("ZIP 解压后超过 4 GiB");
                        if(budget!=null)budget.charge(lengthRead);
                        output.write(buffer, 0, lengthRead);
                    }
                }
            }
        }
        if (entries == 0) throw new ManagementException("ZIP 中没有玩家端文件");
    }

    private Path directory(String projectId, String id) {
        database.requireProject(projectId);
        if (id == null || !id.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"))
            throw new ManagementException("玩家端暂存包不存在");
        try { return PathSafety.resolveInside(paths.root().resolve("player-imports"), projectId + "/" + id); }
        catch (IOException error) { throw new ManagementException("玩家端暂存路径无效", error); }
    }
    public record PackageView(String id, String version, String platform, String launcher,
                              long size, String fileName, String sourceRelative, boolean publishable, String problem) {}
}
