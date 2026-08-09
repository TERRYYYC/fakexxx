package name.caiyao.fakegps.data;

import name.caiyao.fakegps.BuildConfig;

/** Pure authority construction shared by manifest-driven app variants and the UriMatcher. */
public final class ProviderAuthority {
    private ProviderAuthority() {}

    public static final String AUTHORITY =
            forApplicationId(BuildConfig.APPLICATION_ID);

    public static String forApplicationId(String applicationId) {
        if (applicationId == null || applicationId.isEmpty()) {
            throw new IllegalArgumentException("applicationId must not be empty");
        }
        return applicationId + ".data.AppInfoProvider";
    }
}
