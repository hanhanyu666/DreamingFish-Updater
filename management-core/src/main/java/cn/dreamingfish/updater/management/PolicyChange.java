package cn.dreamingfish.updater.management;

/**
 * One maintenance difference between the latest release and the pending one,
 * shown in the publish preview next to the content changes.
 *
 * @param kind     PRESET, CLEANUP_DIRECTORY, OPTIONAL_GROUP, OPTIONAL_MEMBERSHIP,
 *                 WITHDRAWAL, CORRECTION or CORRECTION_DROPPED
 * @param subject  the path, directory, group or directive concerned
 * @param previous the previous value, or {@code null} when newly added
 * @param current  the pending value, or {@code null} when removed
 */
public record PolicyChange(String kind, String subject, String previous, String current) {
}
