package name.caiyao.fakegps.hook;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure, GMS-free planner that maps the public fused-location contract onto the exact runtime
 * implementation Methods of a discovered client class.
 *
 * <h3>Why this exists (Sol's frozen design, 2026-08-04)</h3>
 * GMS renames the concrete FusedLocationProviderClient implementation constantly
 * ({@code zzbp} &rarr; {@code zzbi} &rarr; {@code zzcg} &rarr; app-R8 {@code bkmc}), so
 * internal-name guessing is dead. The stable seam is the public contract: the runtime class
 * is discovered via the {@code LocationServices} factory result, and its exact public
 * methods are resolved here through {@code runtimeClass.getMethod(...)}, which also finds
 * inherited implementations. Xposed can then {@code hookMethod} the returned exact
 * {@link Method} objects — something {@code hookAllMethods} on the interface cannot do
 * (abstract methods) and on the runtime class cannot do reliably (declared-only semantics).
 *
 * <h3>No name lookups, even for marker types</h3>
 * Device pilot (Maps 26.31) proved app-side R8 also strips the public names of
 * {@code LocationCallback}/{@code LocationListener}. They are therefore identified by
 * <em>shape</em>: a {@code requestLocationUpdates} parameter type declaring
 * {@code onLocationResult} is the callback registration, one declaring
 * {@code onLocationChanged} is the listener registration. The matched parameter type is
 * carried in {@link Entry#callbackType} so the installer can {@code isInstance}-check
 * arguments without ever resolving a class name.
 *
 * This class has no Xposed or Android dependencies so it is directly unit-testable.
 */
final class FusedClientMethodPlan {

    enum Surface {
        LAST_LOCATION_TASK,
        CURRENT_LOCATION_TASK,
        CALLBACK_REGISTRATION,
        LISTENER_REGISTRATION,
    }

    /** One hookable unit: the public contract signature and its exact runtime implementation. */
    static final class Entry {
        final Surface surface;
        final Method contract;
        final Method implementation;
        /** Registration surfaces: the parameter type identifying the callback/listener arg. */
        final Class<?> callbackType;

        Entry(Surface surface, Method contract, Method implementation, Class<?> callbackType) {
            this.surface = surface;
            this.contract = contract;
            this.implementation = implementation;
            this.callbackType = callbackType;
        }
    }

    private FusedClientMethodPlan() {}

    /**
     * A runtime class is hookable only when it implements the contract. The caller
     * additionally requires a live instance check ({@code contract.isInstance(client)}).
     */
    static boolean isEligible(Class<?> contract, Class<?> runtime) {
        return contract != null && runtime != null && contract.isAssignableFrom(runtime);
    }

    /**
     * Map supported public contract methods to exact runtime implementation Methods.
     *
     * Iterates the CONTRACT's public methods (never the runtime class's), so same-name
     * non-contract overloads on the runtime class can never leak into the plan. Abstract or
     * unresolvable implementations are skipped — the caller emits surface-missing evidence.
     */
    static List<Entry> plan(Class<?> contract, Class<?> runtime) {
        List<Entry> entries = new ArrayList<>();
        if (!isEligible(contract, runtime)) {
            return entries;
        }
        for (Method contractMethod : contract.getMethods()) {
            Classification classification = classify(contractMethod);
            if (classification == null) {
                continue;
            }
            final Method implementation;
            try {
                // getMethod resolves inherited public implementations too — this is what
                // hookAllMethods on the runtime class cannot see (declared-only).
                implementation = runtime.getMethod(
                        contractMethod.getName(), contractMethod.getParameterTypes());
            } catch (NoSuchMethodException missing) {
                continue;
            }
            if (Modifier.isAbstract(implementation.getModifiers())) {
                continue;
            }
            entries.add(new Entry(
                    classification.surface, contractMethod, implementation,
                    classification.callbackType));
        }
        return entries;
    }

    private static final class Classification {
        final Surface surface;
        final Class<?> callbackType;

        Classification(Surface surface, Class<?> callbackType) {
            this.surface = surface;
            this.callbackType = callbackType;
        }
    }

    /** Surface for a contract method, or null when it is outside the supported contract. */
    private static Classification classify(Method contractMethod) {
        switch (contractMethod.getName()) {
            case "getLastLocation":
                return new Classification(Surface.LAST_LOCATION_TASK, null);
            case "getCurrentLocation":
                return new Classification(Surface.CURRENT_LOCATION_TASK, null);
            case "requestLocationUpdates":
                for (Class<?> paramType : contractMethod.getParameterTypes()) {
                    if (declaresMethod(paramType, "onLocationResult")) {
                        return new Classification(Surface.CALLBACK_REGISTRATION, paramType);
                    }
                    if (declaresMethod(paramType, "onLocationChanged")) {
                        return new Classification(Surface.LISTENER_REGISTRATION, paramType);
                    }
                }
                return null; // e.g. the PendingIntent overload — covered via extractResult
            default:
                return null;
        }
    }

    private static boolean declaresMethod(Class<?> type, String name) {
        for (Method m : type.getMethods()) {
            if (m.getName().equals(name)) {
                return true;
            }
        }
        return false;
    }
}
