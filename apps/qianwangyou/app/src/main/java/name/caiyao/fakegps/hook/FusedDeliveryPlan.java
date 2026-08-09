package name.caiyao.fakegps.hook;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure, name-free delivery planner for GMS fused surfaces (Sol R5 findings #1/#2).
 *
 * The exact Maps APK proved app-side R8 goes further than class names:
 * {@code com.google.android.gms.tasks.Tasks} does not exist at all, and
 * {@code LocationResult.extractResult} survives only as a renamed {@code b(Intent)}.
 * Every resolution here is therefore by SHAPE — parameter/return types and member
 * structure — with member names never consulted.
 *
 * <ul>
 *   <li>Task surfaces: instead of constructing a completed Task (needs the renamed-away
 *       {@code Tasks} utility), the original Task is kept and its success listeners are
 *       wrapped at the public registration boundary.</li>
 *   <li>Callback/listener delivery: single-parameter delivery methods are resolved through
 *       {@code getMethods} so INHERITED implementations are found ({@code findAndHookMethod}
 *       cannot do this) and classified by parameter shape.</li>
 *   <li>Value objects: the (Intent)&rarr;self static factory (extractResult capability),
 *       the (List)&rarr;self static factory (create capability) and the List/boolean
 *       accessors are resolved by signature shape.</li>
 * </ul>
 */
final class FusedDeliveryPlan {

    /** A success-listener registration on a runtime Task class. */
    static final class TaskDelivery {
        /** Registration method on the Task class (name irrelevant). */
        final Method registrationMethod;
        /** The listener interface accepted by the registration method. */
        final Class<?> listenerType;
        /** The listener's single delivery method, invoked with the location value. */
        final Method listenerMethod;

        TaskDelivery(Method registrationMethod, Class<?> listenerType, Method listenerMethod) {
            this.registrationMethod = registrationMethod;
            this.listenerType = listenerType;
            this.listenerMethod = listenerMethod;
        }
    }

    /** Delivery methods of a GMS LocationCallback-shaped class. */
    static final class CallbackDelivery {
        /** Single-param method whose parameter is LocationResult-shaped. */
        final Method resultMethod;
        /** Single-param method whose parameter is LocationAvailability-shaped. */
        final Method availabilityMethod;

        CallbackDelivery(Method resultMethod, Method availabilityMethod) {
            this.resultMethod = resultMethod;
            this.availabilityMethod = availabilityMethod;
        }
    }

    private FusedDeliveryPlan() {}

    /**
     * Plan the success-listener registrations of a runtime Task class: public methods whose
     * LAST parameter is an interface declaring exactly one VOID single-argument method.
     * Excluded shapes: failure listeners (value is Exception), completion listeners and
     * continuations (value is the Task itself — after generic erasure the success value is
     * {@code Object}, which is NOT treated as the Task; exact-Maps fact, Sol R6 #1).
     * Member names are never consulted.
     */
    static List<TaskDelivery> planTaskDelivery(Class<?> taskClass) {
        List<TaskDelivery> out = new ArrayList<>();
        for (Method registration : taskClass.getMethods()) {
            Class<?>[] params = registration.getParameterTypes();
            if (params.length == 0) continue;
            Class<?> listenerType = params[params.length - 1];
            if (!listenerType.isInterface()) continue;
            Method listenerMethod = singleValueMethod(listenerType);
            if (listenerMethod == null) continue;
            Class<?> valueParam = listenerMethod.getParameterTypes()[0];
            if (Exception.class.isAssignableFrom(valueParam)) continue;
            // onComplete/continuation deliver the Task itself (erased param = Task base).
            // The erased success value is Object — do NOT mistake Object for the Task.
            if (valueParam != Object.class && valueParam.isAssignableFrom(taskClass)) continue;
            out.add(new TaskDelivery(registration, listenerType, listenerMethod));
        }
        return out;
    }

    /**
     * Resolve a callback class's result/availability delivery methods, including inherited
     * implementations. Returns null when either shape is absent. Methods declared on
     * {@link Object} (e.g. {@code equals}) are ignored.
     */
    static CallbackDelivery planCallbackDelivery(Class<?> callbackClass) {
        Method result = null;
        Method availability = null;
        for (Method m : callbackClass.getMethods()) {
            if (m.getDeclaringClass() == Object.class) continue;
            if (m.getParameterTypes().length != 1) continue;
            Class<?> param = m.getParameterTypes()[0];
            if (result == null && isLocationResultLike(param)) {
                result = m;
            } else if (availability == null && isAvailabilityLike(param)) {
                availability = m;
            }
        }
        if (result == null || availability == null) return null;
        return new CallbackDelivery(result, availability);
    }

    /** Resolve a GMS-listener class's delivery method: single param of type Location. */
    static Method planListenerDelivery(Class<?> listenerClass) {
        for (Method m : listenerClass.getMethods()) {
            if (m.getDeclaringClass() == Object.class) continue;
            if (m.getParameterTypes().length == 1
                    && m.getParameterTypes()[0] == android.location.Location.class) {
                return m;
            }
        }
        return null;
    }

    /**
     * The extractResult capability: a static (Intent)&rarr;self factory. Resolved by
     * signature shape because the member name is R8-renamed on current Maps. The return
     * type must be the result class OR a subtype (Sol R6 #2: direction matters).
     */
    static Method resolveStaticResultFactory(Class<?> resultClass) throws NoSuchMethodException {
        for (Method m : resultClass.getMethods()) {
            if (Modifier.isStatic(m.getModifiers())
                    && m.getParameterTypes().length == 1
                    && m.getParameterTypes()[0] == android.content.Intent.class
                    && resultClass.isAssignableFrom(m.getReturnType())) {
                return m;
            }
        }
        throw new NoSuchMethodException("no static (Intent)->self factory on " + resultClass);
    }

    /**
     * Build a replacement LocationResult via its public construction seam: prefer a static
     * (List)&rarr;self factory; fall back to the public (List) constructor — the only
     * builder current Maps actually ships (exact-Maps fact, Sol R6 #2).
     */
    static Object buildResult(Class<?> resultClass, List<?> locations) throws Exception {
        try {
            return resolveStaticListFactory(resultClass).invoke(null, locations);
        } catch (NoSuchMethodException noStaticFactory) {
            for (java.lang.reflect.Constructor<?> ctor : resultClass.getConstructors()) {
                if (ctor.getParameterTypes().length == 1
                        && List.class.isAssignableFrom(ctor.getParameterTypes()[0])) {
                    return ctor.newInstance(locations);
                }
            }
            throw noStaticFactory;
        }
    }

    /**
     * The create capability: a static (List)&rarr;self factory used to build a replacement
     * LocationResult without touching private fields.
     */
    static Method resolveStaticListFactory(Class<?> resultClass) throws NoSuchMethodException {
        for (Method m : resultClass.getMethods()) {
            if (Modifier.isStatic(m.getModifiers())
                    && m.getParameterTypes().length == 1
                    && List.class.isAssignableFrom(m.getParameterTypes()[0])
                    && resultClass.isAssignableFrom(m.getReturnType())) {
                return m;
            }
        }
        throw new NoSuchMethodException("no static (List)->self factory on " + resultClass);
    }

    /** The getLocations capability: an instance zero-arg method returning a List. */
    static Method resolveListAccessor(Class<?> resultClass) throws NoSuchMethodException {
        for (Method m : resultClass.getMethods()) {
            if (!Modifier.isStatic(m.getModifiers())
                    && m.getDeclaringClass() != Object.class
                    && m.getParameterTypes().length == 0
                    && List.class.isAssignableFrom(m.getReturnType())) {
                return m;
            }
        }
        throw new NoSuchMethodException("no List-returning accessor on " + resultClass);
    }

    /** The getLastLocation capability: an instance zero-arg method returning Location. */
    static Method resolveLocationAccessor(Class<?> resultClass) throws NoSuchMethodException {
        for (Method m : resultClass.getMethods()) {
            if (!Modifier.isStatic(m.getModifiers())
                    && m.getDeclaringClass() != Object.class
                    && m.getParameterTypes().length == 0
                    && m.getReturnType() == android.location.Location.class) {
                return m;
            }
        }
        throw new NoSuchMethodException("no Location-returning accessor on " + resultClass);
    }

    /** The isLocationAvailable capability: an instance zero-arg method returning boolean. */
    static Method resolveBooleanAccessor(Class<?> availabilityClass) throws NoSuchMethodException {
        for (Method m : availabilityClass.getMethods()) {
            if (!Modifier.isStatic(m.getModifiers())
                    && m.getDeclaringClass() != Object.class
                    && m.getParameterTypes().length == 0
                    && (m.getReturnType() == boolean.class || m.getReturnType() == Boolean.class)) {
                return m;
            }
        }
        throw new NoSuchMethodException("no boolean accessor on " + availabilityClass);
    }

    /**
     * LocationResult shape: has the (Intent)&rarr;self static capability OR an instance
     * List-returning accessor. Names are never consulted.
     */
    static boolean isLocationResultLike(Class<?> type) {
        if (type.isPrimitive()) return false;
        try {
            resolveStaticResultFactory(type);
            return true;
        } catch (NoSuchMethodException ignored) {}
        try {
            resolveListAccessor(type);
            return true;
        } catch (NoSuchMethodException ignored) {}
        return false;
    }

    /** LocationAvailability shape: has an instance zero-arg boolean accessor. */
    static boolean isAvailabilityLike(Class<?> type) {
        if (type.isPrimitive()) return false;
        try {
            resolveBooleanAccessor(type);
            return true;
        } catch (NoSuchMethodException ignored) {}
        return false;
    }

    /** The listener interface's single VOID one-argument method, or null when shapeless. */
    private static Method singleValueMethod(Class<?> listenerType) {
        Method found = null;
        for (Method m : listenerType.getMethods()) {
            if (m.getDeclaringClass() == Object.class) continue;
            if (m.getParameterTypes().length != 1) return null;
            if (m.getReturnType() != void.class) return null; // continuation, not a listener
            if (found != null) return null; // not a single-method listener
            found = m;
        }
        return found;
    }
}
