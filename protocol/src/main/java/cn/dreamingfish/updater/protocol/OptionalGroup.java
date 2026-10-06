package cn.dreamingfish.updater.protocol;

/**
 * A set of published files that players can switch on or off together, such
 * as shaders or other visual mods. Players who never touched the switch follow
 * {@code defaultInstall}.
 */
public record OptionalGroup(String id, String title, String description, boolean defaultInstall) {
    public OptionalGroup {
        description = description == null ? "" : description;
    }
}
