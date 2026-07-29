---
status: accepted
---

# Allow offline starts when the update service is unreachable

When the update service is temporarily unreachable, the player grants the game-start gate. A previously verified installation is still checked against its signed baseline before launch. An instance without a signed baseline is allowed to launch without validation, and the UI and log must explicitly report that neither freshness nor local files were verified.

This availability exception applies only to `NETWORK_UNAVAILABLE`. An incomplete transaction, a damaged previously verified installation, an invalid signature or manifest, a project mismatch, replay, unsafe path, download hash failure, or any other integrity failure still blocks startup. This distinction prevents an attacker from turning a validation failure into an offline bypass while allowing players to use an unverified first installation during a genuine service outage.
