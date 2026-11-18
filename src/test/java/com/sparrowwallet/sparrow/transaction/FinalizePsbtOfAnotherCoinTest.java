package com.sparrowwallet.sparrow.transaction;

import com.sparrowwallet.drongo.Coin;
import com.sparrowwallet.drongo.KeyPurpose;
import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.drongo.Utils;
import com.sparrowwallet.drongo.address.P2PKHAddress;
import com.sparrowwallet.drongo.policy.Policy;
import com.sparrowwallet.drongo.policy.PolicyType;
import com.sparrowwallet.drongo.protocol.Script;
import com.sparrowwallet.drongo.protocol.ScriptType;
import com.sparrowwallet.drongo.protocol.Sha256Hash;
import com.sparrowwallet.drongo.protocol.Transaction;
import com.sparrowwallet.drongo.psbt.PSBT;
import com.sparrowwallet.drongo.wallet.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * The transaction tab finalizes a signed PSBT with the wallet it is shown with. A PSBT of another coin is refused (Wallet.PSBT_COIN_MISMATCH) with its
 * message, rather than an exception on the FX thread, and is left as it was.
 */
public class FinalizePsbtOfAnotherCoinTest {
    private static final int HEIGHT = 800000;

    private Network previousNetwork;

    @BeforeEach
    public void setUp() {
        previousNetwork = Network.get();
        Network.set(Network.MAINNET);
    }

    @AfterEach
    public void tearDown() {
        Network.set(previousNetwork);
    }

    @Test
    public void psbtOfAnotherCoinIsRefusedWithItsMessage() throws Exception {
        Wallet junkcoin = wallet(Coin.JUNKCOIN);
        Wallet luckycoin = wallet(Coin.LUCKYCOIN);
        PSBT psbt = psbt(junkcoin);
        Assertions.assertEquals(String.format(Wallet.PSBT_COIN_MISMATCH, "Junkcoin", "Luckycoin"), HeadersController.finalise(luckycoin, psbt));
        Assertions.assertFalse(psbt.isFinalized());
        Assertions.assertEquals(Coin.JUNKCOIN, psbt.getCoin());
    }

    private static Wallet wallet(Coin coin) throws Exception {
        Coin scope = Coin.beginScope(coin);
        try {
            Wallet wallet = new Wallet(coin.getName());
            wallet.setCoin(coin);
            wallet.setPolicyType(PolicyType.SINGLE_HD);
            wallet.setScriptType(ScriptType.P2PKH);
            DeterministicSeed seed = new DeterministicSeed(Utils.hexToBytes(String.format("%032x", 1)), "", 0);
            wallet.getKeystores().add(Keystore.fromSeed(seed, PolicyType.SINGLE_HD, ScriptType.P2PKH.getDefaultDerivation()));
            wallet.setDefaultPolicy(Policy.getPolicy(PolicyType.SINGLE_HD, ScriptType.P2PKH, wallet.getKeystores(), null));
            wallet.setStoredBlockHeight(HEIGHT + 6);
            wallet.getNode(KeyPurpose.RECEIVE).fillToIndex(0);
            return wallet;
        } finally {
            Coin.endScope(scope);
        }
    }

    /** The wallet's own spend, signed, as the transaction tab holds it once every signature is in */
    private static PSBT psbt(Wallet wallet) throws Exception {
        Coin scope = Coin.beginScope(wallet.getCoin());
        try {
            WalletNode addressNode = wallet.getNode(KeyPurpose.RECEIVE).getChildren().iterator().next();
            Transaction funding = new Transaction();
            funding.addInput(Sha256Hash.of(new byte[] {1}), 0, new Script(new byte[0]));
            funding.addOutput(5_000_000_000L, wallet.getAddress(addressNode));
            wallet.updateTransactions(Map.of(funding.getTxId(), new BlockTransaction(funding.getTxId(), HEIGHT, new Date(), 0L, funding)));
            addressNode.getTransactionOutputs().add(new BlockTransactionHashIndex(funding.getTxId(), HEIGHT, new Date(), 0L, 0, 5_000_000_000L));

            TransactionParameters params = new TransactionParameters(List.of(new PriorityUtxoSelector(HEIGHT + 6)), Collections.emptyList(),
                    List.of(new Payment(new P2PKHAddress(Utils.hexToBytes("cc00000000000000000000000000000000000033")), "test", 2_000_000_000L, false)),
                    Collections.emptyList(), Collections.emptySet(), 10.0d, 1.0d, 1.0d, null, HEIGHT + 6, false, false, true,
                    (w, utxos, outputs, vSize, rate) -> 0L);
            PSBT psbt = wallet.createWalletTransaction(params).createPSBT();
            wallet.sign(psbt);
            Assertions.assertTrue(psbt.isSigned());
            return psbt;
        } finally {
            Coin.endScope(scope);
        }
    }
}
