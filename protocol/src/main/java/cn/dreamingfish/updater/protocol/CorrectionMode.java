package cn.dreamingfish.updater.protocol;

public enum CorrectionMode {
    /** 只替换已知坏版本: replace the local copy only when it equals a listed bad version. */
    KNOWN_BAD,
    /** 覆盖一次: overwrite the local copy once per instance, whatever its content. */
    ONCE
}
