package cn.dreamingfish.updater.management;

import java.io.IOException;
import java.nio.file.Path;

interface BackupRestoreFaultInjector {
    BackupRestoreFaultInjector NONE = new BackupRestoreFaultInjector() { };

    default void beforeInstall(Path restored, Path destination) throws IOException { }

    default void beforeRollback(Path previous, Path destination) throws IOException { }
}
