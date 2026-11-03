---
status: accepted
---

# Merge launcher configuration with declarative patches

Files ending in `.dfs-patch.json` are signed declarative instructions for the corresponding target without that suffix. This preserves the existing source-file, object and transaction model while allowing safe JSON object, INI key and Minecraft JVM-argument updates. Manifests declare `config-patch-v1` and reject physical target collisions and cleanup-directory combinations.

The engine backs up the original target, applies only selected fields, retains access restrictions and verifies the resulting fields. Small verified instructions remain in local state for idempotent and offline verification after the ordinary object cache is cleaned. Unselected fields, conditional JVM arguments and unrelated Agents are retained. Unsupported formats and conflicts fail without silently replacing whole launcher files.

Removing a patch defaults to retaining the player's target; publication requires releasing management rather than deleting the whole configuration. Launcher-specific field meanings and write-back timing remain compatibility conditions. Modifying a version JSON after the JVM started cannot affect that JVM's already-consumed arguments.
