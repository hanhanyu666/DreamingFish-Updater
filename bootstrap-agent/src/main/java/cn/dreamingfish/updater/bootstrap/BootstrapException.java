package cn.dreamingfish.updater.bootstrap;

class BootstrapException extends Exception {
    BootstrapException(String message) {
        super(message);
    }

    BootstrapException(String message, Throwable cause) {
        super(message, cause);
    }
}
