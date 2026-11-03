package cn.dreamingfish.updater.engine;

import cn.dreamingfish.updater.protocol.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.function.Predicate;

/**
 * Outer pack files are set aside, never half-applied, when this computer cannot use the pack grant.
 * Game-instance files keep updating; the outer files are planned again once the grant is usable.
 */
final class PackContent {
    private PackContent() {}

    static boolean present(ReleaseManifest manifest) {
        return manifest.files().stream().anyMatch(file -> ContentRoots.isPackPath(file.path()))
                || manifest.forcedSyncFiles().stream().anyMatch(ContentRoots::isPackPath)
                || manifest.forcedSyncDirectories().stream().anyMatch(ContentRoots::isPackPath)
                || manifest.cleanupDirectories().stream().anyMatch(ContentRoots::isPackPath);
    }

    /** Null when the outer files of this release can be written here. */
    static String unavailableReason(Path instance, ReleaseManifest manifest) {
        if (!present(manifest)) return null;
        ProjectBinding binding;
        try { binding = ContentRoots.binding(instance); }
        catch (IOException | ProtocolException error) { return error.getMessage(); }
        PackRootSpec local = binding.packRoot(), remote = manifest.packRoot();
        if (local == null) {
            if (remote == null) return "这台电脑尚未授权更新整合包外层文件";
            String reason = ContentRoots.unusable(instance, binding.withPackRoot(remote));
            return reason != null ? reason : "外层目录授权尚未保存";
        }
        if (remote != null && !local.sameLocation(remote)) return "服主设置的外层目录位置与这台电脑记录的不同，已跳过外层文件";
        boolean foreign = manifest.files().stream().anyMatch(file -> ContentRoots.isPackPath(file.path())
                && !ManagedPaths.fold(file.path()).startsWith(local.prefix()));
        if (foreign) return "外层文件的目录身份与这台电脑记录的不同，已跳过外层文件";
        return ContentRoots.unusable(instance, binding);
    }

    static SignedRelease view(SignedRelease release, Path instance) {
        if (unavailableReason(instance, release.manifest()) == null) return release;
        return new SignedRelease(withoutPack(release.manifest()), release.bytes(), release.signature(), release.sha256());
    }

    static ReleaseManifest withoutPack(ReleaseManifest manifest) {
        Predicate<String> game = path -> path == null || !ContentRoots.isPackPath(path);
        List<Withdrawal> withdrawals = manifest.withdrawals().stream()
                .map(withdrawal -> new Withdrawal(withdrawal.id(), withdrawal.reason(), withdrawal.createdAt(),
                        withdrawal.items().stream().filter(item -> game.test(item.path())).toList(), withdrawal.kind()))
                .filter(withdrawal -> !withdrawal.items().isEmpty()).toList();
        return manifest.withContent(
                manifest.forcedSyncDirectories().stream().filter(game).toList(),
                manifest.forcedSyncFiles().stream().filter(game).toList(),
                manifest.releasedPaths().stream().filter(game).toList(),
                manifest.files().stream().filter(file -> game.test(file.path())).toList(),
                manifest.cleanupDirectories().stream().filter(game).toList(),
                manifest.retainedSelfManagedPaths().stream().filter(game).toList(),
                withdrawals,
                manifest.corrections().stream().filter(correction -> game.test(correction.path())).toList());
    }

    static VerifiedInstallation withoutPack(VerifiedInstallation installation) {
        return new VerifiedInstallation(installation.schemaVersion(), installation.projectId(), installation.releaseId(),
                installation.sequence(), installation.manifestSha256(), installation.verifiedAt(),
                installation.files().stream().filter(file -> !ContentRoots.isPackPath(file.path())).toList());
    }

    /** Records a grant only after the signed release that carries it was verified. */
    static void accept(Path instance, String projectId, PackRootSpec grant) {
        try {
            AtomicFileSupport.write(instance.resolve(ContentRoots.GRANT), new JsonCodec().writePretty(
                    new ContentRoots.Grant(1, projectId, grant)));
        } catch (IOException error) {
            throw new UpdateException(UpdateErrorCode.LOCAL_STATE_INVALID, "无法保存外层目录授权", error);
        }
    }
}
