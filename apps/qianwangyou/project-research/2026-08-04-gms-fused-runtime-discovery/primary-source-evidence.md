---
feature_ids: []
topics:
  - android
  - xposed
  - google-play-services-location
doc_kind: research_evidence
created: 2026-08-04
---

# GMS fused runtime discovery — primary-source evidence

## Scoped question

Can the module discover and hook the concrete fused-location client through a stable public boundary, without an internal-name list or classpath scan?

## Source inventory

1. Google Play services official references:
   - [`LocationServices`](https://developers.google.com/android/reference/com/google/android/gms/location/LocationServices)
   - [`FusedLocationProviderClient`](https://developers.google.com/android/reference/com/google/android/gms/location/FusedLocationProviderClient)
   - [Google Play services setup/current artifacts](https://developers.google.com/android/guides/setup)
2. Official Google Maven AARs, inspected with `javap -p -c`.
3. Xposed API reference for [`hookAllMethods`](https://api.xposed.info/reference/de/robv/android/xposed/XposedBridge.html#hookAllMethods(java.lang.Class,%20java.lang.String,%20de.robv.android.xposed.XC_MethodHook)) and [`findAndHookMethod`](https://api.xposed.info/reference/de/robv/android/xposed/XposedHelpers.html#findAndHookMethod(java.lang.Class,%20java.lang.String,%20java.lang.Object...)).
4. The exact Google Maps APK installed on the acceptance device, pulled read-only and inspected with `dexdump`.

No community posts or AI-generated reports are used as architectural evidence.

## Cross-version bytecode map

| Artifact | Public client shape | Factory concrete result | SHA-256 |
|---|---|---|---|
| `play-services-location:18.0.0` | public concrete class | public `FusedLocationProviderClient` | `8b46f2fe3fa010639e920edbdf0c42918092e84e453d9966b1ad916f0b167259` |
| `20.0.0` | public concrete class | public `FusedLocationProviderClient` | `b22951ffffc5f1e91fd49c32ca8fdb5698573752b717cf604bc200728b67a0e6` |
| `21.0.1` | public interface | `com.google.android.gms.internal.location.zzbp` | `bc89f1a6118965216b45439e7e9360a6f2054654e4823354768e7884fb3b6b0c` |
| `21.3.0` | public interface | `com.google.android.gms.internal.location.zzbi` | `d5024384d5bf66c75edf353d95eb8e7b5bf64483138474c6e2e93e2c2394f842` |
| `21.4.0` | public interface | `com.google.android.gms.internal.location.zzcg` | `d01c5cbb18c46309b1980535acd7b9d49062537a0c666ab2946c9aabc6b4dcbd` |

Across all five versions, both `Activity` and `Context` overloads of `LocationServices.getFusedLocationProviderClient(...)` are public static Java methods that synchronously construct and return the client. Internal implementation names change even between adjacent releases.

The current branch's candidates are under `com.google.android.gms.location.internal`, while the Google Maven 21.x implementations above are under `com.google.android.gms.internal.location`. The string list is therefore not merely incomplete; it does not describe these official artifacts.

## Exact target: Maps 26.31

Acceptance device:

- Google Maps `26.31.02.954292984`, version code `1068706869`
- Google Play services `26.28.33 (260400-955982596)`
- Android 15, moto g54 5G

Pulled Maps base APK SHA-256:

`750daf9848fdf3fbff8756f9010d78536d96e6ecf13fb6353ab23d81df3db76c`

The Maps dex preserves the public factory and calls it directly:

```text
invoke-static {v1},
  Lcom/google/android/gms/location/LocationServices;
  .getFusedLocationProviderClient:(Landroid/content/Context;)
  Lcom/google/android/gms/location/FusedLocationProviderClient;
```

The factory constructs an application-obfuscated class:

```text
new-instance v0, Lbkmc;
invoke-direct {v0, v1}, Lbkmc;.<init>:(Landroid/content/Context;)V
return-object v0
```

`bkmc` is `public final`, implements the preserved public `FusedLocationProviderClient` interface, and declares public final `getLastLocation`, `getCurrentLocation`, and `requestLocationUpdates` overloads. Its name cannot be predicted from the SDK artifact.

This is direct evidence that the runtime object is the useful capability token: its name is unstable, but its assignability and public contract signatures survive R8.

## Xposed semantic constraint

The Xposed API specifies that `hookAllMethods(clazz, name, callback)` considers only methods declared by `clazz`; inherited implementations are excluded. `findAndHookMethod` has the same declared/overridden constraint. A robust installer must therefore resolve each exact runtime implementation `Method` through the public interface signature, then call `XposedBridge.hookMethod(Method, callback)`.

## Stable value-object seams

`LocationResult.create(List<Location>)` and `LocationResult.extractResult(Intent)` are public in every inspected version from 18.0.0 through 21.4.0. They avoid the current code's version-specific `mLocations` field injection and cover the documented PendingIntent extraction path.

## Claim ledger

| Claim | Comparator / scope | Primary source | Source verdict | Non-triviality | Decision fit | Provenance |
|---|---|---|---|---|---|---|
| Internal implementation names are not a stable seam | 18.0.0–21.4.0 plus Maps 26.31 | Google Maven AAR bytecode + exact Maps dex | use | demonstrated against the existing name list | direct | first-party artifacts, 2020–2026, exact target included |
| The public factory is a stable discovery seam | five SDK versions and current Maps | official API + AAR/dex bytecode | use | demonstrated against name guessing and scanning | direct | first-party API/artifacts, exact target included |
| Factory after-hook can install client hooks before caller use | Java call boundary and Xposed callback ordering | Xposed API + local runtime pilot still required | use-with-caveat | not yet device-proven | partial until pilot | official API; exact timing to be verified on device |
| Interface-signature-to-runtime-Method resolution survives current R8 | exact Maps `bkmc` shape | Maps dex | use | demonstrated for current target | direct for Maps; future versions unknown | exact target artifact |
| `LocationResult.create/extractResult` is more stable than field mutation | five SDK versions | Google Maven AAR bytecode | use | demonstrated against changing private fields | direct | first-party artifacts |

## Unknowns retained

- A future app could inline or bypass `LocationServices`; no inspected version or current Maps does so.
- Direct access to PendingIntent extras instead of the documented `LocationResult.extractResult` path remains outside the public-contract seam.
- The factory timing and all surface hooks still require an instrumented exact-device pilot before implementation acceptance.
