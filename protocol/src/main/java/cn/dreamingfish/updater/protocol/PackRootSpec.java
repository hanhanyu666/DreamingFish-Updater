package cn.dreamingfish.updater.protocol;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * The outer pack directory relative to a game instance. A signed release may carry it, but players
 * accept it only when their actual folders match {@code layout}, the expected names from the root
 * down to the instance ({@code *} matches any one folder).
 */
public record PackRootSpec(String id, String relativeRoot, List<String> layout) {
    public static final String ANY = "*";

    public PackRootSpec {
        if (id == null || !id.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"))
            throw new ProtocolException("Invalid pack root identity");
        if (relativeRoot == null) throw new ProtocolException("Missing pack root position");
        relativeRoot = relativeRoot.replace('\\', '/').replaceAll("/+$", "");
        if (!relativeRoot.matches("\\.\\.(?:/\\.\\.){0,7}"))
            throw new ProtocolException("整合包起始目录必须是实例上方 1 至 8 级，例如 ../../..");
        if (layout != null) {
            layout = List.copyOf(layout);
            if (layout.size() != relativeRoot.split("/").length)
                throw new ProtocolException("目录结构的层数必须与向上的级数一致");
            for (String segment : layout) {
                if (segment == null || segment.isBlank() || segment.length() > 64 || segment.equals(".") || segment.equals("..")
                        || segment.contains("/") || segment.contains("\\") || segment.contains(":")
                        || segment.contains(ANY) && !segment.equals(ANY))
                    throw new ProtocolException("目录结构只能填写文件夹名称或 *：" + segment);
            }
        }
    }

    public PackRootSpec(String id, String relativeRoot) { this(id, relativeRoot, null); }

    public String prefix() { return ContentRoots.PREFIX + id + "/"; }

    public int levels() { return relativeRoot.split("/").length; }

    /** The same grant regardless of how strictly the layout is described. */
    public boolean sameLocation(PackRootSpec other) {
        return other != null && id.equals(other.id) && relativeRoot.equals(other.relativeRoot);
    }

    public PackRootSpec withDefaultLayout() {
        return layout != null ? this : new PackRootSpec(id, relativeRoot, defaultLayout(levels()));
    }

    /** Version-isolated instances: {@code <pack>/.minecraft/versions/<instance>}. */
    public static List<String> defaultLayout(int levels) {
        List<String> known = List.of(".minecraft", "versions", ANY);
        List<String> layout = new ArrayList<>();
        for (int i = known.size(); i < levels; i++) layout.add(ANY);
        layout.addAll(known.subList(Math.max(0, known.size() - levels), known.size()));
        return List.copyOf(layout);
    }

    /** Whether the folder names from the root down to the instance match the expected layout. */
    public boolean matchesLayout(List<String> actual) {
        if (layout == null) return true;
        if (actual.size() != layout.size()) return false;
        for (int i = 0; i < layout.size(); i++) {
            String expected = layout.get(i);
            if (!expected.equals(ANY) && !Objects.equals(expected.toLowerCase(Locale.ROOT), actual.get(i).toLowerCase(Locale.ROOT)))
                return false;
        }
        return true;
    }
}
