package name.caiyao.fakegps.data;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ProviderAuthorityTest {
    @Test
    public void authorityFollowsEachInstalledApplicationId() {
        assertEquals(
                "name.caiyao.fakegps.data.AppInfoProvider",
                ProviderAuthority.forApplicationId("name.caiyao.fakegps"));
        assertEquals(
                "name.caiyao.fakegps.bench.data.AppInfoProvider",
                ProviderAuthority.forApplicationId("name.caiyao.fakegps.bench"));
    }
}
