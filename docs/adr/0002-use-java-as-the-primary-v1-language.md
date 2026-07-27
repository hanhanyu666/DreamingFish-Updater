---
status: accepted
---

# Use Java as the primary V1 language

V1 uses Java for the updater bootstrap, update engine, management tooling, and Minecraft integration. C is reserved for a small Windows-native helper only if Java cannot provide a required operating-system capability; Rust and C++ are excluded from the primary implementation so the project remains maintainable with the owner's current skills and avoids operating two large language ecosystems.
