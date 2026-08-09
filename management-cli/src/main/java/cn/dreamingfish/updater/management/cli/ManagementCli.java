package cn.dreamingfish.updater.management.cli;

import cn.dreamingfish.updater.management.BackupService;
import cn.dreamingfish.updater.management.ManagementDatabase;
import cn.dreamingfish.updater.management.ManagementException;
import cn.dreamingfish.updater.management.ManagementPaths;
import cn.dreamingfish.updater.management.ObjectStore;
import cn.dreamingfish.updater.management.ProjectService;
import cn.dreamingfish.updater.management.PlayerProgramService;
import cn.dreamingfish.updater.management.PlayerDeploymentService;
import cn.dreamingfish.updater.management.PublishService;
import cn.dreamingfish.updater.management.PublicFileServer;
import cn.dreamingfish.updater.management.ScanService;
import cn.dreamingfish.updater.management.SourceFileService;
import cn.dreamingfish.updater.protocol.JsonCodec;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.nio.file.Path;

@CommandLine.Command(
        name = "dfs-admin",
        mixinStandardHelpOptions = true,
        version = "DreamingFish Update System 0.1.20",
        description = "Self-hosted Minecraft modpack update management",
        subcommands = {
                InitCommand.class,
                ProjectCommand.class,
                PlayerProgramCommand.class,
                ServeCommand.class,
                WebCommand.class,
                BackupCommand.class,
                InteractiveCommand.class
        }
)
public final class ManagementCli implements Runnable {
    static final String VERSION = "0.1.20";
    private static final Charset CONSOLE_OUTPUT_CHARSET =
            WindowsConsoleEncoding.outputCharset();

    @CommandLine.Option(
            names = "--data",
            description = "Management data directory (default: ${DEFAULT-VALUE})",
            scope = CommandLine.ScopeType.INHERIT
    )
    Path dataDirectory;

    @CommandLine.Option(
            names = "--json",
            description = "Write machine-readable JSON output",
            scope = CommandLine.ScopeType.INHERIT
    )
    boolean jsonOutput;

    private PrintWriter out = new PrintWriter(
            System.out, true, CONSOLE_OUTPUT_CHARSET);
    private PrintWriter err = new PrintWriter(
            System.err, true, CONSOLE_OUTPUT_CHARSET);
    private java.io.BufferedReader input;
    private final ManagementSettingsStore settingsStore;
    private ManagementSettings settings;

    public ManagementCli() {
        this(defaultSettingsFile(), new AdaptiveConsoleReader(System.in));
    }

    ManagementCli(Path settingsFile, java.io.Reader input) {
        settingsStore = new ManagementSettingsStore(settingsFile);
        settings = settingsStore.load();
        dataDirectory = Path.of(settings.dataDirectory());
        this.input = input instanceof java.io.BufferedReader buffered
                ? buffered : new java.io.BufferedReader(input);
    }

    public static void main(String[] args) {
        int exit = execute(args,
                new PrintWriter(System.out, true, CONSOLE_OUTPUT_CHARSET),
                new PrintWriter(System.err, true, CONSOLE_OUTPUT_CHARSET));
        if (exit != 0) {
            System.exit(exit);
        }
    }

    static int execute(String[] args, PrintWriter out, PrintWriter err) {
        return execute(args, new AdaptiveConsoleReader(System.in),
                defaultSettingsFile(), out, err);
    }

    static int execute(String[] args, java.io.Reader input, Path settingsFile,
                       PrintWriter out, PrintWriter err) {
        ManagementCli root = new ManagementCli(settingsFile, input);
        root.out = out;
        root.err = err;
        CommandLine commandLine = new CommandLine(root);
        commandLine.setOut(out);
        commandLine.setErr(err);
        commandLine.setExecutionExceptionHandler((exception, command, parseResult) -> {
            Throwable cause = exception;
            while (cause.getCause() != null && !(cause instanceof ManagementException)) {
                cause = cause.getCause();
            }
            err.println("Error: " + cause.getMessage());
            if (Boolean.getBoolean("dfs.debug")) {
                exception.printStackTrace(err);
            }
            return 2;
        });
        return commandLine.execute(args);
    }

    @Override
    public void run() {
        if (jsonOutput) {
            new CommandLine(this).usage(out);
            return;
        }
        runInteractive();
    }

    void runInteractive() {
        new InteractiveConsole(this).run();
    }

    Services services() {
        ManagementPaths paths = ManagementPaths.at(dataDirectory);
        JsonCodec json = new JsonCodec();
        ManagementDatabase database = new ManagementDatabase(paths, json);
        database.initialize();
        ProjectService projects = new ProjectService(paths, database);
        ScanService scanner = new ScanService(paths, database, json);
        PublishService publisher = new PublishService(paths, database, scanner, json);
        PlayerProgramService playerPrograms = new PlayerProgramService(paths, database, json);
        SourceFileService sourceFiles = new SourceFileService(paths, database, json);
        PlayerDeploymentService deployments = new PlayerDeploymentService(
                paths, database, json);
        return new Services(paths, json, database, projects, scanner, publisher,
                playerPrograms, deployments, sourceFiles, new ObjectStore(paths),
                new BackupService(paths, database, json));
    }

    void printJson(Object value) {
        out.println(new JsonCodec().writeString(value));
    }

    PrintWriter out() {
        return out;
    }

    PrintWriter err() {
        return err;
    }

    java.io.BufferedReader input() {
        return input;
    }

    ManagementSettings settings() {
        return settings;
    }

    Path settingsFile() {
        return settingsStore.file();
    }

    boolean hasSavedSettings() {
        return settingsStore.exists();
    }

    void saveSettings(ManagementSettings value) {
        settingsStore.save(value);
        settings = settingsStore.load();
        dataDirectory = Path.of(settings.dataDirectory());
    }

    private static Path defaultSettingsFile() {
        String home = System.getProperty("dfs.home", ".");
        return Path.of(home).toAbsolutePath().normalize().resolve("management-settings.json");
    }

    record Services(
            ManagementPaths paths,
            JsonCodec json,
            ManagementDatabase database,
            ProjectService projects,
            ScanService scanner,
            PublishService publisher,
            PlayerProgramService playerPrograms,
            PlayerDeploymentService deployments,
            SourceFileService sourceFiles,
            ObjectStore objects,
            BackupService backups
    ) {
    }

    Path bootstrapAgentPath() {
        String override = System.getProperty("dfs.bootstrapAgent", "").trim();
        if (!override.isEmpty()) return Path.of(override).toAbsolutePath().normalize();
        Path home = settingsFile().getParent();
        Path packaged = home.resolve("support/bootstrap-agent.jar");
        if (java.nio.file.Files.isRegularFile(packaged)) return packaged;
        Path legacy = home.resolve("bootstrap-agent.jar");
        if (java.nio.file.Files.isRegularFile(legacy)) return legacy;
        return packaged;
    }
}
