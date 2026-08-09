package name.caiyao.fakegps.hook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

/**
 * Behavioral tests for {@link FusedDeliveryPlan} and {@link FusedHookRegistry} — the pure,
 * name-free delivery layer for GMS fused surfaces (Sol R5 findings, exact HEAD c091f8a).
 *
 * Sol's R5 proved on the exact Maps APK that app-side R8 removes even more than class names:
 * {@code com.google.android.gms.tasks.Tasks} does not exist at all, and
 * {@code LocationResult.extractResult} survives only as a renamed {@code b(Intent)}.
 * Every fixture here therefore uses deliberately meaningless member names; resolution must
 * happen by SHAPE (parameter/return types), never by member name.
 */
public class FusedDeliveryPlanTest {

    // --- Task delivery fixtures (obfuscated member names on purpose) --------

    interface FakeSuccessListener { void a(android.location.Location value); }
    interface FakeFailureListener { void b(Exception e); }
    interface FakeCompleteListener { void c(FakeTask task); }

    /** GMS Task shape with R8-renamed registration methods. */
    public static class FakeTask {
        public FakeTask a(FakeSuccessListener l) { return this; }
        public FakeTask b(FakeFailureListener l) { return this; }
        public FakeTask c(FakeCompleteListener l) { return this; }
        public FakeTask d(java.util.concurrent.Executor ex, FakeSuccessListener l) { return this; }
        public boolean isComplete() { return true; }
    }

    /** Task surfaces: plan success-listener registrations by shape. */
    @Test
    public void taskDeliveryPlansSuccessListenersOnly() {
        List<FusedDeliveryPlan.TaskDelivery> plan = FusedDeliveryPlan.planTaskDelivery(FakeTask.class);
        assertEquals(2, plan.size());
        for (FusedDeliveryPlan.TaskDelivery d : plan) {
            assertEquals(FakeSuccessListener.class, d.listenerType);
            // The wrapped listener method must be invokable with the fake value
            assertEquals(1, d.listenerMethod.getParameterTypes().length);
            assertEquals(android.location.Location.class, d.listenerMethod.getParameterTypes()[0]);
        }
    }

    /** Failure (Exception param) and completion (Task param) listeners are excluded. */
    @Test
    public void taskDeliveryExcludesFailureAndCompleteListeners() {
        for (FusedDeliveryPlan.TaskDelivery d : FusedDeliveryPlan.planTaskDelivery(FakeTask.class)) {
            Class<?> param = d.listenerMethod.getParameterTypes()[0];
            assertFalse(Exception.class.isAssignableFrom(param));
            assertFalse(param.isAssignableFrom(FakeTask.class));
        }
    }

    // --- Callback / listener delivery fixtures ------------------------------

    /** LocationResult shape: static (Intent)->self factory + instance List accessor. */
    public static class RenamedLocationResult {
        public static RenamedLocationResult b(android.content.Intent intent) {
            return new RenamedLocationResult();
        }
        public List<Object> getLocations() { return null; }
        public android.location.Location a() { return null; }
    }

    /** LocationAvailability shape: instance method returning boolean. */
    public static class RenamedAvailability {
        public boolean c() { return true; }
    }

    /** GMS LocationCallback shape; delivery methods INHERITED by the app's subclass. */
    public static abstract class BaseCallback {
        public void d(RenamedLocationResult result) {}
        public void e(RenamedAvailability availability) {}
    }
    public static final class AppCallback extends BaseCallback {}

    /** GMS LocationListener shape: single method taking android.location.Location. */
    public static final class AppListener {
        public void f(android.location.Location location) {}
    }

    /** Sol R5 #2: inherited callback delivery must resolve (findAndHookMethod cannot). */
    @Test
    public void inheritedCallbackDeliveryResolvesToDeclaringOwner() {
        FusedDeliveryPlan.CallbackDelivery plan =
                FusedDeliveryPlan.planCallbackDelivery(AppCallback.class);
        assertEquals(BaseCallback.class, plan.resultMethod.getDeclaringClass());
        assertEquals(RenamedLocationResult.class, plan.resultMethod.getParameterTypes()[0]);
        assertEquals(BaseCallback.class, plan.availabilityMethod.getDeclaringClass());
    }

