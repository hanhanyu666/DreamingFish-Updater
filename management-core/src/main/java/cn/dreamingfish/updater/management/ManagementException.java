package cn.dreamingfish.updater.management;

public final class ManagementException extends RuntimeException {
    public ManagementException(String message) {
        super(message);
    }

    public ManagementException(String message, Throwable cause) {
        super(message, cause);
    }
}
