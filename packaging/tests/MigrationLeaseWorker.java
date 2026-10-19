package cn.dreamingfish.updater.management;
import java.nio.file.Path;

/** Synthetic cross-runtime lock holder; never opens a real database or publishes. */
class MigrationLeaseWorker {
    public static void main(String[] args)throws Exception {
        try(var lease=ApplicationTaskLease.acquire(Path.of(args[0]),false)) {
            System.out.println("ready");System.out.flush();System.in.read();
        }
    }
}
