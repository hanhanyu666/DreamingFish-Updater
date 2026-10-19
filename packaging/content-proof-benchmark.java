// Compile in an isolated directory against each measured JAR, never into project outputs.
import cn.dreamingfish.updater.engine.LocalFileIndex;
import cn.dreamingfish.updater.protocol.CryptoSupport;
import java.nio.file.*;
import java.lang.management.ManagementFactory;

class ContentProofBenchmark {
    public static void main(String[] args)throws Exception {
        Path root=Path.of(args[0]),file=root.resolve("content.bin"),cache=root.resolve(args.length>1?args[1]:"index.json");
        String hash=CryptoSupport.sha256(file);
        var prime=LocalFileIndex.load(cache);prime.inspect(file,"content.bin",false);prime.save();
        var bean=(com.sun.management.OperatingSystemMXBean)ManagementFactory.getOperatingSystemMXBean();
        for(int i=0;i<3;i++) {
            var index=LocalFileIndex.load(cache);long cpu=bean.getProcessCpuTime(),start=System.nanoTime();
            if(!index.matches(file,"content.bin",hash,Files.size(file)))throw new AssertionError("Content differs");
            System.out.printf(java.util.Locale.ROOT,"{\"round\":%d,\"seconds\":%.9f,\"cpuSeconds\":%.9f,\"bytes\":%d}%n",i+1,(System.nanoTime()-start)/1e9,(bean.getProcessCpuTime()-cpu)/1e9,Files.size(file));
        }
        System.out.flush();System.in.read();
    }
}
