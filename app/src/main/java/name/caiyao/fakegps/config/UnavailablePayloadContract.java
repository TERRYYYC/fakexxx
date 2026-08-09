package name.caiyao.fakegps.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Validates the orthogonal {@code unavailable} set shared by Room publication and the hook reader.
 */
public final class UnavailablePayloadContract {

    private UnavailablePayloadContract() {}

    public static final class Validated {
        private final List<String> list;
        private final Set<String> set;

        private Validated(TreeSet<String> canonical) {
            list = Collections.unmodifiableList(new ArrayList<>(canonical));
            set = Collections.unmodifiableSet(new LinkedHashSet<>(canonical));
        }

        public List<String> asList() {
            return list;
        }

        public Set<String> asSet() {
            return set;
        }
    }

    /**
     * @throws IllegalArgumentException if the payload could change meaning by being partially read
     */
    public static Validated validate(Set<String> configuredFields, List<String> unavailable) {
        Set<String> fields = configuredFields == null
                ? Collections.emptySet() : new HashSet<>(configuredFields);
        List<String> requested = unavailable == null
                ? Collections.emptyList() : unavailable;

        Set<String> seen = new HashSet<>();
        TreeSet<String> canonical = new TreeSet<>();
        for (String field : requested) {
            if (field == null || field.trim().isEmpty()) {
                throw new IllegalArgumentException("blank unavailable field");
            }
            if (!seen.add(field)) {
                throw new IllegalArgumentException("duplicate unavailable field: " + field);
            }
            if (!UnavailableSpec.decidedColumns().contains(field)) {
                throw new IllegalArgumentException("unknown unavailable field: " + field);
            }
            if (!UnavailableSpec.supportsUnavailable(field)) {
                throw new IllegalArgumentException("unsupported unavailable field: " + field);
            }
            if (fields.contains(field)) {
                throw new IllegalArgumentException(
                        "field cannot be both spoofed and unavailable: " + field);
            }
            canonical.add(field);
        }
        if (canonical.contains("mcc") != canonical.contains("mnc")) {
            throw new IllegalArgumentException(
                    "PLMN unavailable must include both mcc and mnc");
        }
        return new Validated(canonical);
    }
}
