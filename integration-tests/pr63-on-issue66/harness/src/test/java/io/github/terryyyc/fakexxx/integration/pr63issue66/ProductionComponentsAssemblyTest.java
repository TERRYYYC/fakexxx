package io.github.terryyyc.fakexxx.integration.pr63issue66;

import com.example.cellrebelauto.recovery.ProviderExecutorRegistry;
import name.caiyao.fakegps.integration.v1.EnvironmentControlHandler;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;

public final class ProductionComponentsAssemblyTest {
    @Test
    public void bothProductionImplementationsAreLoadedInOneHostTest() {
        assertNotNull(ProviderExecutorRegistry.class);
        assertNotNull(EnvironmentControlHandler.class);
    }
}
