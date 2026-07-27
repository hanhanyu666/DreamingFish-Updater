package cn.dreamingfish.updater.engine;

public final class UpdateException extends RuntimeException {
    private final UpdateErrorCode code;

    public UpdateException(UpdateErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public UpdateException(UpdateErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public UpdateErrorCode code() {
        return code;
    }
}
