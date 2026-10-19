---
status: accepted
---

# Separate the universal update core from platform support

File publication, download, verification, transactional installation, and rollback remain independent of Minecraft and mod-loader versions. Loader-aware capabilities such as mod metadata interpretation and server admission are supplied only for combinations in an explicitly tested support matrix; platforms outside that matrix can still use the base update capability without an unsupported promise of complete integration.

Implementation boundary clarified on 2026-10-18: the current protocol and update engine contain Minecraft mod identity, forced-sync, optional-content and retirement semantics; they are not a fully domain-neutral plugin host. Filesystem transactions, transport and cryptography remain reusable mechanisms, while ModMetadataReader interprets loader metadata and the Java agent supplies the launch bridge. This decision is architectural direction, not evidence that every loader / Minecraft combination has been tested or that server admission exists.
