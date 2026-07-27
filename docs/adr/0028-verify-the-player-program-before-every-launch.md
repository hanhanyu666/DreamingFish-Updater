---
status: accepted
---

# Verify the Player Updater before every launch

The Java 8 bootstrap Agent independently verifies the project-signed player-program manifest, its pinned SHA-256, and the exact installed file set before starting either the active or fallback Player Updater. Trusting only the writable active-version pointer would let a damaged or replaced updater issue game-start permission; accepting the small Jackson and Bouncy Castle footprint in the fixed Agent keeps that permission boundary anchored to the project public key.

The Agent runs the CPU-intensive file pass in a short-lived verifier JVM created from the same `java.home`, then waits for that exact child process to exit successfully before it starts the Player Updater. Java 8 executes large SHA-256 passes during `premain` without normal main-phase JIT performance on affected runtimes; isolating the pass keeps a fully verified launch fast without trusting a writable cache or weakening any hash check.
