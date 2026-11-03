---
status: accepted
---

# Bind external content to a local pack identity

Game instance paths retain their existing meaning. Optional pack content uses the reserved `@pack/<uuid>/` namespace throughout scanning, maintenance, installation state, transactions and archives. The `packRoot` grant defines the parent relationship and the expected folder layout from the root down to the instance (`*` matches any one name). A pack marker binds the resolved directory to the project and UUID.

The project-signed release carries the grant, so existing players adopt it without a new deployment. The same channel already delivers executable mods, so a separate local grant would not stop a compromised publisher; the risk it must stop is writing to the wrong place. A player accepts a signed grant only when its actual folders match the layout, the root is not a broad location and any existing marker belongs to the same project and UUID, and then records it beside the deployment binding. A deployment grant takes precedence; a different signed position is not accepted over a recorded one. When the grant cannot be used, outer files are set aside consistently in planning, verification and recorded installation state, game-instance files continue, and the outer files are planned again once the layout matches.

Destination resolution is centralized and checks the effective grant, marker, link safety and protected paths. Relative parent segments are allowed only in the root grant. A shared pack lock and registered peer game locks prevent conflicting writes; read-only checks do not unnecessarily prohibit other games. Recovery uses the same namespace and identity checks, including after moving the complete pack.

New releases declare `pack-root-v1`, so legacy players reject necessary behavior they do not understand. Changing an existing root position requires an explicit migration design rather than rewriting the saved grant in place.
