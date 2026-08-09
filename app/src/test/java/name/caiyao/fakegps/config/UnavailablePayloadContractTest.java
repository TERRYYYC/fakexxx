package name.caiyao.fakegps.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.Test;

public class UnavailablePayloadContractTest {

    @Test
    public void plmnUnavailableDecisionMustBePaired() {
        assertInvalid(Collections.emptySet(), Collections.singletonList("mcc"), "PLMN");
        assertInvalid(Collections.emptySet(), Collections.singletonList("mnc"), "PLMN");
        UnavailablePayloadContract.Validated paired = UnavailablePayloadContract.validate(
                Collections.emptySet(), Arrays.asList("mcc", "mnc"));
        assertEquals(new LinkedHashSet<>(Arrays.asList("mcc", "mnc")), paired.asSet());
    }

    @Test
    public void validFieldsAreCanonicalSortedAndImmutable() {
        UnavailablePayloadContract.Validated v = UnavailablePayloadContract.validate(
                Collections.singleton("operator_name"),
                Arrays.asList("tac", "lac"));

        assertEquals(Arrays.asList("lac", "tac"), v.asList());
        assertEquals(new LinkedHashSet<>(Arrays.asList("lac", "tac")), v.asSet());
        assertThrows(UnsupportedOperationException.class, () -> v.asSet().add("ci"));
    }

    @Test
    public void duplicateUnavailableFieldIsRejected() {
        assertInvalid(Collections.emptySet(), Arrays.asList("tac", "tac"), "duplicate");
    }

    @Test
    public void fieldCannotBeSpoofedAndUnavailableAtOnce() {
        assertInvalid(Collections.singleton("tac"), Collections.singletonList("tac"), "both");
    }

    @Test
    public void unknownAndUnsupportedFieldsAreRejected() {
        assertInvalid(Collections.emptySet(), Collections.singletonList("tacc"), "unknown");
        assertInvalid(Collections.emptySet(), Collections.singletonList("is_roaming"), "unsupported");
        assertInvalid(Collections.emptySet(), Collections.singletonList("wifi_rssi"), "unsupported");
    }

    @Test
    public void nullAndBlankNamesAreRejected() {
        assertInvalid(Collections.emptySet(), Collections.singletonList(null), "blank");
        assertInvalid(Collections.emptySet(), Collections.singletonList(" "), "blank");
    }

    private static void assertInvalid(Set<String> fields, java.util.List<String> unavailable,
                                      String messagePart) {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> UnavailablePayloadContract.validate(fields, unavailable));
        assertFalse(ex.getMessage(), ex.getMessage() == null);
        org.junit.Assert.assertTrue(ex.getMessage(), ex.getMessage().contains(messagePart));
    }
}
