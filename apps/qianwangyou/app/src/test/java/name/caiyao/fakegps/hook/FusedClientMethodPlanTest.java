package name.caiyao.fakegps.hook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.Test;

/**
 * Behavioral tests for {@link FusedClientMethodPlan} — the pure, GMS-free planner that maps
 * a public fused contract onto the exact runtime implementation Methods (Sol's frozen design,
 * research dir 2026-08-04-gms-fused-runtime-discovery).
 *
 * The fixtures mimic the shapes seen in the wild: interface contracts implemented by
 * obfuscated classes (bkmc-style), inherited implementations (zz*-style SDK classes), and a
 * pre-21 concrete public client. Marker types are identified by SHAPE (declared
 * onLocationResult/onLocationChanged methods), never by name — device pilot on Maps 26.31
 * showed app-side R8 strips even public marker-class names.
 */
public class FusedClientMethodPlanTest {

    // --- Fixtures -----------------------------------------------------------

    /** Shape-identified by its onLocationResult method, not its name. */
    interface FakeLocationCallback {
        void onLocationResult(Object result);
    }
    /** Shape-identified by its onLocationChanged method, not its name. */
    interface FakeGmsLocationListener {
        void onLocationChanged(Object location);
    }

    /** Mimics the public FusedLocationProviderClient interface (21.x shape). */
    interface FakeFusedContract {
        Object getLastLocation();
        Object getCurrentLocation(int priority, Object cancellationToken);
        Object requestLocationUpdates(Object request, FakeLocationCallback callback, Object looper);
        Object requestLocationUpdates(Object request, FakeGmsLocationListener listener, Object looper);
    }

    /** Maps 26.31 shape: final obfuscated class implementing the interface directly. */
    public static final class BkmcShape implements FakeFusedContract {
        @Override public Object getLastLocation() { return null; }
        @Override public Object getCurrentLocation(int priority, Object cancellationToken) { return null; }
        @Override public Object requestLocationUpdates(Object request, FakeLocationCallback callback, Object looper) { return null; }
        @Override public Object requestLocationUpdates(Object request, FakeGmsLocationListener listener, Object looper) { return null; }
        /** Same name as a contract method but NOT a contract overload — must never be planned. */
        public Object getLastLocation(int nonContractArg) { return null; }
    }

    /** SDK 21.x shape: implementations live on a package-private base, inherited by the result. */
    public static class ZzBase implements FakeFusedContract {
        @Override public Object getLastLocation() { return null; }
        @Override public Object getCurrentLocation(int priority, Object cancellationToken) { return null; }
        @Override public Object requestLocationUpdates(Object request, FakeLocationCallback callback, Object looper) { return null; }
        @Override public Object requestLocationUpdates(Object request, FakeGmsLocationListener listener, Object looper) { return null; }
    }
    public static final class ZzcgShape extends ZzBase {}

    /** Pre-21 shape: the public client itself is a concrete class. */
    public static class LegacyConcreteClient {
        public Object getLastLocation() { return null; }
        public Object getCurrentLocation(int priority, Object cancellationToken) { return null; }
        public Object requestLocationUpdates(Object request, FakeLocationCallback callback, Object looper) { return null; }
        public Object requestLocationUpdates(Object request, FakeGmsLocationListener listener, Object looper) { return null; }
    }

    /** Abstract implementation — must be rejected (Xposed cannot hook abstract methods). */
    public static abstract class AbstractShape implements FakeFusedContract {}

    static class NotAClient {}

    private static List<FusedClientMethodPlan.Entry> planFor(Class<?> runtime) {
        return FusedClientMethodPlan.plan(FakeFusedContract.class, runtime);
    }

    // --- Design red-first items ---------------------------------------------

    /** 1. Runtime class NAME is irrelevant: different-named impls produce equivalent plans. */
    @Test
    public void planDoesNotDependOnRuntimeClassName() {
        List<FusedClientMethodPlan.Entry> a = planFor(BkmcShape.class);
        List<FusedClientMethodPlan.Entry> b = planFor(ZzcgShape.class);
        assertEquals(4, a.size());
        assertEquals(4, b.size());
        Set<String> surfacesA = new HashSet<>();
        Set<String> surfacesB = new HashSet<>();
        for (FusedClientMethodPlan.Entry e : a) surfacesA.add(e.surface.name());
        for (FusedClientMethodPlan.Entry e : b) surfacesB.add(e.surface.name());
        assertEquals(surfacesA, surfacesB);
    }

    /** 2. Inherited implementations resolve to their DECLARING owner, not the runtime class. */
    @Test
    public void inheritedImplementationsResolveToDeclaringOwner() {
        for (FusedClientMethodPlan.Entry e : planFor(ZzcgShape.class)) {
            assertEquals(ZzBase.class, e.implementation.getDeclaringClass());
            assertFalse(Modifier.isAbstract(e.implementation.getModifiers()));
        }
    }

