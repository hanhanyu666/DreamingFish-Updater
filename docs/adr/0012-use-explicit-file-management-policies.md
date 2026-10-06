---
status: accepted
---

# Use explicit file management policies

New releases classify content as ordinarily managed or excluded, and declare non-excludable files and complete mirror directories separately. Ordinary managed files use the historical `ENFORCED` wire token but allow local player exemptions; exact forced paths and mirror directories override those exemptions. Removing a managed source file requires an explicit delete or release-ownership decision. Released ownership declarations remain in subsequent complete targets until the path is managed again or its range is explicitly mirrored.

The historical `DEFAULT` wire token is read-only compatibility through `LEGACY_MISSING_ONLY`; it is not emitted by new releases. Reinstalling every time a file is missing is distinct from a future initialize-once policy. V1 does not attempt generic configuration merging because arbitrary mod formats and semantics require format-specific knowledge.

Clarified on 2026-10-02 after reviewing accumulated file-management features.

Refined on 2026-10-02 by ADR 0033: the initialize-once behaviour now exists as the “initial” preset, and the “default configuration” preset keeps player-edited configuration without attempting to merge formats.
