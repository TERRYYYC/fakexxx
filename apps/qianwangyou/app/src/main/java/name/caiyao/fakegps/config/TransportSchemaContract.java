package name.caiyao.fakegps.config;

/** Pure schema compatibility gate shared by the Android writer, hook and JVM verification UI. */
public final class TransportSchemaContract {
    private TransportSchemaContract() {}

    /**
     * v5 is the current writer (adds the `modules` object). Still losslessly readable: v4 (the
     * same shape without `modules` — a reader treats absent modules as all-enabled), v3 (no
     * delivery mode) and v2 (no unavailable set). A version outside this chain is rejected rather
     * than guessed at.
     */
    public static boolean supports(int version) {
        return version == ConfigPrefsSync.SCHEMA_VERSION
                || version == ConfigPrefsSync.PREVIOUS_SCHEMA_VERSION
                || version == ConfigPrefsSync.LEGACY_SCHEMA_VERSION
                || version == ConfigPrefsSync.OLDEST_READABLE_SCHEMA_VERSION;
    }
}
