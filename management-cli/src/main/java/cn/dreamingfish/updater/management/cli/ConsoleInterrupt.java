package cn.dreamingfish.updater.management.cli;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.CountDownLatch;

final class ConsoleInterrupt implements AutoCloseable {
    private final CountDownLatch interrupted;
    private final Method handleMethod;
    private final Object signal;
    private final Object previousHandler;
    @SuppressWarnings("FieldCanBeLocal")
    private final Object installedHandler;

    private ConsoleInterrupt(Method handleMethod, Object signal,
                             Object previousHandler, Object installedHandler,
                             CountDownLatch interrupted) {
        this.handleMethod = handleMethod;
        this.signal = signal;
        this.previousHandler = previousHandler;
        this.installedHandler = installedHandler;
        this.interrupted = interrupted;
    }

    static ConsoleInterrupt install() {
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
            return new ConsoleInterrupt(handle, signal, previous, handler, latch);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return new ConsoleInterrupt(null, null, null, null, new CountDownLatch(1));
        }
    }

    boolean supported() {
        return handleMethod != null;
    }

    void await() throws InterruptedException {
        interrupted.await();
    }

    @Override
    public void close() {
        interrupted.countDown();
        if (!supported()) return;
        try {
            handleMethod.invoke(null, signal, previousHandler);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // The JVM can already be shutting down when the handler is restored.
        }
    }
}
