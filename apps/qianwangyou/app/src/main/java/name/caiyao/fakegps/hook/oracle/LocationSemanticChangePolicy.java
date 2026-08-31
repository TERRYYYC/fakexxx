package name.caiyao.fakegps.hook.oracle;

/**
 * Exact semantic comparator for API-35 mock-provider location publication.
 *
 * <p>The authoritative digest includes only exact latitude/longitude bits from a periodic
 * location sample. Timestamps, elapsed realtime, accuracy, altitude, speed, and bearing are
 * deliberately cadence/sample metadata. A missing or invalid coordinate can never prove an
 * identical publication and therefore takes the conservative changed path.</p>
 */
public final class LocationSemanticChangePolicy {
    private LocationSemanticChangePolicy() {}

    public static boolean hasChanged(
            Double previousLatitude,
            Double previousLongitude,
            Double incomingLatitude,
            Double incomingLongitude) {
        if (!isValid(previousLatitude, previousLongitude)
                || !isValid(incomingLatitude, incomingLongitude)) {
            return true;
        }
        return Double.doubleToLongBits(previousLatitude)
                        != Double.doubleToLongBits(incomingLatitude)
                || Double.doubleToLongBits(previousLongitude)
                        != Double.doubleToLongBits(incomingLongitude);
    }

    private static boolean isValid(Double latitude, Double longitude) {
        return latitude != null
                && longitude != null
                && Double.isFinite(latitude)
                && Double.isFinite(longitude)
                && latitude >= -90.0
                && latitude <= 90.0
                && longitude >= -180.0
                && longitude <= 180.0;
    }
}
