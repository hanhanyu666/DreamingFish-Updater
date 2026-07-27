package cn.dreamingfish.updater.engine;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Holds the exclusive side of the Agent's shared game-run lock. */
public final class GameUpdateLock implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;
    private boolean closed;

    private GameUpdateLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    public static GameUpdateLock tryAcquire(Path marker) {
        FileChannel channel = null;
        try {
            Files.createDirectories(marker.getParent());
            channel = FileChannel.open(marker, StandardOpenOption.CREATE,
                    StandardOpenOption.READ, StandardOpenOption.WRITE);
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException e) {
                lock = null;
            }
            if (lock == null) {
                channel.close();
                return null;
            }
            return new GameUpdateLock(channel, lock);
        } catch (IOException e) {
            if (channel != null) {
                try {
                    channel.close();
                } catch (IOException ignored) {
                }
            }
            throw new UpdateException(UpdateErrorCode.GAME_RUNNING,
                    "Unable to lock the Minecraft instance for updating", e);
        }
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
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
