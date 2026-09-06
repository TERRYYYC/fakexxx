package name.caiyao.fakegps.oracle;

import name.caiyao.fakegps.oracle.IAuthoritativeContinuityOracle;

/** Explicit system_server -> QWY hand-off. Implementations must accept only UID 1000. */
interface IContinuityOracleRegistrar {
    void registerOracle(IAuthoritativeContinuityOracle oracle);
}
