package name.caiyao.fakegps.config;

/** Pure schema compatibility gate shared by the Android writer, hook and JVM verification UI. */
public final class TransportSchemaContract {
    private TransportSchemaContract() {}

    public static boolean supports(int version) {
        return version == ConfigPrefsSync.SCHEMA_VERSION
                || version == ConfigPrefsSync.PREVIOUS_SCHEMA_VERSION
                || version == ConfigPrefsSync.LEGACY_SCHEMA_VERSION;
    }
}
