package name.caiyao.fakegps.hook;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

/** Regression coverage for FC-10: real-baseline reads must bypass this module's own getter hooks. */
public class BaselineExtractionGuardTest {

    @Test
    public void guardBytecodeDoesNotCallApi26ThreadLocalFactory() throws Exception {
        String resource = "/" + BaselineExtractionGuard.class.getName().replace('.', '/') + ".class";
        try (InputStream input = BaselineExtractionGuard.class.getResourceAsStream(resource)) {
            assertNotNull(input);
            String constantPool = new String(input.readAllBytes(), StandardCharsets.ISO_8859_1);
            assertFalse(
                    "ThreadLocal.withInitial is unavailable on the supported API 24-25 range",
                    constantPool.contains("withInitial"));
        }
    }

    @Test
    public void guardIsActiveOnlyInsideExtraction() {
        assertFalse(BaselineExtractionGuard.isActive());
        boolean activeInside = BaselineExtractionGuard.call(BaselineExtractionGuard::isActive);
        assertTrue(activeInside);
        assertFalse(BaselineExtractionGuard.isActive());
    }

    @Test
    public void nestedExtractionKeepsGuardActiveUntilOutermostReturn() {
        BaselineExtractionGuard.call(() -> {
            assertTrue(BaselineExtractionGuard.isActive());
            BaselineExtractionGuard.call(() -> {
                assertTrue(BaselineExtractionGuard.isActive());
                return null;
            });
            assertTrue(BaselineExtractionGuard.isActive());
            return null;
        });
        assertFalse(BaselineExtractionGuard.isActive());
    }

    @Test
    public void exceptionCannotLeakExtractionModeIntoLaterAppCalls() {
        try {
            BaselineExtractionGuard.call(() -> {
                throw new IllegalStateException("boom");
            });
            fail("expected exception");
        } catch (IllegalStateException expected) {
            // expected
        }
        assertFalse(BaselineExtractionGuard.isActive());
    }

    @Test
    public void hookSnapshotSelectionBecomesPassthroughForTheEntireExtractionScope() {
        Snapshot configured = new Snapshot();
        configured.operatorName = "Configured Carrier";

        assertSame(configured, Snapshot.forHookInvocation(configured, false));
        assertSame(Snapshot.PASSTHROUGH, Snapshot.forHookInvocation(configured, true));
    }
}
