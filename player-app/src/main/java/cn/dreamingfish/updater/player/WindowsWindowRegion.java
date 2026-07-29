package cn.dreamingfish.updater.player;

import com.sun.jna.Callback;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.util.List;
import java.util.Locale;

final class WindowsWindowRegion {
    private static final int INITIAL_APPLY_DELAY_MILLIS = 35;
    private static final int SETTLE_REAPPLY_DELAY_MILLIS = 125;
    private static final int SETTLE_REAPPLY_COUNT = 16;

    private final Stage stage;
    private final double cornerRadius;
    private final PauseTransition deferredApply =
            new PauseTransition(Duration.millis(INITIAL_APPLY_DELAY_MILLIS));
    private Pointer window;
    private int settleReapplyRemaining;

    private WindowsWindowRegion(Stage stage, double cornerRadius) {
        this.stage = stage;
        this.cornerRadius = cornerRadius;
        deferredApply.setOnFinished(event -> apply());
    }

    static void install(Stage stage, double cornerRadius) {
        if (!isWindows()) return;
        debug("Installing native window region support");
        WindowsWindowRegion region = new WindowsWindowRegion(stage, cornerRadius);
        stage.showingProperty().addListener((observable, oldValue, showing) -> {
            debug("Stage showing changed to " + showing);
            if (Boolean.TRUE.equals(showing)) {
                region.settleReapplyRemaining = SETTLE_REAPPLY_COUNT;
                region.schedule(INITIAL_APPLY_DELAY_MILLIS);
            } else {
                region.window = null;
                region.settleReapplyRemaining = 0;
            }
        });
        stage.widthProperty().addListener((observable, oldValue, value) -> region.schedule());
        stage.heightProperty().addListener((observable, oldValue, value) -> region.schedule());
        stage.outputScaleXProperty().addListener((observable, oldValue, value) -> region.schedule());
        stage.outputScaleYProperty().addListener((observable, oldValue, value) -> region.schedule());
        stage.maximizedProperty().addListener((observable, oldValue, value) -> region.schedule());
    }

    private void schedule() {
        schedule(INITIAL_APPLY_DELAY_MILLIS);
    }

    private void schedule(int delayMillis) {
        if (!stage.isShowing()) return;
        deferredApply.setDuration(Duration.millis(delayMillis));
        if (Platform.isFxApplicationThread()) {
            deferredApply.playFromStart();
        } else {
            Platform.runLater(deferredApply::playFromStart);
        }
    }

    private void apply() {
        if (!stage.isShowing()) return;
        try {
            if (window == null) window = findWindow();
            if (window == null) {
                debug("No visible process window found");
                return;
            }
            if (stage.isMaximized()) {
                int result = User32.INSTANCE.SetWindowRgn(window, null, true);
                debug("Cleared native region for maximized window: " + result);
                return;
            }

            Rect bounds = new Rect();
            if (!User32.INSTANCE.GetClientRect(window, bounds)) return;
            int width = bounds.right - bounds.left;
            int height = bounds.bottom - bounds.top;
            if (width <= 0 || height <= 0) return;
            double scaleX = Math.max(1, stage.getOutputScaleX());
            double scaleY = Math.max(1, stage.getOutputScaleY());
            int diameter = Math.max(2, (int) Math.round(
                    cornerRadius * 2 * Math.max(scaleX, scaleY)));
            Pointer nativeRegion = Gdi32.INSTANCE.CreateRoundRectRgn(
                    0, 0, width + 1, height + 1, diameter, diameter);
            if (nativeRegion == null || Pointer.nativeValue(nativeRegion) == 0) return;
            int result = User32.INSTANCE.SetWindowRgn(window, nativeRegion, true);
            debug("Applied native region " + width + "x" + height
                    + " to HWND " + Pointer.nativeValue(window) + ": " + result);
            if (result == 0) {
                Gdi32.INSTANCE.DeleteObject(nativeRegion);
            }
        } catch (LinkageError | RuntimeException error) {
            window = null;
            if (Boolean.getBoolean("dfs.debug")) {
                error.printStackTrace(System.err);
            }
        } finally {
            if (stage.isShowing() && settleReapplyRemaining > 0) {
                settleReapplyRemaining--;
                schedule(SETTLE_REAPPLY_DELAY_MILLIS);
            }
        }
    }

    private static Pointer findWindow() {
        long processId = ProcessHandle.current().pid();
        Pointer[] match = {null};
        long[] largestArea = {0};
        WindowEnumerator callback = (candidate, ignored) -> {
            IntByReference owner = new IntByReference();
            User32.INSTANCE.GetWindowThreadProcessId(candidate, owner);
            if (Integer.toUnsignedLong(owner.getValue()) != processId
                    || !User32.INSTANCE.IsWindowVisible(candidate)) {
                return true;
            }

            Rect bounds = new Rect();
            if (!User32.INSTANCE.GetClientRect(candidate, bounds)) return true;
            long width = Math.max(0L, (long) bounds.right - bounds.left);
            long height = Math.max(0L, (long) bounds.bottom - bounds.top);
            long area = width * height;
            if (area > largestArea[0]) {
                largestArea[0] = area;
                match[0] = candidate;
            }
            return true;
        };
        User32.INSTANCE.EnumWindows(callback, null);
        return match[0];
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT).startsWith("windows");
    }

    private static void debug(String message) {
        if (Boolean.getBoolean("dfs.debug")) {
            System.err.println("[window-region] " + message);
        }
    }

    private interface WindowEnumerator extends Callback, StdCallLibrary.StdCallCallback {
        boolean callback(Pointer window, Pointer data);
    }

    private interface User32 extends StdCallLibrary {
        User32 INSTANCE = Native.load("user32", User32.class);

        boolean EnumWindows(WindowEnumerator callback, Pointer data);

        int GetWindowThreadProcessId(Pointer window, IntByReference processId);

        boolean IsWindowVisible(Pointer window);

        boolean GetClientRect(Pointer window, Rect bounds);

        int SetWindowRgn(Pointer window, Pointer region, boolean redraw);
    }

    private interface Gdi32 extends StdCallLibrary {
        Gdi32 INSTANCE = Native.load("gdi32", Gdi32.class);

        Pointer CreateRoundRectRgn(int left, int top, int right, int bottom,
                                   int ellipseWidth, int ellipseHeight);

        boolean DeleteObject(Pointer object);
    }

    public static final class Rect extends Structure {
        public int left;
        public int top;
        public int right;
        public int bottom;

        @Override
        protected List<String> getFieldOrder() {
            return List.of("left", "top", "right", "bottom");
        }
    }
}
