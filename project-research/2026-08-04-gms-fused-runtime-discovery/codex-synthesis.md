---
feature_ids: []
topics:
  - android
  - xposed
  - google-play-services-location
doc_kind: research_synthesis
created: 2026-08-04
---

# Codex synthesis — capability-based GMS fused discovery

## Verdict

**Adopt a public-factory → runtime-instance → exact-Method discovery chain.**

Do not add another internal class name. Do not scan dex/classes and do not hook `ClassLoader.loadClass`. The strongest cheap alternative is also the best architecture: hook the stable public factory, inspect its returned capability, and hook only the exact public-contract methods implemented by that runtime class.

## Why this coordinate system is stable

`LocationServices.getFusedLocationProviderClient(...)` survived:

- two releases where the public client itself was concrete;
- three releases where it became an interface backed by three different internal classes; and
- current Maps R8, where the implementation is renamed to `bkmc`.

The factory returns before application code can call the client. An Xposed after-hook can therefore install the concrete method hooks while the object is still inside the factory call boundary. This timing remains a pilot criterion, not an assumed acceptance result.

## Proposed runtime design

### 1. Eagerly arm the public factory

At `hookFusedLocation(ClassLoader)`:

1. Resolve public `LocationServices` and `FusedLocationProviderClient` types.
2. Hook both declared `getFusedLocationProviderClient` overloads.
3. In `afterHookedMethod`, pass the non-null result to `installFusedClientHooks(result, contract, cl)`.
4. For pre-21 releases where the public client type is concrete, also install its class hooks eagerly so direct constructor use remains covered.

No timer, classloader hook, or classpath scan is introduced.

### 2. Validate the returned capability

`installFusedClientHooks` must reject unless:

- `contract.isInstance(client)` is true;
- the runtime class resolves exact implementations for supported public contract signatures; and
- the resolved `Method` is non-abstract.

Deduplicate by `Class<?>` and `Method` identity, not class-name strings. This preserves correctness across multiple classloaders that may reuse the same textual class name.

### 3. Build a pure, testable method plan

Extract one GMS-free helper, tentatively `FusedClientMethodPlan`, that maps:

```text
public contract Method + runtime Class
    -> exact implementation Method + surface enum
```

Supported surfaces:

- `LAST_LOCATION_TASK`: both `getLastLocation` overloads
- `CURRENT_LOCATION_TASK`: both `getCurrentLocation` overloads
- `CALLBACK_REGISTRATION`: `requestLocationUpdates` overloads containing `LocationCallback`
- `LISTENER_REGISTRATION`: overloads containing `LocationListener`

The planner uses the public contract's parameter classes and `runtimeClass.getMethod(...)`, so inherited implementations are found. The Xposed adapter calls `hookMethod` only on the returned exact `Method` objects.

### 4. Replace results through public APIs

- Task surfaces: return `Tasks.forResult(createFakeLocation(snapshot))`.
- `LocationCallback.onLocationResult`: replace the argument with `LocationResult.create(singletonList(fake))`.
- `LocationListener.onLocationChanged`: replace the `Location` argument.
- `LocationResult.getLastLocation/getLocations`: retain the eager public value-object hooks as defense in depth.
- `LocationResult.extractResult(Intent)`: return a public `LocationResult.create(...)` object, covering the documented PendingIntent extraction path.
- `LocationAvailability.isLocationAvailable`: return true through its public getter rather than mutating a private field.

Delete the `mLocations`, `mIsLocationAvailable`, `mResult`, `mComplete`, and `mResultSet` field-name fallbacks from the fused path once the public replacements pass device acceptance. They all compensate for the same wrong representation—private layout—and are not needed in the public-contract design.

### 5. Evidence and resource bounds

Emit one-time lifecycle evidence, never per-location spam:

- `fused_factory_armed` with overload count;
- `fused_client_discovered` with runtime class and classloader identity;
- `fused_surface_hooked` with surface and exact method owner;
- `fused_client_rejected` with a bounded reason enum;
- `fused_surface_missing` for contract methods that cannot resolve.

Bounds per unique runtime class:

- at most two factory hooks;
- at most four Task getter hooks and four callback/listener registration hooks for the current contract;
- reflection only on first discovery of a unique class;
- zero polling and zero classpath scanning.

## Alternatives

| Candidate | Verdict | Reason |
|---|---|---|
| Add `zzcg`/`bkmc` to a candidate list | defer/reject | already disproven by adjacent AARs and app R8 |
| Scan all loaded classes/dex for interface implementors | reject | unbounded startup and classloader cost |
| Hook `ClassLoader.loadClass` and inspect every class | reject | global hot path, too broad |
| Hook the public interface's abstract methods | reject | Xposed cannot hook abstract implementations |
| Binder-level fused interception | defer | broader compatibility and security surface than this mission requires |
| Public factory result discovery | adopt | bounded, version-independent for all inspected releases and exact Maps |

## TDD and acceptance matrix

### Red-first host tests

1. Runtime class name can change without changing the generated method plan.
2. Inherited concrete implementations resolve to their declaring owner.
3. Abstract/unassignable results are rejected.
4. Repeated factory results deduplicate by class/method identity.
5. Only exact public contract overloads are planned; same-name non-contract methods are excluded.
6. Pre-21 concrete public client is eligible for eager installation.
7. Callback replacement uses `LocationResult.create`, with no private-field strings in release bytecode.

### Exact-device pilot

1. Current Maps logs `factory_armed → client_discovered(bkmc) → surface_hooked` before its first fused call.
2. `getLastLocation` and `getCurrentLocation` Tasks return the configured coordinates.
3. Continuous Maps blue-dot updates stay at the configured coordinate for at least two real update intervals.
4. A callback probe and listener probe each receive fake locations.
5. A PendingIntent probe using `LocationResult.extractResult` receives the fake result.
6. Cold/warm process, multi-process, 5/10/30/60 s config, and process restart retain the cadence branch's latency guarantees.
7. Hook counts remain within the declared bound; no duplicate callbacks or scheduler growth.

## Merge gate

The cadence branch stays unmerged until the factory pilot proves current Maps uses the spoofed coordinate. A successful `fused_client_discovered` log alone is not acceptance; the user-visible blue dot and API readback must both change.
