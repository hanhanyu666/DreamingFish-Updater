package cn.dreamingfish.updater.protocol;

import java.time.Instant;
import java.util.List;

/**
 * 撤回问题版本: a persistent owner directive carried by every later release
 * until it is revoked. Matching player copies are moved into the player backup.
 */
public record Withdrawal(String id, String reason, Instant createdAt, List<WithdrawalItem> items,
                         Kind kind) {
    public enum Kind { VERSION, REMOVAL }

    public Withdrawal {
        reason = reason == null ? "" : reason.trim();
        items = items == null ? List.of() : List.copyOf(items);
        kind = kind == null ? Kind.VERSION : kind;
    }

    public Withdrawal(String id, String reason, Instant createdAt, List<WithdrawalItem> items) {
        this(id, reason, createdAt, items, Kind.VERSION);
    }

    public boolean removal() { return kind == Kind.REMOVAL; }
}
