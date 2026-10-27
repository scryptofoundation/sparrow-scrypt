package com.sparrowwallet.sparrow.wallet;

import com.sparrowwallet.drongo.wallet.Status;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class StatusTransitionsTest {
    @Test
    public void protectedCannotBeFrozenOrUnfrozen() {
        Assertions.assertFalse(StatusTransitions.canFreeze(Status.PROTECTED));
        Assertions.assertFalse(StatusTransitions.canUnfreeze(Status.PROTECTED));
        Assertions.assertFalse(StatusTransitions.canFreeze(Status.FROZEN));
        Assertions.assertTrue(StatusTransitions.canFreeze(null));
        Assertions.assertTrue(StatusTransitions.canFreeze(Status.RELEASED));
        Assertions.assertTrue(StatusTransitions.canUnfreeze(Status.FROZEN));
    }

    @Test
    public void bip329ImportNeverTouchesProtected() {
        Assertions.assertEquals(Status.PROTECTED, StatusTransitions.fromBip329(Status.PROTECTED, true));
        Assertions.assertEquals(Status.PROTECTED, StatusTransitions.fromBip329(Status.PROTECTED, false));
        Assertions.assertEquals(Status.FROZEN, StatusTransitions.fromBip329(null, false));
        Assertions.assertEquals(Status.FROZEN, StatusTransitions.fromBip329(Status.RELEASED, false));
        Assertions.assertNull(StatusTransitions.fromBip329(Status.FROZEN, true));
        Assertions.assertNull(StatusTransitions.fromBip329(null, true));
        Assertions.assertEquals(Status.RELEASED, StatusTransitions.fromBip329(Status.RELEASED, true));
    }
}
