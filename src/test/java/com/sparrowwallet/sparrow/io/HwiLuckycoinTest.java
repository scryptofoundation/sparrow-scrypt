package com.sparrowwallet.sparrow.io;

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
 * A hardware wallet works out the signature hash on the device, by Bitcoin's rule, so a Luckycoin transaction past the height its own rule starts at
 * (Coin.getSigHashPrefix) is never sent to one. Software signing is untouched.
 */
public class HwiLuckycoinTest {
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
    public void luckycoinPsbtIsNotSentToAHardwareWallet() throws Exception {
        PSBT luckycoin = psbt(Coin.LUCKYCOIN);
        Assertions.assertTrue(luckycoin.getSigHashPrefix().length > 0);
        //Refused before anything reaches a device: there is no device here at all
        SignTransactionException e = Assertions.assertThrows(SignTransactionException.class, () -> new Hwi().signPSBT(null, null, luckycoin, null, null, null));
        Assertions.assertEquals(Hwi.LUCKYCOIN_HARDWARE_SIGNING_REFUSED, e.getMessage());
        //Read back from its serialization, as a PSBT opened from a file is, it is still refused by its coin mark
        PSBT read = new PSBT(luckycoin.serialize(), false);
        Assertions.assertThrows(SignTransactionException.class, () -> new Hwi().signPSBT(null, null, read, null, null, null));
    }

    @Test
    public void otherCoinsKeepBitcoinsSignatureHash() throws Exception {
        for(Coin coin : List.of(Coin.LITECOIN, Coin.DOGECOIN, Coin.PEPECOIN, Coin.BELLSCOIN, Coin.DINGOCOIN, Coin.JUNKCOIN)) {
            Assertions.assertEquals(0, psbt(coin).getSigHashPrefix().length, coin.getName());
        }
    }

    @Test
    public void luckycoinTestChainBelowTheHeightIsStillSentToAHardwareWallet() throws Exception {
        Network.set(Network.TESTNET);
        PSBT psbt = psbt(Coin.LUCKYCOIN, 1000);
        Assertions.assertEquals(0, psbt.getSigHashPrefix().length);
    }

    private static PSBT psbt(Coin coin) throws Exception {
        return psbt(coin, HEIGHT + 6);
    }

    private static PSBT psbt(Coin coin, int tip) throws Exception {
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
            WalletTransaction walletTransaction = wallet.createWalletTransaction(params);
            wallet.setStoredBlockHeight(tip);
            return walletTransaction.createPSBT();
        } finally {
            Coin.endScope(scope);
        }
    }
}
