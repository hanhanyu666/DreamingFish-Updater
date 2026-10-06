package cn.dreamingfish.updater.player;

import cn.dreamingfish.updater.protocol.JsonCodec;
import cn.dreamingfish.updater.protocol.MaintenanceModel;
import cn.dreamingfish.updater.protocol.ManifestFile;
import cn.dreamingfish.updater.protocol.OptionalGroup;
import cn.dreamingfish.updater.protocol.PathSafety;
import cn.dreamingfish.updater.protocol.ReleaseManifest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The player's explicit optional group switches. Groups the player never
 * touched follow the default the owner publishes, so changing a default later
 * still reaches every player who did not choose.
 */
final class LocalOptionManager {
    static final int SCHEMA_VERSION = 1;

    record Preferences(int schemaVersion, long revision, Map<String, Boolean> choices) {
        Preferences {
            choices = choices == null ? Map.of() : Map.copyOf(choices);
        }
    }

    record Snapshot(long revision, Map<String, Boolean> choices) {
    }

    private final Path preferencesFile;
    private final JsonCodec json = new JsonCodec();

    LocalOptionManager(Path playerHome) {
        preferencesFile = playerHome.toAbsolutePath().normalize()
                .resolve("state/local-option-preferences.json");
    }

    synchronized Snapshot snapshot() throws IOException {
        Preferences preferences = load();
        return new Snapshot(preferences.revision(), preferences.choices());
    }

    /** Records an explicit choice; {@code null} returns the group to its published default. */
    synchronized void setChoice(String groupId, Boolean enabled) throws IOException {
        if (groupId == null || !groupId.matches("[a-z0-9][a-z0-9._-]{0,63}")) {
            throw new IOException("Invalid optional group");
        }
        Preferences current = load();
        Map<String, Boolean> choices = new TreeMap<>(current.choices());
        Boolean previous = enabled == null ? choices.remove(groupId) : choices.put(groupId, enabled);
        if (java.util.Objects.equals(previous, enabled)) return;
        save(new Preferences(SCHEMA_VERSION, current.revision() + 1, choices));
    }

    synchronized void restoreDefaults() throws IOException {
        Preferences current = load();
        if (current.choices().isEmpty()) return;
        save(new Preferences(SCHEMA_VERSION, current.revision() + 1, Map.of()));
    }

    /** Groups of a release with their effective state, for the player window. */
    List<OptionalGroupView> view(ReleaseManifest release, Map<String, Boolean> choices) {
        if (release == null) return List.of();
        MaintenanceModel model = MaintenanceModel.of(release);
        List<OptionalGroupView> views = new ArrayList<>();
        for (OptionalGroup group : model.optionalGroups()) {
            List<String> members = new ArrayList<>();
            for (ManifestFile file : model.files()) {
                if (!group.id().equals(file.optionalGroup())) continue;
                members.add(file.displayName() != null ? file.displayName() : fileName(file.path()));
            }
            Boolean explicit = choices.get(group.id());
            boolean enabled = explicit == null ? group.defaultInstall() : explicit;
            views.add(new OptionalGroupView(group.id(), group.title(), group.description(),
                    group.defaultInstall(), enabled, explicit != null, List.copyOf(members)));
        }
        return List.copyOf(views);
    }

    private Preferences load() throws IOException {
        PathSafety.assertSafePathTree(preferencesFile);
        if (!Files.exists(preferencesFile, LinkOption.NOFOLLOW_LINKS)) {
            return new Preferences(SCHEMA_VERSION, 0, Map.of());
        }
        if (!Files.isRegularFile(preferencesFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Local option preferences path is unsafe");
        }
        Preferences preferences = json.read(preferencesFile, Preferences.class);
        if (preferences.schemaVersion() != SCHEMA_VERSION || preferences.revision() < 0
                || preferences.choices().size() > 500) {
            throw new IOException("Unsupported local option preferences file");
        }
        for (Map.Entry<String, Boolean> choice : preferences.choices().entrySet()) {
            if (choice.getKey() == null || !choice.getKey().matches("[a-z0-9][a-z0-9._-]{0,63}")
                    || choice.getValue() == null) {
                throw new IOException("Invalid local option preference");
            }
        }
        return preferences;
    }

    private void save(Preferences preferences) throws IOException {
        LocalModTransaction.write(preferencesFile, json.writePretty(preferences));
    }

    private static String fileName(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }
}
