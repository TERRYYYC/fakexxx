package name.caiyao.fakegps.hook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Test;

/**
 * Pins the invariant that per-delivery evidence describes the object the target app
 * actually receives.
 *
 * <p>The first cut of this feature classified the pre-replacement INPUT and then delivered
 * a freshly built profile location. Every field was individually correct, so no unit test
 * caught it, yet the emitted meaning was inverted: a healthy interception reported
 * "not the profile" while handing the profile to the app.
 *
 * <p>The fix is structural rather than procedural — construction, classification and
 * return live in one function, so a wiring site cannot hold a classified object and
 * deliver a different one. These assertions keep that shape from silently regressing;
 * HookUtils itself cannot be executed under plain JUnit (Xposed + Android framework, and
 * this project ships no Robolectric), so the project's source-contract seam is used.
 */
public class DeliveryEvidenceWiringContractTest {

    private static final String HOOK_UTILS =
            "src/main/java/name/caiyao/fakegps/hook/HookUtils.java";

    @Test
    public void classificationHasExactlyOneCallSite() throws Exception {
        String source = readSource(HOOK_UTILS);
        // Its declaration plus exactly one call. A second call site would let some surface
        // classify an object other than the one it delivers -- the defect this prevents.
        assertEquals(2, countOccurrences(source, "recordDelivery("));
        // ...and that one call must live inside the fused build-classify-return helper.
        assertEquals(1, countOccurrences(
                methodBody(source, "Location deliverWithEvidence("), "recordDelivery("));
    }

    @Test
    public void everyDeliverySiteTakesItsValueFromTheClassifiedObject() throws Exception {
        String source = readSource(HOOK_UTILS);
        // Declaration plus one call per wired delivery site: the fused-task success
        // listener (surface chosen per task instance), GMS_LISTENER, SYSTEM_LISTENER and
        // SYSTEM_LISTENER_BATCH.
        assertEquals(5, countOccurrences(source, "deliverWithEvidence("));
    }

    @Test
    public void theClassifiedObjectIsTheReturnedObject() throws Exception {
        String body = methodBody(readSource(HOOK_UTILS), "Location deliverWithEvidence(");
        assertEquals("builds the delivered value exactly once", 1,
                countOccurrences(body, "createFakeLocation("));
        assertEquals("classifies exactly once", 1,
                countOccurrences(body, "recordDelivery("));
        assertTrue("classifies the outgoing value, not the incoming one",
                body.contains("recordDelivery(gate, surface, s, outgoing, incoming)"));
        assertTrue("returns the same object it classified", body.contains("return outgoing;"));
    }

    @Test
    public void theTwoFusedTaskSurfacesAreNotFlattenedIntoOne() throws Exception {
        String source = readSource(HOOK_UTILS);
        // A recenter is a getCurrentLocation delivery. Labelling it LAST_LOCATION_TASK, or
        // sharing one heartbeat gate with the steady last-location stream, lets a healthy
        // LAST window swallow the CURRENT evidence and makes recenter unattributable.
        assertTrue("current-location task deliveries carry their own label",
                source.contains("\"CURRENT_LOCATION_TASK\""));
        assertTrue("current-location task deliveries have their own gate",
                source.contains("CURRENT_TASK_DELIVERY_EVIDENCE"));
        assertTrue("last-location task deliveries keep their own gate",
                source.contains("LAST_TASK_DELIVERY_EVIDENCE"));
    }

    @Test
    public void bothAxesEnterTheGateAndTheLineNamesTheRunItCloses() throws Exception {
        String body = methodBody(readSource(HOOK_UTILS), "void recordDelivery(");
        // Gating on `delivered` alone left the edge trigger dead: that axis is near-constant
        // by construction, so `input` -- the axis that varies -- never fired one.
        assertTrue("both axes are gated",
                body.contains("gate.record(delivered, intercepted,"));
        // And the emitted line must carry the emission's own tokens. Printing the caller's
        // current values against an accumulated count is how one line came to claim N
        // deliveries shared a state they did not.
        assertTrue("logs the emission's tokens", body.contains("e.delivered")
                && body.contains("e.input") && body.contains("e.deliveries"));
        // A state change produces two lines. Logging only the head silently drops the newly
        // opened edge, which on a sparse surface is never reported at all.
        assertTrue("walks the whole emission chain", body.contains("e = e.next"));
        assertEquals("must not log the current delivery's tokens", 0,
                countOccurrences(body, "fusedDelivered(\n                        surface, delivered, intercepted"));
    }

    @Test
    public void heartbeatUsesAMonotonicClock() throws Exception {
        String body = methodBody(readSource(HOOK_UTILS), "void recordDelivery(");
        assertTrue("elapsedRealtime cannot be moved backwards by a time sync",
                body.contains("SystemClock.elapsedRealtime()"));
        assertEquals("wall clock must not gate the heartbeat", 0,
                countOccurrences(body, "System.currentTimeMillis()"));
    }

    // ---- helpers (mirroring the project's existing source-contract tests) ----

    private static String readSource(String relativePath) throws Exception {
        File file = new File(relativePath);
        if (!file.exists()) file = new File("app/" + relativePath);
        assertTrue("source not found: " + relativePath, file.exists());
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    /**
     * Brace-balanced extraction so a signature change fails loudly instead of silently.
     * The anchor must name the DECLARATION (return type included): call sites appear
     * earlier in this file than the helpers they call, so a bare name would match a call
     * and silently extract the wrong body.
     */
    private static String methodBody(String source, String declarationAnchor) {
        int at = source.indexOf(declarationAnchor);
        assertTrue("declaration not found: " + declarationAnchor, at >= 0);
        int open = source.indexOf(") {", at);
        assertTrue("body not found: " + declarationAnchor, open >= 0);
        int depth = 0;
        for (int i = open + 2; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') depth++;
            if (c == '}') {
                depth--;
                if (depth == 0) return source.substring(open, i + 1);
            }
        }
        throw new AssertionError("unbalanced body for " + declarationAnchor);
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int from = 0;
        while (true) {
            int at = haystack.indexOf(needle, from);
            if (at < 0) return count;
            count++;
            from = at + needle.length();
        }
    }
}
