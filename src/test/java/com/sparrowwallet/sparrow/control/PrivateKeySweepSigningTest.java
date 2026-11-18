package com.sparrowwallet.sparrow.control;

import com.sparrowwallet.drongo.Coin;
import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.drongo.Utils;
import com.sparrowwallet.drongo.crypto.ECKey;
import com.sparrowwallet.drongo.policy.PolicyType;
import com.sparrowwallet.drongo.protocol.*;
import com.sparrowwallet.drongo.psbt.PSBT;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Sweep Private Key signs for the chain the app is connected to: on Luckycoin from height 276500 (mainnet always) its signatures commit to the
 * chain id, as Luckycoin nodes check them (see Coin.getSigHashPrefix). The signatures are checked here against preimages built in the test.
 */
public class PrivateKeySweepSigningTest {
    private static final byte[] LUCKYCOIN_CHAIN_ID = Utils.reverseBytes(Utils.hexToBytes("000000000000000000000000000000" + "4c55434b59434f494e5f5245564956414c"));
    private static final ECKey KEY = ECKey.fromPrivate(Utils.hexToBytes("2222222222222222222222222222222222222222222222222222222222222222"));

    @Test
    public void sweepSignsByTheChainItIsFor() throws Exception {
        Assertions.assertTrue(signsWith(Coin.LUCKYCOIN, Network.MAINNET, null, LUCKYCOIN_CHAIN_ID), "Luckycoin mainnet, chain id");
        Assertions.assertFalse(signsWith(Coin.LUCKYCOIN, Network.MAINNET, null, new byte[0]), "Luckycoin mainnet, not Bitcoin's hash");
        Assertions.assertTrue(signsWith(Coin.LUCKYCOIN, Network.TESTNET, 1000, new byte[0]), "Luckycoin test chain below the height, Bitcoin's hash");
        Assertions.assertTrue(signsWith(Coin.LUCKYCOIN, Network.TESTNET, 276500, LUCKYCOIN_CHAIN_ID), "Luckycoin test chain at the height, chain id");
        for(Coin coin : List.of(Coin.LITECOIN, Coin.DOGECOIN, Coin.PEPECOIN, Coin.DINGOCOIN, Coin.JUNKCOIN, Coin.BELLSCOIN)) {
            Assertions.assertTrue(signsWith(coin, Network.MAINNET, 5_000_000, new byte[0]), coin.getName() + ", Bitcoin's hash");
        }
    }

    @Test
    public void sweepPsbtCarriesItsCoin() {
        Coin previous = Coin.beginScope(Coin.LUCKYCOIN);
        try {
            Sweep sweep = sweep();
            PSBT psbt = PrivateKeySweepDialog.signSweep(sweep.transaction(), sweep.funding().getOutputs(), KEY, ScriptType.P2PKH, Coin.LUCKYCOIN, Network.MAINNET, null);
            Assertions.assertEquals(Coin.LUCKYCOIN, psbt.getCoin());
            Assertions.assertArrayEquals(LUCKYCOIN_CHAIN_ID, psbt.getSigHashPrefix());
        } finally {
            Coin.endScope(previous);
        }
    }

    private record Sweep(Transaction funding, Transaction transaction) {
    }

    /** A sweep of one P2PKH output to another key, built as the dialog builds it */
    private static Sweep sweep() {
        Transaction funding = new Transaction();
        funding.addInput(Sha256Hash.of(new byte[] {3}), 0, new Script(new byte[0]));
        funding.addOutput(500_000_000L, ScriptType.P2PKH.getOutputScript(KEY.getPubKeyHash()));
        funding = new Transaction(funding.bitcoinSerialize());

        Transaction noFee = new Transaction();
        ScriptType.P2PKH.addSpendingInput(PolicyType.SINGLE_HD, noFee, funding.getOutputs().getFirst(), ECKey.fromPublicOnly(KEY), TransactionSignature.dummy(TransactionSignature.Type.ECDSA));
        Transaction transaction = new Transaction();
        transaction.setVersion(2);
        for(TransactionInput input : noFee.getInputs()) {
            transaction.addInput(input);
        }
        transaction.addOutput(499_000_000L, ScriptType.P2PKH.getOutputScript(Utils.hexToBytes("cc00000000000000000000000000000000000033")));
        return new Sweep(funding, transaction);
    }

    private static boolean signsWith(Coin coin, Network network, Integer tip, byte[] chainId) throws Exception {
        Coin previous = Coin.beginScope(coin);
        try {
            Sweep sweep = sweep();
            PSBT psbt = PrivateKeySweepDialog.signSweep(sweep.transaction(), sweep.funding().getOutputs(), KEY, ScriptType.P2PKH, coin, network, tip);
            Transaction swept = psbt.extractTransaction();
            List<ScriptChunk> chunks = swept.getInputs().getFirst().getScriptSig().getChunks();
            TransactionSignature signature = TransactionSignature.decodeFromBitcoin(chunks.getFirst().data, false);

            //The legacy SIGHASH_ALL preimage, built here
            Transaction copy = new Transaction(swept.bitcoinSerialize(false));
            copy.getInputs().getFirst().setScriptBytes(sweep.funding().getOutputs().getFirst().getScript().getProgram());
            byte[] preimage = Utils.concat(copy.bitcoinSerialize(false), new byte[] {SigHash.ALL.value, 0, 0, 0});
            return ECKey.fromPublicOnly(chunks.get(1).data).verify(Sha256Hash.twiceOf(Utils.concat(chainId, preimage)), signature);
        } finally {
            Coin.endScope(previous);
        }
    }
}
