package cn.dreamingfish.updater.management;

/**
 * Something the owner must know before confirming a release.
 *
 * <ul>
 *   <li>{@code PLAYER_PROGRAM_REQUIRED}: players need {@code subject} or newer;
 *       publishing adapters require an explicit acknowledgement.</li>
 *   <li>{@code CONTENT_MOD_REMOVED}: removing or withdrawing a mod may delete its
 *       blocks and items from players' single-player worlds.</li>
 *   <li>{@code STALE_RULE}: a rule names a file that is no longer in the source directory.</li>
 * </ul>
 */
public record PreviewWarning(String code, String subject, String message) {
    public static final String PLAYER_PROGRAM_REQUIRED = "PLAYER_PROGRAM_REQUIRED";
    public static final String CONTENT_MOD_REMOVED = "CONTENT_MOD_REMOVED";
    public static final String STALE_RULE = "STALE_RULE";
}
