package cn.dreamingfish.updater.management;

import cn.dreamingfish.updater.protocol.PathSafety;
import cn.dreamingfish.updater.protocol.ProtocolException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public record ProjectRules(
        List<FileRule> rules,
        List<String> forcedSyncDirectories,
        List<String> forcedSyncFiles
) {
    public ProjectRules {
        rules = normalizeRules(rules);
        forcedSyncDirectories = normalizeForcedSyncDirectories(forcedSyncDirectories);
        forcedSyncFiles = normalizeForcedSyncFiles(forcedSyncFiles);
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

    public ProjectRules withForcedSyncDirectories(List<String> directories) {
        return new ProjectRules(rules, directories, forcedSyncFiles);
    }

    public ProjectRules withForcedSyncFiles(List<String> files) {
        return new ProjectRules(rules, forcedSyncDirectories, files);
    }

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

    private static List<String> normalizeForcedSyncDirectories(List<String> source) {
        if (source == null || source.isEmpty()) return List.of();
        List<String> normalized = new ArrayList<>();
        Set<String> folded = new HashSet<>();
        for (String value : source) {
            try {
                String directory = PathSafety.normalizeManifestPath(value == null ? null : value.trim());
                if (directory.indexOf('/') >= 0) {
                    throw new ManagementException(
                            "Forced sync directories must be top-level: " + value);
                }
                if (!folded.add(directory.toLowerCase(Locale.ROOT))) {
                    throw new ManagementException("Duplicate forced sync directory: " + value);
                }
                normalized.add(directory);
            } catch (ProtocolException e) {
                throw new ManagementException("Invalid forced sync directory: " + value, e);
            }
        }
        normalized.sort(String::compareTo);
        return List.copyOf(normalized);
    }

    private static List<String> normalizeForcedSyncFiles(List<String> source) {
        if (source == null || source.isEmpty()) return List.of();
        List<String> normalized = new ArrayList<>();
        Set<String> folded = new HashSet<>();
        for (String value : source) {
            try {
                String file = PathSafety.normalizeManifestPath(
                        value == null ? null : value.trim());
                if (!folded.add(file.toLowerCase(Locale.ROOT))) {
                    throw new ManagementException("Duplicate forced sync file: " + value);
                }
                normalized.add(file);
            } catch (ProtocolException e) {
                throw new ManagementException("Invalid forced sync file: " + value, e);
            }
        }
        normalized.sort(String::compareTo);
        return List.copyOf(normalized);
    }
}
