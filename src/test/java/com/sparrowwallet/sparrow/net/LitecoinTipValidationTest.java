package com.sparrowwallet.sparrow.net;

import com.sparrowwallet.drongo.Network;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class LitecoinTipValidationTest {
    //Litecoin mainnet genesis: its scrypt proof of work does not meet the double SHA256 check, so a tip like it must still be accepted
    private static final String GENESIS = "010000000000000000000000000000000000000000000000000000000000000000000000d9ced4ed1130f7b7faad9be25323ffafa33232a17c3edf6cfd97bee6bafbdd97b9aa8e4ef0ff0f1ecd513f7c";

    @Test
    public void litecoinTipIsAccepted() {
        BlockHeaderTip tip = new BlockHeaderTip();
        tip.height = 0;
        tip.hex = GENESIS;
        Assertions.assertFalse(tip.getBlockHeader().verifyProofOfWork());
        Assertions.assertNull(ElectrumServer.getTipValidationError(tip));
        Assertions.assertNull(ElectrumServer.getAnnouncedTipValidationError(tip));
    }

    @Test
    public void litecoinDoesNotVerifyTransactions() {
        Assertions.assertFalse(Network.get().supportsHeaderVerification());
        Assertions.assertFalse(ElectrumServer.isVerifyingTransactions());
        Assertions.assertFalse(ElectrumServer.isVerificationMandatory());
    }
}