package name.caiyao.fakegps.hook.oracle;

/** Pure entry predicates shared by MainHook, the boot-phase callback, and host JVM tests. */
public final class SystemServerOracleEntryPolicy {
    private static final int PHASE_THIRD_PARTY_APPS_CAN_START = 600;

    private SystemServerOracleEntryPolicy() {}

    public static boolean isSystemServer(String packageName, String processName) {
        return "android".equals(packageName) && "android".equals(processName);
    }

    public static boolean shouldBindBridgeAtPhase(int phase) {
        return phase == PHASE_THIRD_PARTY_APPS_CAN_START;
    }
}
