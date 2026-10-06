package cn.dreamingfish.updater.management.cli;

import picocli.CommandLine;

@CommandLine.Command(name="operations", description="Show project activity, undo an operation, or restore published maintenance settings")
final class ProjectOperationsCommand implements Runnable {
    @CommandLine.ParentCommand ProjectCommand parent;
    @CommandLine.Parameters(index="0",description="Project ID") String projectId;
    @CommandLine.Option(names="--undo",description="Operation ID from the activity history") String undoId;
    @CommandLine.Option(names="--restore-published",description="Restore current published maintenance settings") boolean restorePublished;
    @CommandLine.Option(names="--yes",description="Apply the displayed restore") boolean yes;
    @Override public void run(){
        var root=parent.root;var service=root.services().operations();
        if(undoId!=null&&restorePublished)throw new cn.dreamingfish.updater.management.ManagementException("一次只能选择撤销操作或恢复版本设置");
        if(undoId!=null){
            var plan=service.previewUndo(projectId,undoId);
            if(!root.jsonOutput||!yes)root.printJson(plan);
            Confirmations.require(root,yes,"确认按预览撤销这次操作？");
            var result=service.undo(projectId,undoId,plan.stamp());
            root.printJson(root.jsonOutput&&yes?java.util.Map.of("preview",plan,"result",result):result);
        }else if(restorePublished){
            var plan=service.previewRestorePublished(projectId);
            var preview=java.util.Map.of("displayVersion",plan.displayVersion(),"changes",plan.changes());
            if(!root.jsonOutput||!yes)root.printJson(preview);
            Confirmations.require(root,yes,"确认按预览恢复当前已发布版本的维护设置？");
            var result=service.restorePublished(projectId,plan.stamp());
            root.printJson(root.jsonOutput&&yes?java.util.Map.of("preview",preview,"result",result):result);
        }else {
            var entries=service.history(projectId);
            if(root.jsonOutput)root.printJson(entries);
            else for(var entry:entries)root.out().printf("%s  %s%s%n  %s%n",entry.createdAt(),entry.title(),entry.undone()?"（已撤销）":"",entry.id());
        }
    }
}
