package name.caiyao.fakegps.ui.screen.map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/** Compiled contract for the map button's single "current effective location" meaning. */
public class MapRecenterUiContractTest {

    @Test
    public void recenterRequestsCurrentLocationInsteadOfReadingAnUnboundedCache() throws Exception {
        String screen = classBytecode("name.caiyao.fakegps.ui.screen.map.MapScreenKt");

        assertTrue("recenter must request a current fix", screen.contains("getCurrentLocation"));
        assertFalse(
                "getLastKnownLocation can return the same minutes-old point forever",
                screen.contains("getLastKnownLocation"));
    }

    @Test
    public void buttonLabelsTheOneMeaningTruthfully() throws Exception {
        String screen = screenSource();

        assertTrue(screen.contains("归位到当前有效位置"));
    }

    @Test
    public void recenterMovesTheCameraWithoutSelectingANewProfilePoint() throws Exception {
        String screen = screenSource();
        assertSelectionIndependent(screen);
    }

    @Test
    public void selectionGuardRejectsTheLegacyCurrentFixMutation() throws Exception {
        String screen = screenSource();
        String currentFix = "centerMap(mapView, location.latitude, location.longitude)";
        assertTrue("current-location mutation anchor must exist", screen.contains(currentFix));
        String mutated = screen.replace(
                currentFix,
                "vm.onMapTap(location.latitude, location.longitude)\n            " + currentFix);

        assertSelectionMutationRejected(mutated);
    }

    @Test
    public void selectionGuardIgnoresDefaultLambdaBracesInTheSignature() throws Exception {
        String screen = screenSource();
        String parameterEnd =
                "    permLauncher: androidx.activity.result.ActivityResultLauncher<Array<String>>? = null,\n"
                        + ") {";
        assertTrue("recenter signature mutation anchor must exist", screen.contains(parameterEnd));
        String targetBody = "    when (val target = vm.resolveRecenterTarget()) {";
        assertTrue("recenter body mutation anchor must exist", screen.contains(targetBody));
        String mutated = screen
                .replace(
                        parameterEnd,
                        "    permLauncher: androidx.activity.result.ActivityResultLauncher<Array<String>>? = null,\n"
                                + "    onDone: () -> Unit = {},\n"
                                + ") {")
                .replace(targetBody, "    vm.onMapTap(0.0, 0.0)\n" + targetBody);

        assertSelectionMutationRejected(mutated);
    }

    @Test
    public void selectionMutationMetaTestDoesNotAcceptExtractorFailures() throws Exception {
        String screen = screenSource();
        String broken = screen.replace(
                "private fun requestCurrentDeviceLocation(",
                "private fun renamedCurrentDeviceLocation(");

        boolean extractorFailurePropagated = false;
        try {
            assertSelectionMutationRejected(broken);
        } catch (AssertionError expected) {
            extractorFailurePropagated = expected.getMessage() != null
                    && expected.getMessage().contains("signature must remain available");
        }
        assertTrue(
                "extractor failure must not count as a selection-mutation rejection",
                extractorFailurePropagated);
    }

    private static void assertSelectionMutationRejected(String mutated) {
        try {
            assertSelectionIndependent(mutated);
        } catch (AssertionError expected) {
            String message = expected.getMessage();
            if (message != null && message.contains("must not mutate map selection")) {
                return;
            }
            throw expected;
        }
        throw new AssertionError("selection guard must reject the forbidden mutation");
    }

    private static void assertSelectionIndependent(String screen) {
        String[] recenterFunctions = {
            "recenterMap",
            "requestCurrentDeviceLocation",
            "centerMap",
        };
        for (String functionName : recenterFunctions) {
            String function = functionSource(screen, functionName);
            assertFalse(
                    functionName + " must not mutate map selection",
                    function.contains("onMapTap"));
        }
    }

    private static String functionSource(String screen, String functionName) {
        String signature = "private fun " + functionName + "(";
        int start = screen.indexOf(signature);
        assertTrue(functionName + " signature must remain available to the contract", start >= 0);
        int bodyOpening = screen.indexOf(") {", start);
        assertTrue(
                functionName + " body opening must remain available to the contract",
                bodyOpening >= 0);
        int bodyStart = bodyOpening + ") ".length();

        int depth = 0;
        for (int index = bodyStart; index < screen.length(); index++) {
            char current = screen.charAt(index);
            if (current == '{') {
                depth++;
            } else if (current == '}') {
                depth--;
                if (depth == 0) {
                    return screen.substring(start, index + 1);
                }
            }
        }
        throw new AssertionError(functionName + " body must have balanced braces");
    }

    private static String screenSource() throws Exception {
        Path fromRoot = Paths.get(
                "app/src/main/java/name/caiyao/fakegps/ui/screen/map/MapScreen.kt");
        Path fromModule = Paths.get(
                "src/main/java/name/caiyao/fakegps/ui/screen/map/MapScreen.kt");
        Path source = Files.exists(fromRoot) ? fromRoot : fromModule;
        assertTrue("MapScreen.kt source must be available to the contract", Files.exists(source));
        return new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
    }

    private static String classBytecode(String className) throws Exception {
        String resource = className.replace('.', '/') + ".class";
        try (InputStream input = MapRecenterUiContractTest.class
                .getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(resource, input);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), StandardCharsets.ISO_8859_1);
        }
    }
}
