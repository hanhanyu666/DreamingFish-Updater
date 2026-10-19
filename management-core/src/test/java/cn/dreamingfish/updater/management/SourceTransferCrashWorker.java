package cn.dreamingfish.updater.management;

import cn.dreamingfish.updater.protocol.JsonCodec;
import java.nio.file.Path;

/** Separate JVM; Runtime.halt deliberately bypasses catch/finally and shutdown hooks. */
public final class SourceTransferCrashWorker {
    public static void main(String[] args) {
        ManagementPaths paths=ManagementPaths.at(Path.of(args[0]));
        JsonCodec json=new JsonCodec();
        ManagementDatabase database=new ManagementDatabase(paths,json);
        database.initialize();
        java.util.function.Consumer<String> fail=phase->{if(phase.equals(args[3]))Runtime.getRuntime().halt(73);};
        if(args[2].equals("recover"))new SourceImportRecovery(paths,database,json,fail).recoverPending("demo");
        else {
            SourceTransferService service=new SourceTransferService(paths,database,json,fail);
            if(args[2].equals("undo"))service.undo("demo",args[1]);
            else {var plan=service.plan("demo",args[1]);service.commit("demo",args[1],!plan.replacements().isEmpty()?"REPLACE_MOD":plan.existing()!=null?"OVERWRITE":"ADD",plan.stamp(),false);}
        }
    }
}
