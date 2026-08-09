package name.caiyao.fakegps.ui;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

/** Compiled contract for user actions that must never own concurrent mutations. */
public class UiActionOwnershipContractTest {

    @Test
    public void editorSaveActionsShareOneSingleFlightOwner() throws Exception {
        String owner = classBytecode(
                "name.caiyao.fakegps.ui.screen.editor.ProfileEditorViewModel");
        String saveAttempt = classBytecode(
                "name.caiyao.fakegps.ui.screen.editor.ProfileEditorViewModel$save$1");

        assertTrue(owner.contains("SingleFlightGate"));
        assertTrue(owner.contains("tryStart"));
        assertFalse(saveAttempt.contains("tryStart"));
        assertTrue(saveAttempt.contains("finish"));
        assertTrue(owner.contains("getImmediate"));
        assertTrue(owner.contains("saving"));
    }

    @Test
    public void verifyRefreshUsesTheSameSingleFlightPrimitive() throws Exception {
        String verify = classBytecode(
                "name.caiyao.fakegps.ui.screen.verify.VerifyViewModel")
                + classBytecode(
                        "name.caiyao.fakegps.ui.screen.verify.VerifyViewModel$refresh$1")
                + classBytecode(
                        "name.caiyao.fakegps.ui.screen.verify.VerifyViewModel$refresh$1$1");

        assertTrue(verify.contains("SingleFlightGate"));
        assertTrue(verify.contains("tryStart"));
        assertTrue(verify.contains("finish"));
    }

    @Test
    public void collectionImportOwnsDocumentPreviewAndExplicitConfirmation() throws Exception {
        String screen = classBytecode(
                "name.caiyao.fakegps.ui.screen.collection.CollectionScreenKt");
        String viewModel = classBytecode(
                "name.caiyao.fakegps.ui.screen.collection.CollectionViewModel")
                + classBytecode(
                        "name.caiyao.fakegps.ui.screen.collection.CollectionViewModel$confirmImport$1")
                + classBytecode(
                        "name.caiyao.fakegps.ui.screen.collection.CollectionViewModel$confirmImport$1$1$1");

        assertTrue(screen.contains("OpenDocument"));
        assertTrue(screen.contains("CreateDocument"));
        assertTrue(screen.contains("text/csv"));
        assertTrue(screen.contains("ProfileImportDialogs"));
        assertTrue(screen.contains("ProfileImportUiState$Preview"));
        assertTrue(viewModel.contains("previewImport"));
        assertTrue(viewModel.contains("saveImportTemplate"));
        assertTrue(viewModel.contains("beginImport"));
        assertTrue(viewModel.contains("importAll"));
    }

    @Test
    public void templateWriteOwnsOnlyTheSelectedDocumentUri() throws Exception {
        String templateWrite = classBytecode(
                "name.caiyao.fakegps.ui.screen.collection.CollectionViewModel$saveImportTemplate$1")
                + classBytecode(
                        "name.caiyao.fakegps.ui.screen.collection.CollectionViewModel$saveImportTemplate$1$result$1");

        assertTrue(templateWrite.contains("ContentResolver"));
        assertTrue(templateWrite.contains("ProfileImportTemplate"));
        assertFalse(templateWrite.contains("ProfileRepository"));
        assertFalse(templateWrite.contains("ConfigPrefsSync"));
        assertFalse(templateWrite.contains("AppDatabase"));
    }

    private static String classBytecode(String className) throws Exception {
        String resource = className.replace('.', '/') + ".class";
        try (InputStream input = UiActionOwnershipContractTest.class
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
