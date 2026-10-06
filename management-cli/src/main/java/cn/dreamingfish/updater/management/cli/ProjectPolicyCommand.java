package cn.dreamingfish.updater.management.cli;

import cn.dreamingfish.updater.management.ManagementException;
import cn.dreamingfish.updater.management.OptionalGroupRule;
import cn.dreamingfish.updater.management.ProjectPolicyService;
import cn.dreamingfish.updater.management.ProjectRecord;
import cn.dreamingfish.updater.management.ProjectRules;
import cn.dreamingfish.updater.protocol.CorrectionMode;
import cn.dreamingfish.updater.protocol.MaintenancePreset;
import picocli.CommandLine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Maintenance rules from the command line, so owners on SSH-only hosts can do
 * everything the Web console's “管理内容” page offers. Every change takes effect
 * with the next confirmed release.
 */
@CommandLine.Command(
        name = "policy",
        description = "Maintenance presets, cleanup directories, optional groups, withdrawals and corrections",
        subcommands = {
                ProjectPolicyCommand.Show.class,
                ProjectPolicyCommand.Preset.class,
                ProjectPolicyCommand.Cleanup.class,
                ProjectPolicyCommand.Group.class,
                ProjectPolicyCommand.GroupDelete.class,
                ProjectPolicyCommand.GroupMembers.class,
                ProjectPolicyCommand.History.class,
                ProjectPolicyCommand.Withdraw.class,
                ProjectPolicyCommand.WithdrawalRevoke.class,
                ProjectPolicyCommand.Correct.class,
                ProjectPolicyCommand.CorrectionRevoke.class
        }
)
final class ProjectPolicyCommand implements Runnable {
    @CommandLine.ParentCommand
    ProjectCommand parent;

    @Override
    public void run() {
        new CommandLine(this).usage(parent.root.out());
    }

    ManagementCli root() {
        return parent.root;
    }

    ProjectPolicyService policies() {
        return root().services().policies();
    }

