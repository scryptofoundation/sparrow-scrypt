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
        //Past the refusal it goes on to the device, of which there is none here, so it fails there and not with the refusal
        Throwable failure = Assertions.assertThrows(Throwable.class, () -> new Hwi().signPSBT(null, null, psbt, null, null, null));
        Assertions.assertNotEquals(Hwi.LUCKYCOIN_HARDWARE_SIGNING_REFUSED, failure.getMessage());
        Assertions.assertInstanceOf(NullPointerException.class, failure, String.valueOf(failure));
    }

    /**
     * The path DevicePane takes before a USB device signs (Hwi.checkForDevice), with no stamping by hand: the signing wallet puts its chain on the
     * PSBT, so an unmarked Luckycoin PSBT from another app is refused for a Luckycoin wallet; a PSBT marked for another coin is refused with the
     * coin mismatch; a Junkcoin wallet's own PSBT goes on to the device.
     */
    @Test
    public void devicePathStampsTheSigningWalletsChain() throws Exception {
        PSBT luckycoin = psbt(Coin.LUCKYCOIN);
        PSBT unmarked = new PSBT(luckycoin.serialize(), false);
        unmarked.getGlobalProprietary().remove(PSBT.COIN_MARK_KEY);
        Assertions.assertEquals(0, unmarked.getSigHashPrefix().length);
        SignTransactionException refused = Assertions.assertThrows(SignTransactionException.class, () -> Hwi.checkForDevice(wallet(Coin.LUCKYCOIN), unmarked));
        Assertions.assertEquals(Hwi.LUCKYCOIN_HARDWARE_SIGNING_REFUSED, refused.getMessage());

        PSBT junkcoin = psbt(Coin.JUNKCOIN);
        SignTransactionException mismatch = Assertions.assertThrows(SignTransactionException.class, () -> Hwi.checkForDevice(wallet(Coin.LUCKYCOIN), junkcoin));
        Assertions.assertEquals(String.format(Wallet.PSBT_COIN_MISMATCH, "Junkcoin", "Luckycoin"), mismatch.getMessage());
        SignTransactionException reverse = Assertions.assertThrows(SignTransactionException.class, () -> Hwi.checkForDevice(wallet(Coin.JUNKCOIN), new PSBT(luckycoin.serialize(), false)));
        Assertions.assertEquals(String.format(Wallet.PSBT_COIN_MISMATCH, "Luckycoin", "Junkcoin"), reverse.getMessage());
        Assertions.assertDoesNotThrow(() -> Hwi.checkForDevice(wallet(Coin.JUNKCOIN), junkcoin));
    }

    private static Wallet wallet(Coin coin) {
        Wallet wallet = new Wallet(coin.getName());
        wallet.setCoin(coin);
        return wallet;
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
