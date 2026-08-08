package cn.dreamingfish.updater.protocol;

/** A published, optional MP3 resource that can be selected in the player UI. */
public record PlayerMusicTrack(
        String id,
        String title,
        String fileName,
        String sha256,
        long size
) {
}
