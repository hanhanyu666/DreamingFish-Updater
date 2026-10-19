package cn.dreamingfish.updater.management;

import cn.dreamingfish.updater.protocol.PathSafety;
import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** Stable parent guard: normal operations share it; root replacement is exclusive.
 * Lock order: instance access -> project/resource -> storage reservation. All acquisition
 * is nonblocking; a conflict is reported instead of waiting in an inverted lock order.
 */
final class InstanceAccess implements AutoCloseable {
    private static final ConcurrentHashMap<Path,Held> INSTANCES=new ConcurrentHashMap<>();
    private static final class Held {
        final ReentrantReadWriteLock local=new ReentrantReadWriteLock();
        FileChannel channel;FileLock file;int readers;boolean exclusive;
    }
    private final Held held;private final boolean write;private boolean closed;
    private InstanceAccess(Held held,boolean write){this.held=held;this.write=write;}
    static InstanceAccess acquire(Path root,boolean exclusive)throws IOException {
        root=root.toAbsolutePath().normalize();Path parent=root.getParent();
        if(parent==null)throw new ManagementException("管理数据根必须有父目录");
        Path marker=parent.resolve("."+root.getFileName()+".maintenance.lock");PathSafety.createSafeDirectories(parent);PathSafety.assertSafePathTree(marker);
        Held state=INSTANCES.computeIfAbsent(marker,key->new Held());
        java.util.concurrent.locks.Lock local=exclusive?state.local.writeLock():state.local.readLock();
        if(!local.tryLock())throw new ManagementException("管理实例正在进行备份恢复或目录切换，请稍后重试");
        try {
            synchronized(state){
                if(exclusive){
                    if(state.readers!=0||state.exclusive)throw new ManagementException("管理实例仍有活动任务，不能替换数据根");
                    open(state,marker,false);state.exclusive=true;
                }else{
                    if(!state.exclusive&&state.readers==0)open(state,marker,true);
                    state.readers++;
                }
            }
            return new InstanceAccess(state,exclusive);
        }catch(IOException|RuntimeException error){local.unlock();throw error;}
    }
    private static void open(Held held,Path marker,boolean shared)throws IOException {
        FileChannel channel=FileChannel.open(marker,StandardOpenOption.CREATE,StandardOpenOption.READ,StandardOpenOption.WRITE);
        try {
            FileLock lock=channel.tryLock(0,Long.MAX_VALUE,shared);
            if(lock==null)throw new ManagementException("另一进程正在使用或替换管理实例");
            held.channel=channel;held.file=lock;
        }catch(IOException|RuntimeException error){channel.close();throw error;}
    }
    public void close()throws IOException {
        if(closed)return;closed=true;
        try{synchronized(held){
            if(write)held.exclusive=false;else held.readers--;
            if(!held.exclusive&&held.readers==0){try{held.file.release();}finally{held.channel.close();held.file=null;held.channel=null;}}
        }}finally{if(write)held.local.writeLock().unlock();else held.local.readLock().unlock();}
    }
}
