package cn.dreamingfish.updater.engine;

import java.nio.channels.FileChannel;
import java.nio.file.*;

public final class GameLockWorker {
    public static void main(String[] args)throws Exception {
        Path marker=Path.of(args[0]);Files.createDirectories(marker.getParent());
        try(var channel=FileChannel.open(marker,StandardOpenOption.CREATE,StandardOpenOption.READ,StandardOpenOption.WRITE);
            var lock=channel.lock(0,Long.MAX_VALUE,true)) {
            System.out.println("LOCKED");System.out.flush();System.in.read();
        }
    }
}
