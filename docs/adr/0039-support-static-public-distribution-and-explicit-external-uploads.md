---
status: accepted
---

# Support static public distribution and explicit external uploads

The management application can export signed public manifests and immutable objects to a static directory and explicitly upload them to WebDAV or S3-compatible storage. A conventional HTTPS host or CDN can serve that directory. The operator manages certificates, access policy and cache configuration; the application does not configure a CDN or terminate TLS itself.

This records the existing implementation and supersedes ADR 0013's original built-in-only transport restriction. Signatures, content hashes, immutable release identity and strict path checks remain mandatory on every transport. Public exports never include project private keys, management credentials, unpublished source files or recovery journals. Mutable latest pointers require revalidation; content-addressed objects and hashed UI assets may use immutable caching.

The built-in public HTTP service remains supported with bounded connection and transfer capacity, independently of authenticated management traffic. Its presentation cache still needs the project signing key when producing a new signed payload; this decision does not claim process-level removal of all private-key access.

External upload credentials are supplied for the operation and are not persisted as task inputs. A restart or missing acknowledgement requires reconciliation or explicit credential resubmission, never automatic replay of an external write.
