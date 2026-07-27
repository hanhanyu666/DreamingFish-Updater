package cn.dreamingfish.updater.player;

import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.function.Consumer;

final class BackgroundMusic implements AutoCloseable {
    private static final String RESOURCE = "audio/bg_music.mp3";
    private static final double VOLUME = 0.42;

    enum State {
        PLAYING,
        PAUSED,
        UNAVAILABLE
    }

    private final Path mutedMarker;
    private final Consumer<State> stateListener;
    private final Consumer<Throwable> errorListener;
    private MediaPlayer player;
    private boolean muted;
    private boolean failed;
    private boolean closed;

    BackgroundMusic(Path mutedMarker, Consumer<State> stateListener,
                    Consumer<Throwable> errorListener) {
        this.mutedMarker = mutedMarker;
        this.stateListener = stateListener;
        this.errorListener = errorListener;
    }

    void start() {
        try {
            failed = false;
            closed = false;
            muted = mutedMarker != null && Files.isRegularFile(mutedMarker, LinkOption.NOFOLLOW_LINKS);
            URL resource = BackgroundMusic.class.getResource(RESOURCE);
            if (resource == null) {
                throw new IllegalStateException("Bundled background music is missing");
            }
            Media media = new Media(resource.toExternalForm());
            MediaPlayer created = new MediaPlayer(media);
            player = created;
            created.setCycleCount(MediaPlayer.INDEFINITE);
            created.setVolume(VOLUME);
            media.setOnError(() -> unavailable(media.getError()));
            created.setOnError(() -> unavailable(created.getError()));
            created.setOnHalted(() -> unavailable(created.getError()));
            created.setOnReady(() -> {
                notifyState(created, State.PAUSED);
                if (isActive(created) && !muted) created.play();
            });
            created.setOnPlaying(() -> notifyState(created, State.PLAYING));
            created.setOnPaused(() -> notifyState(created, State.PAUSED));
            created.setOnStopped(() -> notifyState(created, State.PAUSED));
        } catch (RuntimeException e) {
            unavailable(e);
        }
    }

    void toggle() {
        if (player == null || failed || closed) return;
        if (player.getStatus() == MediaPlayer.Status.PLAYING) {
            muted = true;
            persistMutedPreference();
            player.pause();
        } else {
            muted = false;
            persistMutedPreference();
            player.play();
        }
    }

    private void persistMutedPreference() {
        if (mutedMarker == null) return;
        try {
            if (muted) {
                Files.createDirectories(mutedMarker.getParent());
                Files.writeString(mutedMarker, "muted\n", StandardCharsets.US_ASCII);
            } else {
                Files.deleteIfExists(mutedMarker);
            }
        } catch (IOException e) {
            errorListener.accept(e);
        }
    }

    private void unavailable(Throwable error) {
        if (failed || closed) return;
        failed = true;
        MediaPlayer current = player;
        player = null;
        if (current != null) current.dispose();
        stateListener.accept(State.UNAVAILABLE);
        errorListener.accept(error == null
                ? new IllegalStateException("Background music playback failed")
                : error);
    }

    private boolean isActive(MediaPlayer expected) {
        return !failed && !closed && player == expected;
    }

    private void notifyState(MediaPlayer expected, State state) {
        if (isActive(expected)) stateListener.accept(state);
    }

    @Override
    public void close() {
        closed = true;
        MediaPlayer current = player;
        player = null;
        if (current != null) current.dispose();
    }
}
