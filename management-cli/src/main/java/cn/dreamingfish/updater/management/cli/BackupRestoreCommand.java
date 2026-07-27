package cn.dreamingfish.updater.management.cli;

import cn.dreamingfish.updater.management.BackupService;
import cn.dreamingfish.updater.management.ManagementDatabase;
import cn.dreamingfish.updater.management.ManagementPaths;
import cn.dreamingfish.updater.protocol.JsonCodec;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.Arrays;

@CommandLine.Command(name = "restore", description = "Verify and restore a complete encrypted backup")
final class BackupRestoreCommand implements Runnable {
    @CommandLine.ParentCommand
    BackupCommand parent;
    @CommandLine.Parameters(index = "0")
    Path archive;
    @CommandLine.Option(names = "--password-env", defaultValue = "DFS_BACKUP_PASSWORD")
    String passwordEnvironment;
    @CommandLine.Option(names = "--force", description = "Replace an existing management data directory")
    boolean force;

    @Override
    public void run() {
        ManagementCli root = parent.root;
        char[] password = BackupPasswords.read(passwordEnvironment, false);
        try {
            ManagementPaths paths = ManagementPaths.at(root.dataDirectory);
            JsonCodec json = new JsonCodec();
            BackupService backup = new BackupService(paths, new ManagementDatabase(paths, json), json);
            backup.restore(archive, password, force);
            if (root.jsonOutput) root.printJson(java.util.Map.of("status", "restored", "dataDirectory", paths.root().toString()));
            else root.out().println("Restored management data to " + paths.root());
        } finally {
            Arrays.fill(password, '\0');
        }
    }
}
