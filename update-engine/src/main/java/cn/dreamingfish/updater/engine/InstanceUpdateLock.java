package cn.dreamingfish.updater.engine;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

final class InstanceUpdateLock implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;

    private InstanceUpdateLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    static InstanceUpdateLock acquire(Path path) {
        FileChannel channel = null;
        try {
            Files.createDirectories(path.getParent());
            channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException e) {
                lock = null;
            }
            if (lock == null) {
                channel.close();
                throw new UpdateException(UpdateErrorCode.INSTANCE_BUSY,
                        "Another updater is already working on this instance");
            }
            return new InstanceUpdateLock(channel, lock);
        } catch (UpdateException e) {
            throw e;
        } catch (IOException e) {
            if (channel != null) {
                try {
                    channel.close();
                } catch (IOException ignored) {
                }
            }
            throw new UpdateException(UpdateErrorCode.INSTANCE_BUSY,
                    "Unable to lock this instance for updating", e);
        }
    }

    @Override
    public void close() {
        try {
            lock.release();
        } catch (IOException ignored) {
        }
        try {
            channel.close();
        } catch (IOException ignored) {
        }
    }
}
