package cn.dreamingfish.updater.management;

import cn.dreamingfish.updater.protocol.ManagedPaths;
import cn.dreamingfish.updater.protocol.ManifestFile;
import cn.dreamingfish.updater.protocol.ReleaseManifest;
import cn.dreamingfish.updater.protocol.Withdrawal;
import cn.dreamingfish.updater.protocol.WithdrawalItem;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.LinkedHashMap;
import cn.dreamingfish.updater.protocol.MaintenanceModel;


/** Compiles owner removal decisions into the complete target, independent of player history. */
final class RemovalPolicies {
    private RemovalPolicies() { }

    /** Once-only migration of actual historical deletions; releases and initial player files stay owned by players. */
    static ProjectRules migrate(ProjectRules rules, ReleaseManifest latest, List<ReleaseManifest> history) {
        if (rules.simplified()) return rules;
        List<Withdrawal> instructions = new ArrayList<>(rules.withdrawals());
        if (latest != null && !latest.requiredCapabilities().contains(
                cn.dreamingfish.updater.protocol.ProtocolConstants.CAPABILITY_SIMPLIFIED_MAINTENANCE)) {
            record Seen(ManifestFile file, MaintenanceModel.Behavior behavior) { }
            java.util.Map<String, Seen> last = new LinkedHashMap<>();
            for (ReleaseManifest release : history) {
                MaintenanceModel model = MaintenanceModel.of(release);
                for (ManifestFile file : release.files()) {
                    String key = file.componentId() == null ? ManagedPaths.fold(file.path())
                            : ManagedPaths.topLevel(file.path()) + "/mod/" + ManagedPaths.fold(file.componentId());
                    last.put(key, new Seen(file, model.behaviorOf(file)));
                }
            }
            for (Seen seen : last.values()) {
                ManifestFile file = seen.file();
                WithdrawalItem item = new WithdrawalItem(file.sha256(), file.size(), file.path(), file.componentId(), file.version());
                if (!seen.behavior().removedWithRelease()
                        || latest.files().stream().anyMatch(current -> sameResource(item, current))
                        || latest.releasedPaths().stream().anyMatch(path -> path.equalsIgnoreCase(file.path()))
                        || latest.retainedSelfManagedPaths().stream().anyMatch(path -> path.equalsIgnoreCase(file.path()))
                        || instructions.stream().filter(Withdrawal::removal).flatMap(rule -> rule.items().stream())
                        .anyMatch(existing -> sameResource(existing, file))) continue;
                instructions.add(new Withdrawal("remove-" + UUID.randomUUID(), "延续旧发布中已移除的文件：" + file.path(),
                        Instant.now(), List.of(item), Withdrawal.Kind.REMOVAL));
            }
        }
        return rules.withWithdrawals(instructions).asSimplified();
    }

    static List<Withdrawal> compile(ProjectRules rules, ReleaseManifest previous,
                                    List<ManifestFile> target, List<PreviewChange> changes, Instant now) {
        List<String> released = changes.stream().filter(change -> change.removalAction() == RemovalAction.RELEASE)
                .map(PreviewChange::path).toList();
        List<Withdrawal> result = new ArrayList<>();
        for (Withdrawal instruction : rules.withdrawals()) {
            if (!instruction.removal()) { result.add(instruction); continue; }
            List<WithdrawalItem> remaining = instruction.items().stream()
                    .filter(item -> target.stream().noneMatch(file -> sameResource(item, file)))
                    .filter(item -> released.stream().noneMatch(path -> path.equalsIgnoreCase(item.path())))
                    .toList();
            if (!remaining.isEmpty()) result.add(new Withdrawal(instruction.id(), instruction.reason(),
                    instruction.createdAt(), remaining, instruction.kind()));
        }
        if (previous != null) {
            for (PreviewChange change : changes) {
                if (change.kind() != ChangeKind.REMOVED || change.removalAction() != RemovalAction.DELETE) continue;
                ManifestFile old = previous.files().stream()
                        .filter(file -> file.path().equalsIgnoreCase(change.path())).findFirst().orElseThrow();
                WithdrawalItem item = new WithdrawalItem(old.sha256(), old.size(), old.path(),
                        old.componentId(), old.version());
                // Updating a mod under a new filename is not retirement of that mod.
                if (target.stream().anyMatch(file -> sameResource(item, file))) continue;
                if (result.stream().filter(Withdrawal::removal).flatMap(rule -> rule.items().stream())
                        .anyMatch(existing -> sameResource(existing, old))) continue;
                String name = old.displayName() == null ? old.path() : old.displayName();
                String reason = "服主已移除 " + name;
                if (reason.length() > 500) reason = reason.substring(0, 500);
                result.add(new Withdrawal("remove-" + UUID.randomUUID(), reason, now,
                        List.of(item), Withdrawal.Kind.REMOVAL));
            }
        }
        return List.copyOf(result);
    }

    static boolean sameResource(WithdrawalItem item, ManifestFile file) {
        if (item.componentId() != null && file.componentId() != null && item.modScoped()) {
            return item.componentId().equalsIgnoreCase(file.componentId())
                    && ManagedPaths.topLevel(item.path()).equalsIgnoreCase(ManagedPaths.topLevel(file.path()));
        }
        return item.path().equalsIgnoreCase(file.path());
    }
}
