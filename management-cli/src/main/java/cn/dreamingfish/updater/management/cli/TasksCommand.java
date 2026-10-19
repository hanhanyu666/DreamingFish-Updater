package cn.dreamingfish.updater.management.cli;

import picocli.CommandLine;
import cn.dreamingfish.updater.management.ApplicationTasks;
import cn.dreamingfish.updater.management.ManagementPaths;
import cn.dreamingfish.updater.management.ManagementException;
import cn.dreamingfish.updater.protocol.JsonCodec;

@CommandLine.Command(name="tasks",description="Query durable application tasks without replaying writes")
final class TasksCommand implements Runnable {
    @CommandLine.ParentCommand ManagementCli root;
    @CommandLine.Option(names="--id") String id;
    @CommandLine.Option(names="--cancel-queued") boolean cancel;
    public void run(){
        if(cancel){if(id==null)throw new ManagementException("取消排队必须指定 --id");root.printJson(root.cancelQueuedTask(id));return;}
        var paths=ManagementPaths.at(root.dataDirectory);var json=new JsonCodec();
        root.printJson(id==null?ApplicationTasks.query(paths,json):ApplicationTasks.query(paths,json,id));
    }
}
