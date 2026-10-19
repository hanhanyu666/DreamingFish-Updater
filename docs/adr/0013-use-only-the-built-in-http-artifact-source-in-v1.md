---
status: superseded
superseded-by: 0039-support-static-public-distribution-and-explicit-external-uploads.md
---

# Use only the built-in HTTP artifact source in V1

V1 distributes public release manifests and artifacts through the Management Tool's built-in standard HTTP service. CDN, object storage, WebDAV, HTTPS termination, and custom transfer protocols are excluded; because transport is unauthenticated and unencrypted, signed manifests and cryptographic content hashes are mandatory, and no management credentials or private data may be exposed through this public endpoint.

Superseded by [ADR 0039](0039-support-static-public-distribution-and-explicit-external-uploads.md), which records the implemented static export and explicit WebDAV / S3-compatible uploads. The original decision above is retained as history; it no longer describes the complete transport support.