    /** 3a. Unassignable runtime class is not eligible. */
    @Test
    public void unassignableRuntimeClassIsRejected() {
        assertFalse(FusedClientMethodPlan.isEligible(FakeFusedContract.class, NotAClient.class));
    }

    /** 3b. Abstract implementations are excluded from the plan. */
    @Test
    public void abstractImplementationsAreExcluded() {
        assertTrue(planFor(AbstractShape.class).isEmpty());
    }

    /** 4. Planning is deterministic and dedups by Method identity. */
    @Test
    public void repeatedPlansDeduplicateByMethodIdentity() {
        Set<Method> seen = new HashSet<>();
        for (FusedClientMethodPlan.Entry e : planFor(BkmcShape.class)) seen.add(e.implementation);
        int first = seen.size();
        for (FusedClientMethodPlan.Entry e : planFor(BkmcShape.class)) seen.add(e.implementation);
        assertEquals(first, seen.size());
        assertEquals(4, first);
    }

    /** 5. Only exact contract overloads are planned; same-name non-contract methods excluded. */
    @Test
    public void nonContractOverloadsAreExcluded() throws Exception {
        Method nonContract = BkmcShape.class.getMethod("getLastLocation", int.class);
        for (FusedClientMethodPlan.Entry e : planFor(BkmcShape.class)) {
            assertFalse(e.implementation.equals(nonContract));
        }
    }

    /** 6. Pre-21 concrete public client is eligible for eager installation. */
    @Test
    public void concretePublicClientIsEligibleForEagerInstall() {
        assertTrue(FusedClientMethodPlan.isEligible(LegacyConcreteClient.class, LegacyConcreteClient.class));
        List<FusedClientMethodPlan.Entry> plan = FusedClientMethodPlan.plan(
                LegacyConcreteClient.class, LegacyConcreteClient.class);
        assertEquals(4, plan.size());
        for (FusedClientMethodPlan.Entry e : plan) {
            assertEquals(LegacyConcreteClient.class, e.implementation.getDeclaringClass());
        }
    }

    /** Surface mapping sanity: each contract method lands on the right surface. */
    @Test
    public void surfacesMapToContractSignatures() {
        Set<FusedClientMethodPlan.Surface> surfaces = new HashSet<>();
        for (FusedClientMethodPlan.Entry e : planFor(BkmcShape.class)) {
            surfaces.add(e.surface);
            switch (e.contract.getName()) {
                case "getLastLocation":
                    assertEquals(FusedClientMethodPlan.Surface.LAST_LOCATION_TASK, e.surface);
                    break;
                case "getCurrentLocation":
                    assertEquals(FusedClientMethodPlan.Surface.CURRENT_LOCATION_TASK, e.surface);
                    break;
                case "requestLocationUpdates":
                    assertTrue(e.surface == FusedClientMethodPlan.Surface.CALLBACK_REGISTRATION
                            || e.surface == FusedClientMethodPlan.Surface.LISTENER_REGISTRATION);
                    break;
                default:
                    throw new AssertionError("unexpected contract method " + e.contract);
            }
        }
        assertEquals(4, surfaces.size());
    }

    /**
     * Registration entries carry the SHAPE-matched parameter type so the installer can
     * isInstance-check arguments without any class-name lookup (Maps 26.31 pilot: R8 strips
     * public marker-class names). Task surfaces carry none.
     */
    @Test
    public void registrationEntriesCarryShapeMatchedCallbackType() {
        for (FusedClientMethodPlan.Entry e : planFor(BkmcShape.class)) {
            switch (e.surface) {
                case CALLBACK_REGISTRATION:
                    assertEquals(FakeLocationCallback.class, e.callbackType);
                    break;
                case LISTENER_REGISTRATION:
                    assertEquals(FakeGmsLocationListener.class, e.callbackType);
                    break;
                default:
                    assertNull(e.callbackType);
            }
        }
    }

    /** Marker interfaces without the identifying methods must not be shape-matched. */
    interface ShapelessMarker {}
    interface ShapelessContract {
        Object requestLocationUpdates(Object request, ShapelessMarker marker, Object looper);
    }
    public static class ShapelessImpl implements ShapelessContract {
        @Override public Object requestLocationUpdates(Object request, ShapelessMarker marker, Object looper) { return null; }
    }

    @Test
    public void shapelessRegistrationParametersAreNotPlanned() {
        assertTrue(FusedClientMethodPlan.plan(ShapelessContract.class, ShapelessImpl.class).isEmpty());
    }
}
