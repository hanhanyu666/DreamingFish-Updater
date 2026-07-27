---
status: accepted
---

# Separate the universal update core from platform support

File publication, download, verification, transactional installation, and rollback remain independent of Minecraft and mod-loader versions. Loader-aware capabilities such as mod metadata interpretation and server admission are supplied only for combinations in an explicitly tested support matrix; platforms outside that matrix can still use the base update capability without an unsupported promise of complete integration.
