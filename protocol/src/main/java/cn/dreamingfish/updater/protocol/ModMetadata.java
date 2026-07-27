package cn.dreamingfish.updater.protocol;

public record ModMetadata(String componentId, String displayName) {
    public ModMetadata {
        if (componentId == null || componentId.isBlank()) {
            throw new IllegalArgumentException("Mod component ID is missing");
        }
        componentId = componentId.trim();
        displayName = displayName == null || displayName.isBlank()
                ? componentId
                : displayName.trim();
    }
}
