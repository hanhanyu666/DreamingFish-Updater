---
status: accepted
---

# Allow offline starts from a trusted installation baseline

When the update service is temporarily unreachable, the player checks a previously accepted signed installation or the signed baseline bundled with the distribution before granting verified offline permission. Missing or invalid baseline metadata blocks the current startup flow before the network check; there is no automatic permission for an instance without a baseline.

Automatic offline permission applies only to `NETWORK_UNAVAILABLE` and requires intact local content. If the network is unavailable but content differs from an existing trusted baseline, the UI may offer an explicit player choice to retain those changes and launch without recording verification. Invalid signatures or manifests, incomplete transactions, project mismatch, replay, unsafe paths and download hash failures cannot trigger either automatic offline permission or that local-content choice.

Clarified on 2026-10-02 to match the startup implementation and distinguish an explicit local-content choice from a missing-baseline launch.

Clarified on 2026-10-18: verified permission proves current bytes against the trusted target and applicable maintenance policy. Persistent size / mtime indexes are display caches, never content evidence. Any offline repair, transaction recovery or maintenance-state write must hold the engine's game lock; without it only read-only verification is allowed. A process-exit recovery test does not establish hard-power-loss durability on all storage hardware.
