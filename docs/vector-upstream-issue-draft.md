# Draft issue for JingMatrix/Vector (NOT submitted)

> 存档说明：这是给上游 Vector 的 issue 草稿，仅供 review，尚未（也暂不）提交到 GitHub。
> 提交前建议补：Vector 精确版本号/commit、可复现的最小模块示例、是否愿提 PR。

---

**Title:** XSharedPreferences re-parses the module APK on every construction (no metadata cache) and blocks callers indefinitely in `awaitLoadedLocked()` — can ANR the target app's main thread

**Component:** `legacy/src/main/java/de/robv/android/xposed/XSharedPreferences.java`, `xposed/src/main/kotlin/org/matrix/vector/impl/utils/VectorMetaDataReader.kt`

**Version:** Vector v2.2-3110 (canary-3110, 3110-c4a701aa), Zygisk; observed on Android 15 and Android 16 (HyperOS 3) devices

## Summary

Two related issues in the `XSharedPreferences` shim make a very common module pattern — constructing an `XSharedPreferences` per refresh tick on the host's main thread — both expensive and fragile:

1. **Every constructor call re-parses the module APK.** `XSharedPreferences(String packageName, String prefFileName)` calls `VectorMetaDataReader.getMetaData(new File(apkPath))`, which does `JarFile(apk).use { zip -> zip.getEntry("AndroidManifest.xml") ... }` — a full zip central-directory parse (`ZipFile.initCEN`) to read `xposedminversion` / `xposedsharedprefs` meta. There is no cache keyed by apk path/mtime, so the same APK is re-read on every construction, forever.

2. **Every getter can block forever.** `startLoadFromDisk()` loads asynchronously on a `"XSharedPreferences-load"` thread; all getters (`getString`, `getAll`, …) call `awaitLoadedLocked()`, which is:

   ```java
   private void awaitLoadedLocked() {
       while (!mLoaded) {
           try { wait(); } catch (InterruptedException unused) { }
       }
   }
   ```

   There is no timeout and `InterruptedException` is swallowed. If the loader thread stalls (frozen cgroup / FUSE stall / slow storage — all realistic on OEM-aggressive ROMs), the calling thread waits indefinitely. A module calling `getString()` from the target app's main looper turns that stall into a host-app ANR.

## Evidence from our module (ANR trace)

Our module (a location-spoofer provider) used to do exactly the common thing: `new XSharedPreferences(BuildConfig.APPLICATION_ID, PREFS_FILE)` on every heartbeat tick, running on the host's main thread via a `Handler` on `Looper.getMainLooper()`. On a Xiaomi 14 (HyperOS 3 / Android 16) we captured an ANR stack with these frames:

```
MainHook$1.handleMessage            (main looper heartbeat tick)
  -> MainHook.loadSnapshot
       -> XSharedPreferences.<init>            (per-tick construction)
            -> VectorMetaDataReader.getMetaData -> java.util.zip.ZipFile.initCEN
       -> XSharedPreferences.getString
            -> awaitLoadedLocked -> Object.wait   (loader thread stalled; process frozen by OEM freezer)
```

So in one tick we paid (a) a full APK zip parse on the main thread, and (b) an unbounded wait on a loader that could never finish because the process was being cgroup-frozen at that moment. The process was suspended while blocked in `Object.wait`, and every later binder call into it black-holed.

## Workaround we shipped (module side)

- ONE shared `XSharedPreferences` instance per process (lazy `AtomicReference` + CAS); `reload()` is cheap for an unchanged file (stat gate), so a single instance keeps the same freshness at a one-time construction cost.
- ALL runtime prefs IO moved off the main thread to a single daemon executor; the main thread only posts work. A wedged worker now degrades to a frozen last-known-good snapshot instead of a dead main thread.

That fixes it for our module, but the framework-level hazards remain for every module copying the classic Xposed snippets.

## Suggestions

- Cache the meta-data verdict in `VectorMetaDataReader` (keyed by apk path + lastModified/size), or replace the `JarFile` parse with `PackageManager.getApplicationInfo(packageName).metaData` where a `PackageManager` is reachable — the constructor only needs `xposedminversion` / `xposedsharedprefs`, which are ordinary manifest meta-data.
- Give `awaitLoadedLocked()` a bounded wait (e.g. return `mMap`-so-far or default values after N seconds and log), so a stalled loader degrades instead of hanging the caller forever; do not swallow `InterruptedException` in a loop that can then wait again forever.
- Optionally document on the module API surface that construction is expensive under Vector and instances should be reused + `reload()`d (the classic LSPosed docs already advise instance reuse; a stat-gated `reload()` makes that advice cheap to follow).

Happy to provide the full ANR traces, device logs (cgroup.freeze transitions), or a minimal repro module if useful.
