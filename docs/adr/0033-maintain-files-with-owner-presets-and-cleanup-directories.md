---
status: accepted
---

# Maintain files with owner presets and independent cleanup directories

Releases carrying the `maintenance-policy-v2` capability give every published file one of four owner presets instead of the forced/ordinary split: **required** (always equal to the release; players can neither disable nor self-manage it), **sync** (the default: follows the release, players may disable mods or self-manage files), **initial** (installed once when missing, then owned by the player) and **default configuration** (updated only while the local copy still equals the last shipped default; a changed copy is kept and the player can ask to restore the default). Presets are set per file or per folder and the most specific rule wins; files chosen as optional content are more specific than a folder-wide “required”, while an explicit per-file “required” cannot be optional.

Cleaning extra files is a separate switch per top-level directory. It moves files the release does not publish into the player backup and never decides how published files are maintained, so “keep mods identical” and “let players change configs” can be combined freely. Cleanup refuses directories that local exclusion rules protect.

Planning applies, in order: protected updater paths, withdrawals, corrections and reset requests, player choices (ignored for required files), the preset, files the owner removed, cleanup directories and duplicate mods. Releases without the capability keep their historical semantics through a compatibility mapping, publishing such a release requires player 0.2.0 or newer (the management console requires an explicit acknowledgement while no such player program is published), and older players fail closed.

Supersedes ADR 0030 and ADR 0032; decided on 2026-10-02.
