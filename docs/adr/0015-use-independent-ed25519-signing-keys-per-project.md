---
status: accepted
---

# Use independent Ed25519 signing keys per project

Each modpack project generates and owns an independent Ed25519 signing key pair. The private key remains in the self-hosted Management Tool and must support encrypted backup export, while the corresponding public key is pinned by that project's Player Updater; both modpack release manifests and Player Updater program manifests require that project's valid signature, and artifacts require cryptographic content hashes. This isolates project compromise and makes untrusted HTTP transport incapable of authorizing forged updates.
