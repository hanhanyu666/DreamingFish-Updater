package cn.dreamingfish.updater.management;

import java.util.List;

/**
 * An owner-defined optional group. Mods join by mod ID so renamed updates stay
 * in the group; other content joins by exact file or directory.
 */
public record OptionalGroupRule(String id, String title, String description,
                                boolean defaultInstall, List<String> modIds,
                                List<String> files, List<String> directories) {
    public OptionalGroupRule {
        description = description == null ? "" : description.trim();
        modIds = modIds == null ? List.of() : List.copyOf(modIds);
        files = files == null ? List.of() : List.copyOf(files);
        directories = directories == null ? List.of() : List.copyOf(directories);
    }
}
