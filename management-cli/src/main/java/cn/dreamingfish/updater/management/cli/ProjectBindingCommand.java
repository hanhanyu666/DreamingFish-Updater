package cn.dreamingfish.updater.management.cli;

import cn.dreamingfish.updater.management.ManagementException;
import cn.dreamingfish.updater.management.BundledReleasePreparer;
import cn.dreamingfish.updater.management.PlayerProgramBootstrapper;
import cn.dreamingfish.updater.protocol.CryptoSupport;
import cn.dreamingfish.updater.protocol.PathSafety;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

@CommandLine.Command(name = "binding", description = "Export a player project binding file")
final class ProjectBindingCommand implements Runnable {
    @CommandLine.ParentCommand
    ProjectCommand parent;
    @CommandLine.Parameters(index = "0")
    String projectId;
    @CommandLine.Option(names = "--output", description = "Export only the binding JSON to this file")
    Path output;
    @CommandLine.Option(names = "--instance",
            description = "Prepare a runnable player bundle in this Minecraft instance")
    Path instance;
    @CommandLine.Option(names = "--player-home", defaultValue = "DreamingFishUpdater")
    String playerHome;
    @CommandLine.Option(names = "--platform", defaultValue = "windows-x64")
    String platform;
    @CommandLine.Option(names = "--release",
            description = "Immutable release ID represented by the distributable instance")
    String releaseId;
    @CommandLine.Option(names = "--bundled-cover", description = "Cover path relative to the instance directory")
    String bundledCover;

    @Override
    public void run(){
        var root=parent.root;
        var prepared=root.services().instances().prepare(projectId,instance,output,playerHome,platform,releaseId,bundledCover);
        if(root.jsonOutput){var result=new java.util.LinkedHashMap<String,Object>();result.put("output",prepared.output().toString());result.put("binding",prepared.binding());if(prepared.playerProgram()!=null)result.put("playerProgram",prepared.playerProgram());if(prepared.bundledRelease()!=null)result.put("bundledRelease",prepared.bundledRelease());root.printJson(result);}
        else if(prepared.playerProgram()!=null)root.out().println("Prepared signed player program "+prepared.playerProgram().version()+", bundled release "+prepared.bundledRelease().displayVersion()+" ("+prepared.bundledRelease().releaseId()+"), and wrote project binding to "+prepared.output());
        else root.out().println("Exported binding JSON to "+prepared.output()+"; use --instance to prepare a runnable player bundle");
    }
}
