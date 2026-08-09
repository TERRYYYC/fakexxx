package name.caiyao.fakegps.hook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;

import org.junit.Test;

/**
 * Sol R6 red-first tests — every fixture here mirrors the EXACT Maps bytecode facts Sol
 * verified on the device APK:
 *
 * <ul>
 *   <li>The declared Task return type is the ABSTRACT {@code bkwo}; the real success
 *       listener is {@code bkwj.c(Object)} — generic erasure makes the delivered value
 *       param {@code Object}, and this MUST be planned (R6 #1).</li>
 *   <li>{@code LocationResult} has a public {@code (List)} CONSTRUCTOR and a renamed
 *       static {@code b(Intent)}, but NO static {@code (List)->self} factory (R6 #2).</li>
 *   <li>claim-then-fail must release so the next discovery retries (R6 #3).</li>
 * </ul>
 */
public class FusedDeliveryPlanR6Test {

    // --- Erasure-realistic Task fixtures (R6 #1) -----------------------------

    /** The erased OnSuccessListener shape: value param is Object. */
    interface ErasedSuccessListener { void c(Object value); }
    /** The erased OnCompleteListener shape: param is the Task base class. */
    interface ErasedCompleteListener { void d(FakeTaskBase task); }
    /** A continuation: returns a value, param is the Task — must be excluded. */
    interface FakeContinuation { Object e(FakeTaskBase task); }

    /** Abstract declared return type (bkwo shape). */
    public abstract static class FakeTaskBase {
        public abstract FakeTaskBase a(ErasedSuccessListener l);
        public abstract FakeTaskBase b(ErasedCompleteListener l);
        public abstract FakeTaskBase c(FakeContinuation c);
    }
    /** Concrete runtime task (what the fused API actually returns). */
    public static final class FakeTaskImpl extends FakeTaskBase {
        @Override public FakeTaskBase a(ErasedSuccessListener l) { return this; }
        @Override public FakeTaskBase b(ErasedCompleteListener l) { return this; }
        @Override public FakeTaskBase c(FakeContinuation c) { return this; }
    }

    /** R6 #1: erased (Object)-param success listeners MUST be planned. */
    @Test
    public void erasedObjectSuccessListenerIsPlanned() {
        List<FusedDeliveryPlan.TaskDelivery> plan =
                FusedDeliveryPlan.planTaskDelivery(FakeTaskImpl.class);
        assertEquals(1, plan.size());
        assertEquals(ErasedSuccessListener.class, plan.get(0).listenerType);
        assertEquals(Object.class, plan.get(0).listenerMethod.getParameterTypes()[0]);
    }

    /** R6 #1: complete (Task-param) and continuation (non-void, Task-param) excluded. */
    @Test
    public void completeAndContinuationRegistrationsAreExcluded() {
        for (FusedDeliveryPlan.TaskDelivery d
                : FusedDeliveryPlan.planTaskDelivery(FakeTaskImpl.class)) {
            assertFalse(d.listenerType == ErasedCompleteListener.class);
            assertFalse(d.listenerType == FakeContinuation.class);
        }
    }

    /** A listener whose delivery method returns a value is a continuation, not a listener. */
    interface NonVoidListener { Object f(Object value); }
    public static final class TaskWithNonVoid {
        public TaskWithNonVoid g(NonVoidListener l) { return this; }
    }

    @Test
    public void nonVoidDeliveryMethodsAreExcluded() {
        assertTrue(FusedDeliveryPlan.planTaskDelivery(TaskWithNonVoid.class).isEmpty());
    }

    // --- LocationResult builder: public (List) constructor (R6 #2) -----------

    /** Exact-Maps shape: renamed static (Intent)->self + public (List) constructor,
     *  NO static (List)->self factory. */
    public static class CtorOnlyResult {
        public CtorOnlyResult(List<?> locations) {}
        public static CtorOnlyResult b(android.content.Intent intent) {
            return new CtorOnlyResult(null);
        }
        public List<Object> getLocations() { return null; }
    }

    /** The replacement builder must fall back to the public (List) constructor. */
    @Test
    public void resultBuilderFallsBackToPublicListConstructor() throws Exception {
        Object built = FusedDeliveryPlan.buildResult(
                CtorOnlyResult.class, java.util.Collections.emptyList());
        assertNotNull(built);
        assertTrue(built instanceof CtorOnlyResult);
    }

    /** Static (List)->self factory, when present, is preferred over the constructor. */
    public static class FactoryResult {
        public static FactoryResult create(List<?> locations) { return new FactoryResult(); }
        public static FactoryResult b(android.content.Intent intent) { return new FactoryResult(); }
    }

    @Test
    public void resultBuilderPrefersStaticListFactory() throws Exception {
        Object built = FusedDeliveryPlan.buildResult(
                FactoryResult.class, java.util.Collections.emptyList());
        assertNotNull(built);
        assertTrue(built instanceof FactoryResult);
    }

    /** R6 #2: assignability direction — a factory returning a SUBTYPE is acceptable. */
    public static class SubOfFactoryResult extends FactoryResult {}
    public static class BaseResult {
        public static SubOfBaseResult b(android.content.Intent intent) { return new SubOfBaseResult(); }
    }
    public static class SubOfBaseResult extends BaseResult {}

    @Test
    public void subtypeReturningFactoryIsAccepted() throws Exception {
        Method m = FusedDeliveryPlan.resolveStaticResultFactory(BaseResult.class);
        assertEquals(SubOfBaseResult.class, m.getReturnType());
        assertTrue(BaseResult.class.isAssignableFrom(m.getReturnType()));
    }

    /** A static (Intent)->Object method must NOT be accepted (return is supertype). */
    public static class ObjectReturningResult {
        public static Object b(android.content.Intent intent) { return new Object(); }
    }

    @Test(expected = NoSuchMethodException.class)
    public void supertypeReturningFactoryIsRejected() throws NoSuchMethodException {
        FusedDeliveryPlan.resolveStaticResultFactory(ObjectReturningResult.class);
    }

    // --- Registry release on failure (R6 #3) ---------------------------------

    /** claim -> install failure -> the next discovery may claim again (R8: covered in
     *  depth by FusedInstallTransactionTest's terminal-state semantics). */
    @Test
    public void failedInstallReleasesClaimForRetry() throws Exception {
        FusedHookRegistry registry = new FusedHookRegistry();
        Method m = FusedDeliveryPlanTest.BaseCallback.class.getMethod(
                "d", FusedDeliveryPlanTest.RenamedLocationResult.class);
        assertEquals(FusedHookRegistry.InstallResult.FAILED,
                registry.claimAndInstall(m, method -> {
                    throw new IllegalStateException("simulated hookMethod failure");
                }));
        assertEquals("failed install must leave the method claimable for retry",
                FusedHookRegistry.InstallResult.INSTALLED,
                registry.claimAndInstall(m, method -> {}));
    }

    // --- Fused Task identity gate (R6 #1) -------------------------------------

    /** Only Task instances returned by fused APIs are wrapped; others pass through. */
    @Test
    public void taskTrackerGatesByInstanceIdentity() {
        FusedTaskTracker tracker = new FusedTaskTracker();
        Object fusedTask = new Object();
        Object unrelatedTask = new Object();
        assertFalse(tracker.isTracked(fusedTask));
        tracker.mark(fusedTask);
        assertTrue(tracker.isTracked(fusedTask));
        assertFalse(tracker.isTracked(unrelatedTask));
    }
}
