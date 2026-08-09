package name.caiyao.fakegps.hook;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Collections;

/**
 * Identity fields that are observable on modern Android but are not user-configurable.
 *
 * <p>Each getter is optional because the serving process may run an older API or an OEM framework
 * where only part of the modern surface exists. A missing getter drops only that field; it must not
 * abort extraction of the remaining real baseline.
 */
final class CellIdentityMetadata {

    static final CellIdentityMetadata EMPTY =
            new CellIdentityMetadata(null, null, null, null, null);

    final int[] bands;
    final String alphaLong;
    final String alphaShort;
    final Collection<?> additionalPlmns;
    final Object csgInfo;

    private CellIdentityMetadata(int[] bands, String alphaLong, String alphaShort,
                                 Collection<?> additionalPlmns, Object csgInfo) {
        this.bands = bands;
        this.alphaLong = alphaLong;
        this.alphaShort = alphaShort;
        this.additionalPlmns = additionalPlmns;
        this.csgInfo = csgInfo;
    }

    static CellIdentityMetadata from(Object identity) {
        if (identity == null) return EMPTY;

        Object bands = callOptional(identity, "getBands");
        Object alphaLong = callOptional(identity, "getOperatorAlphaLong");
        Object alphaShort = callOptional(identity, "getOperatorAlphaShort");
        Object additionalPlmns = callOptional(identity, "getAdditionalPlmns");
        Object csgInfo = callOptional(identity, "getClosedSubscriberGroupInfo");
        return new CellIdentityMetadata(
                bands instanceof int[] ? (int[]) bands : null,
                stringOrNull(alphaLong),
                stringOrNull(alphaShort),
                additionalPlmns instanceof Collection ? (Collection<?>) additionalPlmns : null,
                csgInfo);
    }

    static String readPlmn(Object identity, String stringGetter, String numericGetter) {
        Object value = callOptional(identity, stringGetter);
        if (value == null) value = callOptional(identity, numericGetter);
        return stringOrNull(value);
    }

    static Integer readInteger(Object target, String getter) {
        Object value = callOptional(target, getter);
        return value instanceof Number ? ((Number) value).intValue() : null;
    }

    /**
     * Project the configured network operator into both public alpha-name variants.
     *
     * <p>{@code null} retains the real device metadata (passthrough). The snapshot's empty string
     * represents {@code --} on TelephonyManager, but CellIdentity's native unknown is null.
     */
    CellIdentityMetadata withOperatorName(String operatorName) {
        if (operatorName == null) return this;
        String identityValue = operatorName.isEmpty() ? null : operatorName;
        return new CellIdentityMetadata(
                bands, identityValue, identityValue, additionalPlmns, csgInfo);
    }

    int[] bandsOrEmpty() {
        return bands != null ? bands : new int[0];
    }

    Collection<?> additionalPlmnsOrEmpty() {
        return additionalPlmns != null ? additionalPlmns : Collections.emptySet();
    }

    private static Object callOptional(Object target, String methodName) {
        try {
            Method method = target.getClass().getMethod(methodName);
            return method.invoke(target);
        } catch (ReflectiveOperationException | SecurityException unavailable) {
            return null;
        }
    }

    private static String stringOrNull(Object value) {
        return value != null ? String.valueOf(value) : null;
    }
}