    void printRules(ProjectRecord project) {
        ManagementCli root = root();
        ProjectRules rules = project.rules();
        if (root.jsonOutput) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("presets", rules.presets());
            view.put("cleanupDirectories", rules.cleanupDirectories());
            view.put("optionalGroups", rules.optionalGroups());
            view.put("withdrawals", rules.withdrawals());
            view.put("corrections", rules.corrections());
            root.printJson(view);
            return;
        }
        var out = root.out();
        out.println("维护方式（未列出的文件为普通同步）：");
        if (rules.presets().isEmpty()) out.println("  （无）");
        rules.presets().forEach(rule -> out.printf("  %-8s %s%s%n", PolicyText.preset(rule.preset()),
                rule.path(), rule.directory() ? "/（文件夹）" : ""));
        out.println("完整强制同步的目录：" + (rules.cleanupDirectories().isEmpty()
                ? "（未开启）" : String.join("、", rules.cleanupDirectories())));
        out.println("可选内容：" + (rules.optionalGroups().isEmpty() ? "（无）" : ""));
        rules.optionalGroups().forEach(group -> {
            out.printf("  %s [%s] %s%n", group.title(), group.id(), group.defaultInstall() ? "默认安装" : "默认不安装");
            if (!group.modIds().isEmpty()) out.println("    模组：" + String.join("、", group.modIds()));
            if (!group.files().isEmpty()) out.println("    文件：" + String.join("、", group.files()));
            if (!group.directories().isEmpty()) out.println("    文件夹：" + String.join("、", group.directories()));
        });
        out.println("撤回问题版本：" + (rules.withdrawals().isEmpty() ? "（无）" : ""));
        rules.withdrawals().forEach(withdrawal -> {
            out.printf("  [%s] %s%n", withdrawal.id(), withdrawal.reason());
            withdrawal.items().forEach(item -> out.printf("    %s%s  %s%n", item.path(),
                    item.version() == null ? "" : " " + item.version(), item.sha256().substring(0, 12)));
        });
        out.println("修正玩家文件：" + (rules.corrections().isEmpty() ? "（无）" : ""));
        rules.corrections().forEach(correction -> out.printf("  [%s] %s  %s  %s%n", correction.id(),
                correction.path(), correction.mode() == CorrectionMode.ONCE ? "各覆盖一次" : "替换已知旧版本",
                correction.reason()));
        out.println("修改会在下一次确认发布后对玩家生效。");
    }

    static MaintenancePreset parsePreset(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (normalized.equals("DEFAULT") || normalized.equals("FOLLOW")) return null;
        try {
            MaintenancePreset preset = MaintenancePreset.valueOf(normalized);
            if (preset == MaintenancePreset.DEFAULT_CONFIG) throw new IllegalArgumentException();
            return preset;
        } catch (IllegalArgumentException e) {
            throw new ManagementException(
                    "维护方式只能是 REQUIRED（强制）、SYNC（普通）、INITIAL（首次）或 DEFAULT（跟随上级）：" + value);
        }
    }

    /** Resolves {@code path=shaPrefix} against the project's published history. */
    List<ProjectPolicyService.VersionReference> resolveVersions(String projectId, List<String> specs) {
        List<ProjectPolicyService.FileHistory> history = policies().history(projectId);
        List<ProjectPolicyService.VersionReference> result = new ArrayList<>();
        for (String spec : specs) {
            int separator = spec.lastIndexOf('=');
            if (separator <= 0 || separator == spec.length() - 1) {
                throw new ManagementException("版本格式应为 路径=内容标识前缀，例如 mods/a.jar=3f2a9c1b：" + spec);
            }
            String path = spec.substring(0, separator).replace('\\', '/');
            String prefix = spec.substring(separator + 1).toLowerCase(Locale.ROOT);
            ProjectPolicyService.FileHistory file = history.stream()
                    .filter(entry -> entry.path().equalsIgnoreCase(path)).findFirst()
                    .orElseThrow(() -> new ManagementException("这个文件没有发布记录：" + path));
            List<ProjectPolicyService.FileVersion> matches = file.versions().stream()
                    .filter(version -> version.sha256().startsWith(prefix)
                            || prefix.equalsIgnoreCase(version.version() == null ? "" : version.version()))
                    .toList();
            if (matches.size() != 1) {
                throw new ManagementException(matches.isEmpty()
                        ? "没有找到这个版本：" + spec
                        : "版本标识不唯一，请写更长的内容标识前缀：" + spec);
            }
            result.add(new ProjectPolicyService.VersionReference(file.path(), matches.getFirst().sha256()));
        }
        return result;
    }

    @CommandLine.Command(name = "show", description = "Show the project's maintenance rules")
    static final class Show implements Runnable {
        @CommandLine.ParentCommand
        ProjectPolicyCommand parent;
        @CommandLine.Parameters(index = "0", description = "Project ID")
        String projectId;

        @Override
        public void run() {
            parent.printRules(parent.root().services().database().requireProject(projectId));
        }
    }

    @CommandLine.Command(name = "preset",
            description = "Set file maintenance: REQUIRED (forced), SYNC (ordinary), INITIAL (first provision), DEFAULT (inherit)")
    static final class Preset implements Runnable {
        @CommandLine.ParentCommand
        ProjectPolicyCommand parent;
        @CommandLine.Parameters(index = "0", description = "Project ID")
        String projectId;
        @CommandLine.Parameters(index = "1", description = "Path in the standard source directory")
        String path;
        @CommandLine.Parameters(index = "2", description = "REQUIRED, SYNC, INITIAL or DEFAULT")
        String preset;
        @CommandLine.Option(names = "--directory", description = "Apply to every file below the folder")
        boolean directory;

        @Override
        public void run() {
            ProjectRecord project = parent.policies().applyPresets(projectId, List.of(
                    new ProjectPolicyService.PresetChange(path, directory, parsePreset(preset))));
            parent.printRules(project);
        }
    }

    @CommandLine.Command(name = "cleanup",
            description = "Move files players added to a folder into their backup during updates")
    static final class Cleanup implements Runnable {
        @CommandLine.ParentCommand
        ProjectPolicyCommand parent;
        @CommandLine.Parameters(index = "0", description = "Project ID")
        String projectId;
        @CommandLine.Parameters(index = "1", description = "Top-level folder, for example mods")
        String directory;
        @CommandLine.ArgGroup(multiplicity = "1")
        Switch state;
        @CommandLine.Option(names = "--yes", description = "Enable without the confirmation prompt")
        boolean yes;

        static final class Switch {
            @CommandLine.Option(names = "--on", required = true) boolean on;
            @CommandLine.Option(names = "--off", required = true) boolean off;
        }

        @Override
        public void run() {
            if (state.on) {
                Confirmations.require(parent.root(), yes, "Files players add to " + directory
                        + "/ will be moved into their backup during updates. Continue?");
            }
            parent.printRules(parent.policies().setCleanup(projectId, directory, state.on));
        }
    }

    @CommandLine.Command(name = "group", description = "Create an optional group, or edit one with --id")
    static final class Group implements Runnable {
        @CommandLine.ParentCommand
        ProjectPolicyCommand parent;
        @CommandLine.Parameters(index = "0", description = "Project ID")
        String projectId;
        @CommandLine.Option(names = "--id", description = "Existing group to edit")
        String groupId;
        @CommandLine.Option(names = "--title", required = true, description = "Name players see")
        String title;
        @CommandLine.Option(names = "--description", defaultValue = "")
        String description;
        @CommandLine.Option(names = "--default-off", description = "Players who never chose do not install it")
        boolean defaultOff;

        @Override
        public void run() {
            OptionalGroupRule group = parent.policies().defineOptionalGroup(
                    projectId, groupId, title, description, !defaultOff);
            if (!parent.root().jsonOutput) {
                parent.root().out().println("可选内容已保存：" + group.title() + " [" + group.id() + "]");
            }
            parent.printRules(parent.root().services().database().requireProject(projectId));
        }
    }

    @CommandLine.Command(name = "group-delete", description = "Delete an optional group")
    static final class GroupDelete implements Runnable {
        @CommandLine.ParentCommand
        ProjectPolicyCommand parent;
        @CommandLine.Parameters(index = "0", description = "Project ID")
        String projectId;
        @CommandLine.Parameters(index = "1", description = "Group ID")
        String groupId;
        @CommandLine.Option(names = "--yes")
        boolean yes;

        @Override
        public void run() {
            Confirmations.require(parent.root(), yes, "Delete optional group " + groupId
                    + "? Its files become normal content for every player.");
            parent.printRules(parent.policies().deleteOptionalGroup(projectId, groupId));
        }
    }

    @CommandLine.Command(name = "group-members",
            description = "Add files or folders to an optional group, or take them out")
    static final class GroupMembers implements Runnable {
        @CommandLine.ParentCommand
        ProjectPolicyCommand parent;
        @CommandLine.Parameters(index = "0", description = "Project ID")
        String projectId;
        @CommandLine.Parameters(index = "1", description = "Group ID")
        String groupId;
        @CommandLine.Parameters(index = "2..*", arity = "0..*", description = "Paths in the source directory")
        List<String> paths = new ArrayList<>();
        @CommandLine.ArgGroup(multiplicity = "1")
        Direction direction;
        @CommandLine.Option(names = "--directory", description = "The paths are folders")
        boolean directory;
        @CommandLine.Option(names = "--mod-id", description = "Take a mod out by its mod ID")
        List<String> modIds = new ArrayList<>();

        static final class Direction {
            @CommandLine.Option(names = "--add", required = true) boolean add;
            @CommandLine.Option(names = "--remove", required = true) boolean remove;
        }

        @Override
        public void run() {
            List<ProjectPolicyService.GroupMember> members = new ArrayList<>();
            paths.forEach(path -> members.add(new ProjectPolicyService.GroupMember(path, directory)));
            if (!modIds.isEmpty() && direction.add) {
                throw new ManagementException("--mod-id 只能与 --remove 一起使用；加入模组时请写 jar 路径");
            }
            modIds.forEach(id -> members.add(new ProjectPolicyService.GroupMember(null, false, id)));
            parent.printRules(parent.policies().setGroupMembers(projectId, groupId, members, direction.add));
        }
    }

    @CommandLine.Command(name = "history", description = "List every published version of every file")
    static final class History implements Runnable {
        @CommandLine.ParentCommand
        ProjectPolicyCommand parent;
        @CommandLine.Parameters(index = "0", description = "Project ID")
        String projectId;
        @CommandLine.Parameters(index = "1", arity = "0..1", description = "Only paths containing this text")
        String filter;

        @Override
        public void run() {
            var history = parent.policies().history(projectId).stream()
                    .filter(file -> filter == null
                            || file.path().toLowerCase(Locale.ROOT).contains(filter.toLowerCase(Locale.ROOT)))
                    .toList();
            if (parent.root().jsonOutput) {
                parent.root().printJson(history);
                return;
            }
            var out = parent.root().out();
            history.forEach(file -> {
                out.println(file.path());
                file.versions().forEach(version -> out.printf("  %s  %-12s %s → %s%s%s%n",
                        version.sha256().substring(0, 12),
                        version.version() == null ? "" : version.version(),
                        version.firstRelease(), version.lastRelease(),
                        version.currentlyPublished() ? "  当前发布" : "",
                        version.withdrawnBy() == null ? "" : "  已撤回（" + version.withdrawnBy() + "）"));
            });
            if (history.isEmpty()) out.println("没有符合条件的发布记录");
        }
    }

    @CommandLine.Command(name = "withdraw",
            description = "Withdraw historical versions from every player (path=contentPrefix or path=version)")
    static final class Withdraw implements Runnable {
        @CommandLine.ParentCommand
        ProjectPolicyCommand parent;
        @CommandLine.Parameters(index = "0", description = "Project ID")
        String projectId;
        @CommandLine.Parameters(index = "1..*", arity = "1..*", description = "path=contentPrefix or path=version")
        List<String> versions;
        @CommandLine.Option(names = "--reason", required = true, description = "Shown to players")
        String reason;
        @CommandLine.Option(names = "--yes")
        boolean yes;

        @Override
        public void run() {
            var references = parent.resolveVersions(projectId, versions);
            Confirmations.require(parent.root(), yes, "Withdraw " + references.size()
                    + " version(s) from every player with the next release?");
            var withdrawal = parent.policies().withdraw(projectId, reason, references);
            if (!parent.root().jsonOutput) {
                parent.root().out().println("已撤回：" + withdrawal.id());
            }
            parent.printRules(parent.root().services().database().requireProject(projectId));
        }
    }

    @CommandLine.Command(name = "withdrawal-revoke", description = "Stop a withdrawal")
    static final class WithdrawalRevoke implements Runnable {
        @CommandLine.ParentCommand
        ProjectPolicyCommand parent;
        @CommandLine.Parameters(index = "0", description = "Project ID")
        String projectId;
        @CommandLine.Parameters(index = "1", description = "Withdrawal ID")
        String withdrawalId;

        @Override
        public void run() {
            parent.printRules(parent.policies().revokeWithdrawal(projectId, withdrawalId));
        }
    }

    @CommandLine.Command(name = "correct",
            description = "Put the currently published file on players' machines (KNOWN_BAD or ONCE)")
    static final class Correct implements Runnable {
        @CommandLine.ParentCommand
        ProjectPolicyCommand parent;
        @CommandLine.Parameters(index = "0", description = "Project ID")
        String projectId;
        @CommandLine.Parameters(index = "1", description = "Published file path")
        String path;
        @CommandLine.Option(names = "--mode", required = true, description = "KNOWN_BAD or ONCE")
        CorrectionMode mode;
        @CommandLine.Option(names = "--bad", description = "Content prefix of a known bad version (KNOWN_BAD)")
        List<String> bad = new ArrayList<>();
        @CommandLine.Option(names = "--reason", required = true, description = "Shown to players")
        String reason;
        @CommandLine.Option(names = "--yes")
        boolean yes;

        @Override
        public void run() {
            List<String> badSha256 = parent.resolveVersions(projectId,
                    bad.stream().map(prefix -> path + "=" + prefix).toList()).stream()
                    .map(ProjectPolicyService.VersionReference::sha256).toList();
            Confirmations.require(parent.root(), yes, "Correct " + path + " on players' machines ("
                    + mode + ") with the next release?");
            parent.policies().correct(projectId, path, mode, reason, badSha256);
            parent.printRules(parent.root().services().database().requireProject(projectId));
        }
    }

    @CommandLine.Command(name = "correction-revoke", description = "Stop a correction")
    static final class CorrectionRevoke implements Runnable {
        @CommandLine.ParentCommand
        ProjectPolicyCommand parent;
        @CommandLine.Parameters(index = "0", description = "Project ID")
        String projectId;
        @CommandLine.Parameters(index = "1", description = "Correction ID")
        String correctionId;

        @Override
        public void run() {
            parent.printRules(parent.policies().revokeCorrection(projectId, correctionId));
        }
    }
}
