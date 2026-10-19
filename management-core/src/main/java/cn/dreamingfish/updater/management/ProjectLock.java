package cn.dreamingfish.updater.management;

import java.io.Closeable;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

final class ProjectLock implements Closeable {
    private static final ThreadLocal<java.util.Map<Path, Held>> HELD = ThreadLocal.withInitial(java.util.HashMap::new);
    private final Path path;
    private final Held held;
    private final Thread owner;
    private boolean closed;

    private ProjectLock(Path path, Held held) {
        this.path = path;
        this.held = held;
        this.owner = Thread.currentThread();
    }

    static ProjectLock acquire(Path lockFile) throws IOException {
        lockFile = lockFile.toAbsolutePath().normalize();
        cn.dreamingfish.updater.protocol.PathSafety.createSafeDirectories(lockFile.getParent());
        cn.dreamingfish.updater.protocol.PathSafety.assertSafePathTree(lockFile);
        Held existing = HELD.get().get(lockFile);
        if (existing != null) {
            existing.references++;
            return new ProjectLock(lockFile, existing);
        }
        InstanceAccess access = lockFile.getParent().getFileName().toString().equals("locks")
                ? InstanceAccess.acquire(lockFile.getParent().getParent(),false) : null;
        FileChannel channel;
        try{channel=FileChannel.open(lockFile,StandardOpenOption.CREATE,StandardOpenOption.WRITE);}
        catch(IOException|RuntimeException error){if(access!=null)access.close();throw error;}
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) {
                channel.close();
                throw new ManagementException("Another operation is already changing this project");
            }
            Held held = new Held(channel, lock,access);
            HELD.get().put(lockFile, held);
            return new ProjectLock(lockFile, held);
        } catch (java.nio.channels.OverlappingFileLockException e) {
            channel.close();if(access!=null)access.close();
            throw new ManagementException("Another operation is already changing this project", e);
        } catch (IOException | RuntimeException failure) {
            channel.close();if(access!=null)access.close();
            throw failure;
        }
    }

    @Override
    public void close() throws IOException {
        if (closed) return;
        if (Thread.currentThread() != owner) throw new IllegalStateException("Project lock must be released by its owning thread");
        closed = true;
        if (--held.references > 0) return;
        HELD.get().remove(path);
        if (HELD.get().isEmpty()) HELD.remove();
        try {
            held.lock.release();
        } finally {
            try{held.channel.close();}finally{if(held.access!=null)held.access.close();}
        }
    }

    private static final class Held {
        final FileChannel channel;
        final FileLock lock;
        int references = 1;
        final InstanceAccess access;
        Held(FileChannel channel, FileLock lock,InstanceAccess access) { this.channel = channel; this.lock = lock; this.access=access; }
    }
}
