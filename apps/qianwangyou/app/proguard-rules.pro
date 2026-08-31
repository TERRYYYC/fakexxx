# FakeGPS ProGuard rules

# Xposed hook entry point and supporting classes - must not be obfuscated
-keep class name.caiyao.fakegps.hook.MainHook
-keep class name.caiyao.fakegps.hook.HookUtils
-keep class name.caiyao.fakegps.hook.Snapshot
-keep class name.caiyao.fakegps.hook.PrefsDirectoryObserver
-keep class name.caiyao.fakegps.hook.FusedClientMethodPlan
-keep class name.caiyao.fakegps.hook.FusedDeliveryPlan
-keep class name.caiyao.fakegps.hook.FusedHookRegistry
-keep class name.caiyao.fakegps.hook.FusedTaskTracker
-keep class name.caiyao.fakegps.oracle.** { *; }
-keep class name.caiyao.fakegps.hook.oracle.** { *; }

# Xposed replaces this exact target-classloader method at runtime. Keeping the class and method is
# required not just for its name: R8 must not inline the default `false` into the probe service.
-keep class name.caiyao.fakegps.verify.RuntimeHookSentinel { *; }

# Keep Xposed-related classes
-keep class de.robv.android.xposed.** { *; }
-dontwarn de.robv.android.xposed.**

# OSMDroid
-dontwarn org.osmdroid.**
