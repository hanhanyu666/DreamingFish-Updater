---
status: accepted
---

# Enforce mod blacklists at server admission

Unmanaged and blacklisted mods do not prevent Minecraft from starting or being used for single-player games. When a player connects to the target server, the client and server integration performs an admission check; a detected blacklisted mod causes an immediate disconnect with a clear explanation, never forced process termination. This policy supports compatibility and community rules but is not treated as an anti-cheat security boundary because a hostile client can falsify client-side reports.

Blacklist enforcement and server-admission integration are deferred beyond V1. V1 only reports unmanaged mods and otherwise leaves them untouched.
