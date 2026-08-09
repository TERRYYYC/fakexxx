package name.caiyao.fakegps.verify;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

/** Compiled production census for the private release probe path. */
public class RuntimeProbeWiringContractTest {

    @Test
    public void mainHookInstallsTargetClassLoaderSentinelAndUsesProcessPolicy() throws Exception {
        String hook = classBytecode("name.caiyao.fakegps.hook.MainHook");
        assertTrue(hook.contains("RuntimeSelfHookPolicy"));
        assertTrue(hook.contains("RuntimeHookSentinel"));
        assertTrue(hook.contains("isHookActive"));
        assertTrue(hook.contains("reloadHookSnapshot"));
        assertTrue(hook.contains("reloadSnapshotForProbe"));
        assertTrue(hook.contains("CURRENT_FINGERPRINT"));
    }

    @Test
    public void clientUsesCrossProcessResultReceiverAndPrivateService() throws Exception {
        String client = classBytecode("name.caiyao.fakegps.verify.HookVerificationClient")
                + classBytecode(
                        "name.caiyao.fakegps.verify.HookVerificationClient$request$result$1")
                + classBytecode(
                        "name.caiyao.fakegps.verify.HookVerificationClient$request$result$1$1$receiver$1");
        assertTrue(client.contains("HookVerificationService"));
        assertTrue(client.contains("probe.receiver"));
        assertTrue(client.contains("startService"));
        assertTrue(client.contains("cancelIntent"));
        assertFalse(client.contains("stopService"));
        assertTrue(client.contains("probeRequested"));
        assertTrue(client.contains("ProbeResultCorrelation"));
    }

    @Test
    public void serviceChecksScopeReadsPublishedPayloadAndObservesPublicApis() throws Exception {
        String service = classBytecode("name.caiyao.fakegps.verify.HookVerificationService");
        assertTrue(service.contains("ResultReceiver"));
        assertTrue(service.contains("RuntimeHookSentinel"));
        assertTrue(service.contains("reloadHookSnapshot"));
        assertTrue(service.contains("ConfigPrefsSync"));
        assertTrue(service.contains("DeviceObserver"));
        assertTrue(service.contains("ProbeObservationCodec"));
        assertTrue(service.contains("killProcess"));
        assertTrue(service.contains("DeferredProcessTermination"));
        assertTrue(service.contains("ProbeExecutionRegistry"));
        assertTrue(service.contains("cancelPending"));
        assertTrue(service.contains("schedule"));
        assertFalse(service.contains("probeDelivered"));
    }

    @Test
    public void verifyViewModelOnlyBuildsRuntimeVerdictsThroughProbeDecision() throws Exception {
        String viewModel = classBytecode(
                "name.caiyao.fakegps.ui.screen.verify.VerifyViewModel")
                + classBytecode(
                        "name.caiyao.fakegps.ui.screen.verify.VerifyViewModel$refresh$1")
                + classBytecode(
                        "name.caiyao.fakegps.ui.screen.verify.VerifyViewModel$refresh$1$1");
        assertTrue(viewModel.contains("HookVerificationClient"));
        assertTrue(viewModel.contains("VerificationRequestCoordinator"));
        assertTrue(viewModel.contains("ProbeVerificationDecision"));
        assertTrue(viewModel.contains("SingleFlightGate"));
        assertTrue(viewModel.contains("tryStart"));
        assertTrue(viewModel.contains("finish"));
        assertTrue(viewModel.contains("getImmediate"));
        assertTrue(viewModel.contains("HOOK_PROBE"));
        assertTrue(viewModel.contains("probeDelivered"));
        assertTrue(viewModel.contains("probeFailed"));
    }

    private static String classBytecode(String className) throws Exception {
        String resource = className.replace('.', '/') + ".class";
        try (InputStream input = RuntimeProbeWiringContractTest.class
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
