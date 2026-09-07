package name.caiyao.fakegps.oracle;

import android.os.Bundle;
import android.os.IBinder;

/** QWY-internal, synchronous continuity oracle. Not part of Environment Control v1. */
interface IAuthoritativeContinuityOracle {
    Bundle snapshot();
    void registerQwySession(String semanticDigest, IBinder clientDeathToken);
    long beginQwySemanticMutation(
        String mutationId,
        String beforeDigest,
        IBinder clientDeathToken
    );
    void finishQwySemanticMutation(
        long token,
        boolean changed,
        boolean uncertain,
        String afterDigest
    );
}
