package cn.dreamingfish.updater.management;

import cn.dreamingfish.updater.protocol.Correction;
import cn.dreamingfish.updater.protocol.MaintenancePreset;
import cn.dreamingfish.updater.protocol.ManagedPaths;
import cn.dreamingfish.updater.protocol.PathSafety;
import cn.dreamingfish.updater.protocol.ProtocolException;
import cn.dreamingfish.updater.protocol.Withdrawal;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Everything the owner decides about how a project's files are maintained on
 * player instances.
 *
 * <p>{@code legacyForcedSyncDirectories} and {@code legacyForcedSyncFiles}
 * only read configurations written by earlier management versions. They are
 * migrated on load: a forced directory becomes a cleanup directory whose files
 * are {@link MaintenancePreset#REQUIRED}, and a forced file becomes a required
 * file. Both lists are always empty afterwards.
 */
public record ProjectRules(
        List<FileRule> rules,
        @JsonProperty("forcedSyncDirectories") List<String> legacyForcedSyncDirectories,
        @JsonProperty("forcedSyncFiles") List<String> legacyForcedSyncFiles,
        List<PresetRule> presets,
        List<String> cleanupDirectories,
        List<OptionalGroupRule> optionalGroups,
        List<Withdrawal> withdrawals,
        List<Correction> corrections,
        boolean simplified
) {
    private static final String GROUP_ID = "[a-z0-9][a-z0-9._-]{0,63}";
    private static final String MOD_ID = "[A-Za-z0-9_.-]{1,128}";

    public ProjectRules {
        rules = normalizeRules(rules);
        Map<String, PresetRule> presetMap = new LinkedHashMap<>();
        for (PresetRule preset : presets == null ? List.<PresetRule>of() : presets) {
            PresetRule normalized = normalizePreset(preset);
            presetMap.put(presetKey(normalized.path(), normalized.directory()), normalized);
        }
        List<String> cleanup = new ArrayList<>(normalizeDirectories(cleanupDirectories,
                "Invalid cleanup directory"));
        for (String directory : normalizeDirectories(legacyForcedSyncDirectories,
                "Invalid forced sync directory")) {
            if (directory.indexOf('/') >= 0) {
                throw new ManagementException("Forced sync directories must be top-level: " + directory);
            }
            if (cleanup.stream().noneMatch(existing -> existing.equalsIgnoreCase(directory))) {
                cleanup.add(directory);
            }
            presetMap.putIfAbsent(presetKey(directory, true),
                    new PresetRule(directory, true, MaintenancePreset.REQUIRED));
        }
        for (String file : normalizeFiles(legacyForcedSyncFiles, "Invalid forced sync file")) {
            presetMap.putIfAbsent(presetKey(file, false),
                    new PresetRule(file, false, MaintenancePreset.REQUIRED));
        }
        cleanup.sort(String::compareTo);
        cleanupDirectories = List.copyOf(cleanup);
        List<PresetRule> sortedPresets = new ArrayList<>(presetMap.values());
        sortedPresets.sort(Comparator.comparing(PresetRule::path).thenComparing(PresetRule::directory));
        presets = List.copyOf(sortedPresets);
        legacyForcedSyncDirectories = List.of();
        legacyForcedSyncFiles = List.of();
        optionalGroups = normalizeGroups(optionalGroups);
        withdrawals = withdrawals == null ? List.of() : List.copyOf(withdrawals);
        corrections = corrections == null ? List.of() : List.copyOf(corrections);
    }

    public ProjectRules(List<FileRule> rules, List<String> forcedSyncDirectories,
                        List<String> forcedSyncFiles) {
        this(rules, forcedSyncDirectories, forcedSyncFiles, List.of(), List.of(), List.of(),
                List.of(), List.of());
    }

    public ProjectRules(List<FileRule> rules, List<String> forcedSyncDirectories,
                        List<String> forcedSyncFiles, List<PresetRule> presets,
                        List<String> cleanupDirectories, List<OptionalGroupRule> optionalGroups,
                        List<Withdrawal> withdrawals, List<Correction> corrections) {
        this(rules, forcedSyncDirectories, forcedSyncFiles, presets, cleanupDirectories,
                optionalGroups, withdrawals, corrections, true);
    }

    public ProjectRules asSimplified() {
        return new ProjectRules(rules, List.of(), List.of(), presets, cleanupDirectories,
                optionalGroups, withdrawals, corrections, true);
    }

    public ProjectRules(List<FileRule> rules, List<String> forcedSyncDirectories) {
        this(rules, forcedSyncDirectories, List.of());
    }

    public ProjectRules(List<FileRule> rules) {
        this(rules, List.of(), List.of());
    }

    public static ProjectRules defaults() {
        return new ProjectRules(List.of(
                new FileRule(".dreamingfish-bootstrap/**", RuleAction.EXCLUDE),
                new FileRule("DreamingFishUpdater/**", RuleAction.EXCLUDE),
                new FileRule("logs/**", RuleAction.EXCLUDE),
                new FileRule("crash-reports/**", RuleAction.EXCLUDE),
                new FileRule("saves/**", RuleAction.EXCLUDE),
                new FileRule("screenshots/**", RuleAction.EXCLUDE)
        ), List.of(), List.of());
    }

    // ---- resolution --------------------------------------------------------------

    /** The most specific preset rule covering a path, if any. */
    public Optional<PresetRule> presetRuleFor(String path) {
        String folded = ManagedPaths.fold(path);
        PresetRule best = null;
        for (PresetRule rule : presets) {
            String root = ManagedPaths.fold(rule.path());
            if (!rule.directory()) {
                if (root.equals(folded)) return Optional.of(rule);
                continue;
            }
            if (folded.startsWith(root + "/")
                    && (best == null || rule.path().length() > best.path().length())) {
                best = rule;
            }
        }
        return Optional.ofNullable(best);
    }

    public MaintenancePreset presetFor(String path) {
        return presetRuleFor(path).map(PresetRule::preset).orElse(MaintenancePreset.SYNC);
    }

    /**
     * The preset a published file gets. The most specific rule wins: a mod or
     * file chosen as optional content beats a folder-wide "required" (the rest
     * of the folder stays required), while an optional folder at the same or a
     * higher level than a required rule is a conflict the owner must resolve.
     */
    public MaintenancePreset effectivePreset(String path, String componentId) {
        PresetRule rule = presetRuleFor(path).orElse(null);
        if (rule == null) return MaintenancePreset.SYNC;
        if (rule.preset() != MaintenancePreset.REQUIRED
                || optionalMembership(path, componentId) == null) {
            return rule.preset();
        }
        throw new ManagementException("“" + path + "”设为了必需同步，不能同时作为可选内容；"
                + "请先把它改为其他维护方式，或移出可选内容。");
    }

    /** Whether optional membership is more specific than the folder-wide "required" covering a path. */
    public boolean optionalOverridesRequired(String path, String componentId) {
        PresetRule rule = presetRuleFor(path).orElse(null);
        if (rule == null || !rule.directory() || rule.preset() != MaintenancePreset.REQUIRED) return false;
        String membership = optionalMembership(path, componentId);
        return membership != null
                && (membership.isEmpty() || membership.length() > rule.path().length());
    }

    /**
     * How a path joins optional content: {@code ""} by mod ID or exact file, the
     * deepest matching group directory otherwise, {@code null} when not optional.
     */
    private String optionalMembership(String path, String componentId) {
        for (OptionalGroupRule group : optionalGroups) {
            if (componentId != null && ManagedPaths.isModJar(path)
                    && group.modIds().stream().anyMatch(id -> id.equalsIgnoreCase(componentId))) {
                return "";
            }
            if (group.files().stream().anyMatch(file -> file.equalsIgnoreCase(path))) return "";
        }
        String deepest = null;
        for (OptionalGroupRule group : optionalGroups) {
            for (String directory : group.directories()) {
                if (ManagedPaths.isBelow(path, directory)
                        && (deepest == null || directory.length() > deepest.length())) {
                    deepest = directory;
                }
            }
        }
        return deepest;
    }

    public boolean insideCleanupDirectory(String path) {
        return cleanupDirectories.stream().anyMatch(directory -> ManagedPaths.isBelow(path, directory));
    }

    /**
     * The optional groups claiming a file. Membership by mod ID takes precedence;
     * otherwise exact files beat directories. Ambiguity is reported by the scanner.
     */
    public List<OptionalGroupRule> groupsFor(String path, String componentId) {
        List<OptionalGroupRule> byMod = new ArrayList<>();
        if (componentId != null && ManagedPaths.isModJar(path)) {
            for (OptionalGroupRule group : optionalGroups) {
                if (group.modIds().stream().anyMatch(id -> id.equalsIgnoreCase(componentId))) {
                    byMod.add(group);
                }
            }
            if (!byMod.isEmpty()) return byMod;
        }
        List<OptionalGroupRule> byFile = new ArrayList<>();
        for (OptionalGroupRule group : optionalGroups) {
            if (group.files().stream().anyMatch(file -> file.equalsIgnoreCase(path))) byFile.add(group);
        }
        if (!byFile.isEmpty()) return byFile;
        List<OptionalGroupRule> byDirectory = new ArrayList<>();
        int depth = -1;
        for (OptionalGroupRule group : optionalGroups) {
            for (String directory : group.directories()) {
                if (!ManagedPaths.isBelow(path, directory)) continue;
                if (directory.length() > depth) {
                    byDirectory.clear();
                    depth = directory.length();
                }
                if (directory.length() == depth && !byDirectory.contains(group)) byDirectory.add(group);
            }
        }
        return byDirectory;
    }

    public Optional<OptionalGroupRule> optionalGroup(String id) {
        return optionalGroups.stream().filter(group -> group.id().equals(id)).findFirst();
    }

    /** Directories whose cleanup is combined with required files: the historical "forced sync" view. */
    public List<String> lockedCleanupDirectories() {
        return cleanupDirectories.stream()
                .filter(directory -> presets.stream().anyMatch(rule -> rule.directory()
                        && rule.path().equalsIgnoreCase(directory)
                        && rule.preset() == MaintenancePreset.REQUIRED))
                .toList();
    }

    /** Exact files marked required: the historical "forced sync file" view. */
    public List<String> requiredFiles() {
        return presets.stream()
                .filter(rule -> !rule.directory() && rule.preset() == MaintenancePreset.REQUIRED)
                .map(PresetRule::path)
                .toList();
    }

    // ---- edits -------------------------------------------------------------------

    public ProjectRules withRules(List<FileRule> replacement) {
        return new ProjectRules(replacement, List.of(), List.of(), presets, cleanupDirectories,
                optionalGroups, withdrawals, corrections, simplified);
    }

    /** Sets or clears ({@code preset == null}) the rule for one file or directory. */
    public ProjectRules withPreset(String path, boolean directory, MaintenancePreset preset) {
        String normalized = normalizePath(path, "Invalid preset path");
        List<PresetRule> updated = new ArrayList<>();
        for (PresetRule rule : presets) {
            if (rule.directory() == directory && rule.path().equalsIgnoreCase(normalized)) continue;
            updated.add(rule);
        }
        if (preset != null) updated.add(new PresetRule(normalized, directory, preset));
        return withPresets(updated);
    }

    public ProjectRules withPresets(Collection<PresetRule> replacement) {
        return new ProjectRules(rules, List.of(), List.of(), List.copyOf(replacement),
                cleanupDirectories, optionalGroups, withdrawals, corrections, simplified);
    }

    public ProjectRules withCleanupDirectories(List<String> directories) {
        return new ProjectRules(rules, List.of(), List.of(), presets, directories,
                optionalGroups, withdrawals, corrections, simplified);
    }

    public ProjectRules withOptionalGroups(List<OptionalGroupRule> groups) {
        return new ProjectRules(rules, List.of(), List.of(), presets, cleanupDirectories,
                groups, withdrawals, corrections, simplified);
    }

    public ProjectRules withWithdrawals(List<Withdrawal> replacement) {
        return new ProjectRules(rules, List.of(), List.of(), presets, cleanupDirectories,
                optionalGroups, replacement, corrections, simplified);
    }

    public ProjectRules withCorrections(List<Correction> replacement) {
        return new ProjectRules(rules, List.of(), List.of(), presets, cleanupDirectories,
                optionalGroups, withdrawals, replacement, simplified);
    }

    /**
     * Historical "forced sync directories" setting: the listed directories
     * become locked cleanup directories and directories no longer listed lose
     * both their cleanup and their directory-level required preset.
     */
    public ProjectRules withLegacyForcedSyncDirectories(List<String> directories) {
        List<String> requested = normalizeDirectories(directories, "Invalid forced sync directory");
        for (String directory : requested) {
            if (directory.indexOf('/') >= 0) {
                throw new ManagementException("Forced sync directories must be top-level: " + directory);
            }
        }
        Set<String> wanted = folded(requested);
        List<PresetRule> updated = new ArrayList<>();
        for (PresetRule rule : presets) {
            boolean previouslyLocked = rule.directory() && rule.preset() == MaintenancePreset.REQUIRED
                    && cleanupDirectories.stream().anyMatch(dir -> dir.equalsIgnoreCase(rule.path()));
            if (previouslyLocked && !wanted.contains(ManagedPaths.fold(rule.path()))) continue;
            if (rule.directory() && wanted.contains(ManagedPaths.fold(rule.path()))) continue;
            updated.add(rule);
        }
        for (String directory : requested) {
            updated.add(new PresetRule(directory, true, MaintenancePreset.REQUIRED));
        }
        List<String> cleanup = new ArrayList<>();
        for (String directory : cleanupDirectories) {
            boolean locked = lockedCleanupDirectories().stream().anyMatch(dir -> dir.equalsIgnoreCase(directory));
            if (!locked || wanted.contains(ManagedPaths.fold(directory))) cleanup.add(directory);
        }
        for (String directory : requested) {
            if (cleanup.stream().noneMatch(dir -> dir.equalsIgnoreCase(directory))) cleanup.add(directory);
        }
        return new ProjectRules(rules, List.of(), List.of(), updated, cleanup, optionalGroups,
                withdrawals, corrections, simplified);
    }

    /** Historical "forced sync files" setting: exactly these files are required. */
    public ProjectRules withLegacyForcedSyncFiles(List<String> files) {
        List<String> requested = normalizeFiles(files, "Invalid forced sync file");
        Set<String> wanted = folded(requested);
        List<PresetRule> updated = new ArrayList<>();
        for (PresetRule rule : presets) {
            if (!rule.directory() && rule.preset() == MaintenancePreset.REQUIRED
                    && !wanted.contains(ManagedPaths.fold(rule.path()))) {
                continue;
            }
            if (!rule.directory() && wanted.contains(ManagedPaths.fold(rule.path()))) continue;
            updated.add(rule);
        }
        for (String file : requested) updated.add(new PresetRule(file, false, MaintenancePreset.REQUIRED));
        return withPresets(updated);
    }

    // ---- normalization -----------------------------------------------------------

    private static List<FileRule> normalizeRules(List<FileRule> source) {
        if (source == null || source.isEmpty()) return List.of();
        List<FileRule> normalized = new ArrayList<>();
        for (FileRule rule : source) {
            // DEFAULT used to be injected for options.txt and servers.dat. It is
            // retained in RuleAction only long enough to read and migrate old data.
            if (rule.action() == RuleAction.LEGACY_DEFAULT) continue;
            normalized.add(rule);
        }
        return List.copyOf(normalized);
    }

    private static PresetRule normalizePreset(PresetRule rule) {
        if (rule == null || rule.preset() == null) {
            throw new ManagementException("Maintenance preset rule is incomplete");
        }
        return new PresetRule(normalizePath(rule.path(), "Invalid preset path"),
                rule.directory(), rule.preset() == MaintenancePreset.DEFAULT_CONFIG
                ? MaintenancePreset.INITIAL : rule.preset());
    }

    private static List<OptionalGroupRule> normalizeGroups(List<OptionalGroupRule> source) {
        if (source == null || source.isEmpty()) return List.of();
        List<OptionalGroupRule> result = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (OptionalGroupRule group : source) {
            if (group == null || group.id() == null || !group.id().matches(GROUP_ID)
                    || !ids.add(group.id())) {
                throw new ManagementException("Invalid or duplicate optional group ID");
            }
            String title = group.title() == null ? "" : group.title().trim();
            if (title.isEmpty() || title.length() > 60
                    || title.chars().anyMatch(Character::isISOControl)) {
                throw new ManagementException("Optional group title must be 1-60 characters");
            }
            if (group.description().length() > 300) {
                throw new ManagementException("Optional group description is too long");
            }
            Set<String> modIds = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            for (String modId : group.modIds()) {
                if (modId == null || !modId.trim().matches(MOD_ID)) {
                    throw new ManagementException("Invalid mod ID in optional group " + group.id());
                }
                modIds.add(modId.trim());
            }
            result.add(new OptionalGroupRule(group.id(), title, group.description(),
                    group.defaultInstall(), List.copyOf(modIds),
                    normalizeFiles(group.files(), "Invalid optional group file"),
                    normalizeDirectories(group.directories(), "Invalid optional group directory")));
        }
        return List.copyOf(result);
    }

    private static List<String> normalizeDirectories(List<String> source, String message) {
        return normalizeFiles(source, message);
    }

    private static List<String> normalizeFiles(List<String> source, String message) {
        if (source == null || source.isEmpty()) return List.of();
        List<String> normalized = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String value : source) {
            String path = normalizePath(value, message);
            if (seen.add(path.toLowerCase(Locale.ROOT))) normalized.add(path);
        }
        normalized.sort(String::compareTo);
        return List.copyOf(normalized);
    }

    private static String normalizePath(String value, String message) {
        try {
            return PathSafety.normalizeManifestPath(value == null ? null : value.trim());
        } catch (ProtocolException e) {
            throw new ManagementException(message + ": " + value, e);
        }
    }

    private static String presetKey(String path, boolean directory) {
        return (directory ? "d:" : "f:") + ManagedPaths.fold(path);
    }

    private static Set<String> folded(List<String> values) {
        Set<String> result = new HashSet<>();
        values.forEach(value -> result.add(ManagedPaths.fold(value)));
        return result;
    }
}
