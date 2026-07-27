---
status: accepted
---

# Use explicit file management policies

Every distributed file is classified as enforced, default-only, or unmanaged. Enforced files converge to the current release and may overwrite or remove local copies; default-only files are installed only when absent and become player-owned afterward; unmanaged files are never changed. V1 does not attempt generic configuration merging because arbitrary mod formats and semantics cannot be merged reliably without format-specific knowledge.
