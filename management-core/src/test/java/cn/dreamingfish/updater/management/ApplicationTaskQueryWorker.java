package cn.dreamingfish.updater.management;

import cn.dreamingfish.updater.protocol.JsonCodec;
import java.nio.file.*;

/** A distinct observer process must never turn another process's live job into an orphan. */
public final class ApplicationTaskQueryWorker {
    public static void main(String[] args)throws Exception {
        var paths=ManagementPaths.at(Path.of(args[0]));var json=new JsonCodec();
        String id=args[1];Path record=paths.root().resolve("application-tasks/"+id+".json");
        byte[] before=Files.readAllBytes(record);
        if(ApplicationTasks.query(paths,json,id).state()!=ApplicationTasks.State.RUNNING)throw new AssertionError("read-only query misclassified live owner");
        if(ApplicationTasks.query(paths,json).stream().noneMatch(task->task.id().equals(id)&&task.state()==ApplicationTasks.State.RUNNING))throw new AssertionError("query list lost live task");
        try(var observer=new ApplicationTasks(paths,json)){
            if(observer.require(id).state()!=ApplicationTasks.State.RUNNING)throw new AssertionError("second executor misclassified live owner");
            observer.submit(id,"demo","scan",null,"same",false,()->{throw new AssertionError("must not replay another executor's task");});
        }
        if(!java.util.Arrays.equals(before,Files.readAllBytes(record)))throw new AssertionError("observer changed live task record");
        System.out.println("live owner and disk state preserved");
    }
}
