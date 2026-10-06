package cn.dreamingfish.updater.protocol;

public record ModMetadata(String componentId, String displayName, String version) {
    public ModMetadata(String componentId, String displayName) {
        this(componentId, displayName, null);
    }

    public ModMetadata {
        if (componentId == null || componentId.isBlank()) {
            throw new IllegalArgumentException("Mod component ID is missing");
        }
        componentId = componentId.trim();
        displayName = displayName == null || displayName.isBlank()
                ? componentId
                : displayName.trim();
        version = version == null || version.isBlank() ? null : version.trim();
    }
}
