package cn.dreamingfish.updater.management;

import cn.dreamingfish.updater.protocol.PathSafety;
import cn.dreamingfish.updater.protocol.ProtocolException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public record ProjectRules(List<FileRule> rules, List<String> forcedSyncDirectories) {
    public ProjectRules {
        rules = rules == null ? List.of() : List.copyOf(rules);
        forcedSyncDirectories = normalizeForcedSyncDirectories(forcedSyncDirectories);
    }

    public ProjectRules(List<FileRule> rules) {
        this(rules, List.of());
    }

    public static ProjectRules defaults() {
        return new ProjectRules(List.of(
                new FileRule(".dreamingfish-bootstrap/**", RuleAction.EXCLUDE),
                new FileRule("DreamingFishUpdater/**", RuleAction.EXCLUDE),
                new FileRule("logs/**", RuleAction.EXCLUDE),
                new FileRule("crash-reports/**", RuleAction.EXCLUDE),
                new FileRule("saves/**", RuleAction.EXCLUDE),
                new FileRule("screenshots/**", RuleAction.EXCLUDE),
                new FileRule("options.txt", RuleAction.DEFAULT),
                new FileRule("servers.dat", RuleAction.DEFAULT)
        ), List.of());
    }

    public ProjectRules withForcedSyncDirectories(List<String> directories) {
        return new ProjectRules(rules, directories);
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
}
