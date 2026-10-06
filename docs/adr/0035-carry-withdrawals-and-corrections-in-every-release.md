---
status: accepted
---

# Carry withdrawals and corrections in every release

Problem handling is expressed as persistent directives in the project rules and copied into every later release, like released paths, so players updating directly from any old baseline still receive them. A **withdrawal** names historical versions by content hash and, for mods, by mod ID plus version; every copy found on a player machine is moved into the player backup even if the player disabled or self-manages it, renamed copies are found by mod ID, and a withdrawal never blocks the game from starting. A version that is still published cannot be withdrawn, and a rollback refuses targets containing withdrawn versions.

A **correction** puts the currently published file in place of a player's copy: either only where the local copy matches selected known-bad versions, or once per player regardless of local edits. The replaced copy goes into the player backup and the player state records which corrections were applied, so a one-time correction is never repeated. Both directives stay in effect until the owner revokes them; revoking does not restore files already moved.

Decided on 2026-10-02.
