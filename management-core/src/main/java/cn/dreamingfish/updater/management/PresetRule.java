package cn.dreamingfish.updater.management;

import cn.dreamingfish.updater.protocol.MaintenancePreset;

/**
 * An owner-selected maintenance preset for one file or for every file below a
 * directory. The most specific rule wins: an exact file rule beats any
 * directory rule, and a deeper directory beats a shallower one.
 */
public record PresetRule(String path, boolean directory, MaintenancePreset preset) {
}
