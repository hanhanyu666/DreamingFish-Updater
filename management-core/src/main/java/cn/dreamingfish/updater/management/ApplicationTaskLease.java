package cn.dreamingfish.updater.management;

import cn.dreamingfish.updater.protocol.PathSafety;
import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;

/** Process-shared reservation held from admission through completion, including queue time.
 * Unlike a thread lock it may be released by the worker which completes the admitted task.
 */
final class ApplicationTaskLease implements AutoCloseable {
    private static final Map<Path,Held> HELD=new HashMap<>();
    private static final class Held {FileChannel channel;FileLock lock;int references;boolean exclusive;}
    private final Path marker;private final Held held;private boolean closed;
    private ApplicationTaskLease(Path marker,Held held){this.marker=marker;this.held=held;}
    static ApplicationTaskLease acquire(Path root,boolean exclusive)throws IOException {
        ApplicationTaskLease lease=tryAcquire(root,exclusive);
        if(lease==null)throw new ManagementException("另一进程仍有任务，或正在恢复管理数据根");
        return lease;
    }
    /** Null means a live reservation, not a crashed owner. Other I/O errors remain errors. */
    static synchronized ApplicationTaskLease tryAcquire(Path root,boolean exclusive)throws IOException {
        root=root.toAbsolutePath().normalize();Path marker=root.resolveSibling("."+root.getFileName()+".tasks.lock");
        PathSafety.createSafeDirectories(marker.getParent());PathSafety.assertSafePathTree(marker);
        Held existing=HELD.get(marker);
        if(existing!=null){if(exclusive||existing.exclusive)return null;existing.references++;return new ApplicationTaskLease(marker,existing);}
        FileChannel channel=FileChannel.open(marker,StandardOpenOption.CREATE,StandardOpenOption.READ,StandardOpenOption.WRITE);
        try{FileLock lock=channel.tryLock(0,Long.MAX_VALUE,!exclusive);if(lock==null){channel.close();return null;}Held held=new Held();held.channel=channel;held.lock=lock;held.references=1;held.exclusive=exclusive;HELD.put(marker,held);return new ApplicationTaskLease(marker,held);}
        catch(IOException|RuntimeException error){channel.close();throw error;}
    }
    public void close()throws IOException {synchronized(ApplicationTaskLease.class){if(closed)return;closed=true;if(--held.references==0){HELD.remove(marker);try{held.lock.release();}finally{held.channel.close();}}}}
}
