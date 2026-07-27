package cn.dreamingfish.updater.engine;

interface TransactionFaultInjector {
    TransactionFaultInjector NONE = new TransactionFaultInjector() { };

    default void afterPhase(TransactionPhase phase) {
    }

    default void afterOperation(int operationIndex) {
    }

    default void beforeCommit() {
    }
}