    /** GMS listener delivery resolves by Location parameter shape, not by method name. */
    @Test
    public void listenerDeliveryResolvesByLocationParameterShape() {
        Method m = FusedDeliveryPlan.planListenerDelivery(AppListener.class);
        assertEquals(AppListener.class, m.getDeclaringClass());
        assertEquals(android.location.Location.class, m.getParameterTypes()[0]);
    }

    /** Sol R5 #2: the renamed (Intent)->LocationResult capability must be found by shape. */
    @Test
    public void renamedStaticFactoryResolvesBySignatureShape() throws NoSuchMethodException {
        Method m = FusedDeliveryPlan.resolveStaticResultFactory(RenamedLocationResult.class);
        assertEquals("b", m.getName()); // name is irrelevant to resolution; asserted only to prove the point
        assertEquals(RenamedLocationResult.class, m.getReturnType());
    }

    /** Instance List-returning accessor resolves by return shape. */
    @Test
    public void listAccessorResolvesByReturnShape() throws NoSuchMethodException {
        Method m = FusedDeliveryPlan.resolveListAccessor(RenamedLocationResult.class);
        assertTrue(List.class.isAssignableFrom(m.getReturnType()));
    }

    /** The getLastLocation capability: instance zero-arg method returning Location. */
    @Test
    public void locationAccessorResolvesByReturnType() throws NoSuchMethodException {
        Method m = FusedDeliveryPlan.resolveLocationAccessor(RenamedLocationResult.class);
        assertEquals(android.location.Location.class, m.getReturnType());
        assertEquals(0, m.getParameterTypes().length);
    }

    /** A class lacking the (Intent)->self static capability is not a LocationResult. */
    public static class NotAResult {
        public List<Object> getLocations() { return null; }
    }

    @Test(expected = NoSuchMethodException.class)
    public void staticFactoryRequiresIntentCapability() throws NoSuchMethodException {
        // NotAResult has the List accessor but no (Intent)->self static factory.
        // resolveStaticResultFactory must report NoSuchMethodException via the unchecked wrapper.
        FusedDeliveryPlan.resolveStaticResultFactory(NotAResult.class);
    }

    // --- Method-identity dedup (Sol R5 #3) ----------------------------------

    /** Two subclasses inheriting the SAME implementation Method install it only once. */
    @Test
    public void sharedInheritedMethodIsClaimedOnce() throws Exception {
        FusedHookRegistry registry = new FusedHookRegistry();
        AtomicInteger installs = new AtomicInteger();
        Method viaBase = BaseCallback.class.getMethod("d", RenamedLocationResult.class);
        Method viaSubclass = AppCallback.class.getMethod("d", RenamedLocationResult.class);
        assertEquals(FusedHookRegistry.InstallResult.INSTALLED,
                registry.claimAndInstall(viaBase, m -> installs.incrementAndGet()));
        assertEquals("inherited Method resolved via a second class must dedup by identity",
                FusedHookRegistry.InstallResult.ALREADY_INSTALLED,
                registry.claimAndInstall(viaSubclass, m -> installs.incrementAndGet()));
        assertEquals(1, installs.get());
    }

    /** Distinct methods on the same class are independent installs. */
    @Test
    public void distinctMethodsOnSameClassClaimIndependently() throws Exception {
        FusedHookRegistry registry = new FusedHookRegistry();
        assertEquals(FusedHookRegistry.InstallResult.INSTALLED,
                registry.claimAndInstall(
                        BaseCallback.class.getMethod("d", RenamedLocationResult.class), m -> {}));
        assertEquals(FusedHookRegistry.InstallResult.INSTALLED,
                registry.claimAndInstall(
                        BaseCallback.class.getMethod("e", RenamedAvailability.class), m -> {}));
    }
}
