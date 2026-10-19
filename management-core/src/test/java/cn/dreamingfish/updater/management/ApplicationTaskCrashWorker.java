package cn.dreamingfish.updater.management;
import cn.dreamingfish.updater.protocol.JsonCodec;
import java.nio.file.*;
public final class ApplicationTaskCrashWorker {
    public static void main(String[] args)throws Exception {
        var paths=ManagementPaths.at(Path.of(args[0]));paths.initialize();
        var tasks=new ApplicationTasks(paths,new JsonCodec());
        tasks.submit(args[1],"demo","publish",null,"stable",false,()->{Files.writeString(paths.root().resolve("executed-once.txt"),"once");Runtime.getRuntime().halt(73);return null;});
        Thread.sleep(30_000);
    }
}
