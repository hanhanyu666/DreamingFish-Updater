package cn.dreamingfish.updater.management.cli;

import com.sun.jna.Callback;
import com.sun.jna.Library;
import com.sun.jna.Native;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;

final class ConsoleInterrupt implements AutoCloseable {
    private final CountDownLatch interrupted;
    private final Runnable uninstall;
    @SuppressWarnings("FieldCanBeLocal")
    private final Object installedHandler;

    private ConsoleInterrupt(Runnable uninstall, Object installedHandler,
                             CountDownLatch interrupted) {
        this.uninstall = uninstall;
        this.installedHandler = installedHandler;
        this.interrupted = interrupted;
    }

    static ConsoleInterrupt install() {
        if (System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT).startsWith("windows")) {
            ConsoleInterrupt windows = installWindowsHandler();
            if (windows != null) return windows;
        }
        return installSignalHandler();
    }

    private static ConsoleInterrupt installWindowsHandler() {
        try {
            CountDownLatch latch = new CountDownLatch(1);
            WindowsConsole.Handler handler = controlType -> {
                if (controlType == WindowsConsole.CTRL_C_EVENT
                        || controlType == WindowsConsole.CTRL_BREAK_EVENT) {
                    latch.countDown();
                    return true;
                }
                return false;
            };
            if (!WindowsConsole.INSTANCE.SetConsoleCtrlHandler(handler, true)) {
                return null;
            }
            return new ConsoleInterrupt(
                    () -> WindowsConsole.INSTANCE.SetConsoleCtrlHandler(handler, false),
                    handler, latch);
        } catch (LinkageError | RuntimeException e) {
            return null;
        }
    }

    private static ConsoleInterrupt installSignalHandler() {
        try {
            Class<?> signalType = Class.forName("sun.misc.Signal");
            Class<?> handlerType = Class.forName("sun.misc.SignalHandler");
            Object signal = signalType.getConstructor(String.class).newInstance("INT");
            CountDownLatch latch = new CountDownLatch(1);
            Object handler = Proxy.newProxyInstance(
                    ConsoleInterrupt.class.getClassLoader(),
                    new Class<?>[]{handlerType},
                    (proxy, method, arguments) -> {
                        return switch (method.getName()) {
                            case "handle" -> {
                                latch.countDown();
                                yield null;
                            }
                            case "toString" -> "DreamingFish Ctrl+C handler";
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == arguments[0];
                            default -> null;
                        };
                    });
            Method handle = signalType.getMethod("handle", signalType, handlerType);
            Object previous = handle.invoke(null, signal, handler);
            return new ConsoleInterrupt(() -> {
                try {
                    handle.invoke(null, signal, previous);
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    // The JVM can already be shutting down when the handler is restored.
                }
            }, handler, latch);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return new ConsoleInterrupt(null, null, new CountDownLatch(1));
        }
    }

    boolean supported() {
        return uninstall != null;
    }

    void await() throws InterruptedException {
        interrupted.await();
    }

    @Override
    public void close() {
        interrupted.countDown();
        if (!supported()) return;
        try {
            uninstall.run();
        } catch (RuntimeException ignored) {
            // The JVM can already be shutting down when the handler is restored.
        }
    }

    private interface WindowsConsole extends Library {
        int CTRL_C_EVENT = 0;
        int CTRL_BREAK_EVENT = 1;
        WindowsConsole INSTANCE = Native.load("kernel32", WindowsConsole.class);

        boolean SetConsoleCtrlHandler(Handler handler, boolean add);

        interface Handler extends Callback {
            boolean callback(int controlType);
        }
    }
}
