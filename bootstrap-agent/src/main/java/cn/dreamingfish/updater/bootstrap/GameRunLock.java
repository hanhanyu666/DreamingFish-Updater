package cn.dreamingfish.updater.bootstrap;

import java.io.Closeable;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

final class GameRunLock implements Closeable {
    private final FileChannel channel;
    private final FileLock lock;

    private GameRunLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    static GameRunLock acquire(Path instanceRoot) throws BootstrapException {
        Path marker = instanceRoot.resolve(".dreamingfish-bootstrap/game.lock");
        FileChannel channel = null;
        try {
            Files.createDirectories(marker.getParent());
            channel = FileChannel.open(marker, StandardOpenOption.CREATE,
                    StandardOpenOption.READ, StandardOpenOption.WRITE);
            FileLock lock = channel.lock(0L, Long.MAX_VALUE, true);
            return new GameRunLock(channel, lock);
        } catch (Exception e) {
            if (channel != null) {
                try {
                    channel.close();
                } catch (IOException ignored) {
                }
            }
            throw new BootstrapException("Unable to mark the Minecraft instance as running", e);
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
