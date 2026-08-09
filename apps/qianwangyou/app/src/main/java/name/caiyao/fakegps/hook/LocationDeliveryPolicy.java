package name.caiyao.fakegps.hook;

/** Applies the location-only Hook/System-Mock choice without disturbing other profile groups. */
final class LocationDeliveryPolicy {
    private static final String SYSTEM_MOCK = "system_mock";

    private LocationDeliveryPolicy() {}

    static Snapshot apply(Snapshot snapshot, String mode) {
        if (!SYSTEM_MOCK.equals(mode)) return snapshot;

        snapshot.latitude = null;
        snapshot.longitude = null;
        snapshot.altitude = null;
        snapshot.speed = null;
        snapshot.bearing = null;
        snapshot.accuracy = null;
        return snapshot;
    }
}
