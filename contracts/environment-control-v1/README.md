---
feature_ids: []
topics:
  - contract
  - aidl
  - binder
  - environment-control-v1
doc_kind: contract_readme
created: 2026-08-09
status: frozen-v1
---

# Environment Control contract v1

The only sanctioned channel between **CellRebel Auto** (consumer) and
**Qianwangyou** (environment authority). Device-local, authenticated, versioned,
narrow. No network surface.

Spec: [`feature-specs/2026-08-09-cellrebel-qianwangyou-a-plus.md`](../../feature-specs/2026-08-09-cellrebel-qianwangyou-a-plus.md) §6.
Machine-checked by [`scripts/check-contract-v1.sh`](../../scripts/check-contract-v1.sh).

## Surface

```
IEnvironmentControlV1
  discover()  -> EnvironmentControlResultV1
  preflight() -> EnvironmentControlResultV1
  apply()     -> EnvironmentControlResultV1
  observe()   -> EnvironmentControlResultV1
  release()   -> EnvironmentControlResultV1
  completeAndAdvance() -> EnvironmentControlResultV1
```

Every `EnvironmentControlResultV1` is schema-versioned (`resultSchemaVersion =
1`) and carries either exactly one typed success payload or a stable
`ContractErrorCodeV1.wire` in `errorCodeWire`. Binder death and
`RemoteException` are transport failures on a separate path. Diagnostic strings
are for humans only; no machine decision may read them.

## Four rules that are easy to break by accident

**1. No Kotlin enum ever crosses the Binder boundary.** Every enum-valued field
is an `Int` wire code (`...Wire` / `...Wires`) decoded by an explicit
`fromWire()`. kotlin-parcelize encodes an enum as `Parcel.writeString(value.name)`
and decodes with `valueOf`, so a constant added by a newer peer makes an older
reader throw from the generated `createFromParcel` — an unparcel crash inside a
Binder transaction, not the typed fail-closed outcome INV-03 requires. The two
apps ship independently, so skew is the normal state, not an edge case.
`check-contract-v1.sh` fails the build if any `@Parcelize` class grows an
enum-typed field.

**2. `fromWire()` returning null always fails closed.** On the trust path that
means "not trusted"; on the handshake path it means `INCOMPATIBLE_PROTOCOL`. An
unknown code is never optimistically read as compatible.

**3. Trust is an exact match, never a comparison.** Only
`VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED` is trustworthy. `ordinal`,
`>=` and "anything but `NONE`" are forbidden — note that trusted is ordinal 0 and
`NONE` is ordinal 2, so comparison-based policies read backwards and would start
trusting new constants silently.

**4. Business failures are values, not hidden framework exceptions.** App code
cannot rely on hidden Android classes for the contract wire path. A provider
returns `EnvironmentControlResultV1.failure(errorCodeWire)` for expected
business failures, and the consumer decodes unknown error codes to
`INTERNAL_FAILURE` fail-closed.

## `acceptedIntentHash`

`CanonicalIntentDigestV1` computes a length-prefixed (`uint32be(len) || bytes`)
SHA-256 over the intent fields. Length prefixes, not separators: with any fixed
separator, `runId="a\nb", attemptId="c"` and `runId="a", attemptId="b\nc"` encode
to identical bytes, so two different intents would share one hash and INV-23's
binding could be bypassed.

This is what proves a trusted completion belongs to *this* attempt's schedule
identity. Coordinates are not part of the intent or its digest — Qianwangyou is
the sole coordinate authority and resolves the effective location from its own
schedule item data (KB-8). Coverage, revision, fingerprint, lease and
verification level together only prove the environment did not change during the
test — they do not prove it was at the right place; that is now the provider's
exclusive responsibility.

## Versioning

`protocolVersion` is 1 and the AIDL descriptor is bound to v1 permanently.
Published method and field semantics are never rewritten in place. A wire code
assigned in v1 is never reused or redefined; new constants may only append, and
only after passing the [`compatibility.yaml`](compatibility.yaml) skew matrix. A
non-backward-compatible v2 uses a new package and interface and is negotiated
explicitly through `discover()`.

## Consuming the module

`minSdk = 24` — the lower bound of the two consumers (Auto 26, Qianwangyou 24).
A library at 26 could not be depended on by a `minSdk 24` app at all.

The two apps are independent Gradle roots, so each includes this module by path:

```kotlin
include(":environment-control-v1")
project(":environment-control-v1").projectDir = file("../../contracts/environment-control-v1")
```

Build output is redirected under each consuming root
(`<app>/build/contract-environment-control-v1`) so the two lanes never write to
the same directory. `check-contract-v1.sh` runs the module's tests from **both**
roots, which is what makes "shared library" a verified claim rather than an
assumption.

Auto must also declare `<queries>` for both provider applicationIds
(`name.caiyao.fakegps`, `name.caiyao.fakegps.bench`): on `targetSdk 35` an
explicit bind to another app's service is subject to package visibility, and the
activity exemption does not extend to services.
