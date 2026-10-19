package cn.dreamingfish.updater.management.cli;

import cn.dreamingfish.updater.management.StorageService;
import picocli.CommandLine;

@CommandLine.Command(name="storage",description="Inspect staging budgets and preview or confirm safe cleanup")
final class StorageCommand implements Runnable {
    @CommandLine.ParentCommand ManagementCli root;
    @CommandLine.Option(names="--forget-undo",description="Include import archives; confirmation permanently loses undo") boolean forgetUndo;
    @CommandLine.Option(names="--confirm",description="Apply exactly this preview stamp; omission only previews") String stamp;
    public void run(){
        var services=root.services();var storage=new StorageService(services.paths(),services.database(),services.json());
        root.printJson(stamp==null?storage.preview(forgetUndo):storage.clean(stamp,forgetUndo));
    }
}
