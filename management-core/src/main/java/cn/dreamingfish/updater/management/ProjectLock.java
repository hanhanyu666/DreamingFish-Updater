package cn.dreamingfish.updater.management;

import java.io.Closeable;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

final class ProjectLock implements Closeable {
    private final FileChannel channel;
    private final FileLock lock;

    private ProjectLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    static ProjectLock acquire(Path lockFile) throws IOException {
        java.nio.file.Files.createDirectories(lockFile.getParent());
        FileChannel channel = FileChannel.open(lockFile,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) {
                channel.close();
                throw new ManagementException("Another operation is already changing this project");
            }
            return new ProjectLock(channel, lock);
        } catch (java.nio.channels.OverlappingFileLockException e) {
            channel.close();
            throw new ManagementException("Another operation is already changing this project", e);
        }
    }

    @Override
    public void close() throws IOException {
        try {
            lock.release();
        } finally {
            channel.close();
        }
    }
}
