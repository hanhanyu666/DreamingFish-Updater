package cn.dreamingfish.updater.engine;

@FunctionalInterface
public interface CancellationToken {
    CancellationToken NEVER = () -> false;

    boolean isCancelled();

    default void throwIfCancelled() {
        if (isCancelled()) {
            throw new UpdateException(UpdateErrorCode.CANCELLED, "Update was cancelled");
        }
    }
}
