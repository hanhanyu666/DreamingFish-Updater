package cn.dreamingfish.updater.management.cli;

final class HumanSize {
    private static final String[] UNITS = {"B", "KiB", "MiB", "GiB", "TiB"};

    private HumanSize() {
    }

    static String format(long bytes) {
        double value = bytes;
        int unit = 0;
        while (value >= 1024 && unit < UNITS.length - 1) {
            value /= 1024;
            unit++;
        }
        return unit == 0 ? bytes + " B" : String.format(java.util.Locale.ROOT, "%.1f %s", value, UNITS[unit]);
    }
}
