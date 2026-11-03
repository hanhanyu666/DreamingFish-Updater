import cn.dreamingfish.updater.management.*;
import cn.dreamingfish.updater.protocol.*;
import java.nio.file.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Run with Java 21 and the freshly shaded management JAR. Uses only a new synthetic directory.
 * Args: new-fixture-directory old-extracted-bundle new-extracted-bundle agent-jar
 * Opens the shipped player windows; does not send any mouse/keyboard or touch existing instances.
 */
public class NativePlayerUpdateProbe {
    public static void main(String[] args) throws Exception {
        if(args.length!=4)throw new IllegalArgumentException("Expected new fixture, old bundle, new bundle, agent JAR");
        Path root=Path.of(args[0]).toAbsolutePath().normalize();
        Files.createDirectory(root);
        Path source=Files.createDirectory(root.resolve("source"));
        Files.writeString(source.resolve("guide.txt"),"synthetic server content");
        var json=new JsonCodec(); var paths=ManagementPaths.at(root.resolve("management"));
        var db=new ManagementDatabase(paths,json);db.initialize();
        var objects=new ObjectStore(paths);
        try(var server=new PublicFileServer(db,objects,new InetSocketAddress("127.0.0.1",0))) {
            server.start();
            var projects=new ProjectService(paths,db);
            projects.create("probe","Synthetic native update",source,"http://127.0.0.1:"+server.address().getPort(),
                new Branding("Synthetic server branding","retained across program updates","",null,"#2ee8df","#b06cff"),ProjectRules.defaults());
            var programs=new PlayerProgramService(paths,db,json);
            var old=programs.publishAuto("probe","windows-x64",Path.of(args[1]),"0.1.2");
            var scan=new ScanService(paths,db,json);var preview=scan.createPreview("probe");
            var content=new PublishService(paths,db,scan,json).publish("probe","1.0","0.2.0","synthetic content",preview.previewId(),preview.confirmationDigest());
            Path instance=Files.createDirectory(root.resolve("instance"));
            Path oldBundle=Path.of(args[1]).toAbsolutePath().normalize();
            try(var files=Files.walk(oldBundle)) {
                for(Path file:files.toList()) {
                    PathSafety.assertSafePathTree(file);
                    Path target=instance.resolve(oldBundle.relativize(file));
                    if(Files.isDirectory(file,LinkOption.NOFOLLOW_LINKS))Files.createDirectories(target);
                    else Files.copy(file,target);
                }
            }
            Path bootstrap=instance.resolve(".dreamingfish-bootstrap");
            Files.copy(Path.of(args[3]),bootstrap.resolve("bootstrap-agent.jar"),StandardCopyOption.REPLACE_EXISTING);
            new PlayerInstanceService(paths,db,json).prepare("probe",instance,null,"DreamingFishUpdater","windows-x64",content.releaseId(),null);
            Path home=instance.resolve("DreamingFishUpdater"), sentinel=home.resolve("state/local-retention-probe.txt");
            Files.writeString(sentinel,"synthetic local preferences sentinel");
            String bindingHash=CryptoSupport.sha256(bootstrap.resolve("project-binding.json"));
            Path smoke=Files.createDirectory(root.resolve("smoke"));
            Files.writeString(smoke.resolve("SmokeMain.java"),"public class SmokeMain { public static void main(String[] args) throws Exception { System.out.println(\"NATIVE_UPDATE_PERMISSION_GRANTED\"); Thread.sleep(15000); } }");
            var compiler=javax.tools.ToolProvider.getSystemJavaCompiler();
            if(compiler.run(null,null,null,"-d",smoke.toString(),smoke.resolve("SmokeMain.java").toString())!=0)throw new AssertionError("Smoke compiler failed");
            var prepared=programs.prepare("probe","windows-x64","",Path.of(args[2]),"","0.1.2");
            var policies=new PlayerUpdatePolicyService(paths,db,json);
            var policy=policies.read("probe");policies.setEnabled("probe",false,policy.revision());
            runGame(root,instance,smoke,"paused");
            if(!activeVersion(home).equals(old.version()))throw new AssertionError("Paused program unexpectedly changed");
            policies.approve("probe","windows-x64",prepared.version(),prepared.manifestSha256(),policies.read("probe").revision());
            runGame(root,instance,smoke,"approved");
            if(!activeVersion(home).equals(prepared.version()))throw new AssertionError("Approved version did not activate");
            String logs=Files.readString(home.resolve("logs/player-updater.log"));
            if(!logs.contains("玩家端 "+old.version()) || !logs.contains("玩家端 "+prepared.version()))throw new AssertionError("Both real sidecars must run");
            if(!bindingHash.equals(CryptoSupport.sha256(bootstrap.resolve("project-binding.json"))))throw new AssertionError("Project binding changed");
            if(!Files.readString(sentinel).equals("synthetic local preferences sentinel"))throw new AssertionError("Local data changed");
            if(!Files.readString(instance.resolve("guide.txt")).equals("synthetic server content"))throw new AssertionError("Content changed");
            if(!db.requireProject("probe").branding().productName().equals("Synthetic server branding"))throw new AssertionError("Branding changed");
            Files.write(root.resolve("result.json"),json.writePretty(Map.of("passed",true,"oldVersion",old.version(),"newVersion",prepared.version(),
                "pausedVersionRetained",true,"bothNativeSidecarsRan",true,"contentAndLocalDataRetained",true,"realLauncherUiRetested",false)));
            System.out.println("PASS: paused old native player; approved update; new native player; real Agent permission; local data retained.");
        } finally {
            // A UI kept open or a failed initialization must not leak fixture processes.
            Path owned= root.resolve("instance/DreamingFishUpdater");
            ProcessHandle.allProcesses().filter(process -> process.info().command().map(command ->
                    Path.of(command).toAbsolutePath().normalize().startsWith(owned)).orElse(false))
                .forEach(process -> { process.descendants().forEach(ProcessHandle::destroyForcibly); process.destroyForcibly(); });
        }
    }
    private static String activeVersion(Path home) throws Exception {
        var props=new Properties();try(var reader=Files.newBufferedReader(home.resolve("state/active-player.properties"))){props.load(reader);}return props.getProperty("version");
    }
    private static void runGame(Path root,Path instance,Path smoke,String name) throws Exception {
        Path output=root.resolve(name+"-game.log");
        var builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java.exe").toString(),
            "-javaagent:.dreamingfish-bootstrap/bootstrap-agent.jar","-cp",smoke.toString(),"SmokeMain")
            .directory(instance.toFile()).redirectErrorStream(true).redirectOutput(output.toFile());
        // Keep WebView2's test profile in the fixture, away from real player/browser data.
        // https://learn.microsoft.com/en-us/microsoft-edge/webview2/reference/win32/webview2-idl
        builder.environment().put("WEBVIEW2_USER_DATA_FOLDER",root.resolve(name+"-webview").toString());
        var process=builder.start();
        boolean finished=false;
        try {
            finished=process.waitFor(150,TimeUnit.SECONDS);
            if(!finished || process.exitValue()!=0 || !Files.readString(output).contains("NATIVE_UPDATE_PERMISSION_GRANTED"))
                throw new AssertionError("Native startup failed: "+output);
        } finally {
            if(!finished){ process.descendants().forEach(ProcessHandle::destroyForcibly); process.destroyForcibly(); }
        }
    }
}
