package com.sparrowwallet.sparrow.wallet.collectibles;

import com.sparrowwallet.drongo.Coin;
import com.sparrowwallet.drongo.KeyPurpose;
import com.sparrowwallet.drongo.ordinals.*;
import com.sparrowwallet.drongo.policy.Policy;
import com.sparrowwallet.drongo.policy.PolicyType;
import com.sparrowwallet.drongo.protocol.*;
import com.sparrowwallet.drongo.wallet.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class MintFlowTest {
    private static final List<String> WORDS = List.of("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".split(" "));
    private Coin previousCoin;
    private Wallet taproot;

    private static Wallet wallet(String name, ScriptType scriptType, KeystoreSource source) throws Exception {
        DeterministicSeed seed = new DeterministicSeed(new ArrayList<>(WORDS), "", 0, DeterministicSeed.Type.BIP39);
        Wallet wallet = new Wallet(name);
        wallet.setPolicyType(PolicyType.SINGLE);
        wallet.setScriptType(scriptType);
        Keystore keystore = Keystore.fromSeed(seed, scriptType.getDefaultDerivation());
        if(source != KeystoreSource.SW_SEED) {
            keystore.setSeed(null);
            keystore.setMasterPrivateExtendedKey(null);
            keystore.setSource(source);
        }
        wallet.getKeystores().add(keystore);
        wallet.setDefaultPolicy(Policy.getPolicy(PolicyType.SINGLE, scriptType, wallet.getKeystores(), 1));
        wallet.setCoin(Coin.LITECOIN);
        wallet.getNode(KeyPurpose.RECEIVE).fillToIndex(10);
        wallet.getNode(KeyPurpose.CHANGE).fillToIndex(10);
        return wallet;
    }

    @BeforeEach
    public void setUp() throws Exception {
        previousCoin = Coin.beginScope(Coin.LITECOIN);
        taproot = wallet("taproot", ScriptType.P2TR, KeystoreSource.SW_SEED);
    }

    @AfterEach
    public void tearDown() {
        Coin.endScope(previousCoin);
    }

    private Transaction commit(InscriptionMint.Plan plan) throws Exception {
        WalletNode node = taproot.getNode(KeyPurpose.CHANGE).getChildren().stream().reduce((a, b) -> b).orElseThrow();
        Transaction tx = new Transaction();
        tx.addInput(Sha256Hash.ZERO_HASH, 7, new Script(new byte[] {7}));
        tx.addOutput(200_000, node.getAddress());
        Sha256Hash hash = tx.getTxId();
        taproot.updateTransactions(Map.of(hash, new BlockTransaction(hash, 100, new Date(), 0L, tx)));
        BlockTransactionHashIndex utxo = new BlockTransactionHashIndex(hash, 100, new Date(), 0L, 0, 200_000);
        node.getTransactionOutputs().add(utxo);
        return InscriptionMint.commit(taproot, plan, List.of(new CollectibleTransactions.Pinned(utxo, node)), 2.0, 1.0).getTransaction();
    }

    @Test
    public void fundingWalletIsTheOpenPublicWallet() throws Exception {
        Wallet segwit = wallet("segwit", ScriptType.P2WPKH, KeystoreSource.SW_SEED);
        Assertions.assertSame(segwit, MintFlow.fundingWallet(segwit));
        Assertions.assertSame(taproot, MintFlow.fundingWallet(taproot));
        Wallet legacy = wallet("legacy", ScriptType.P2PKH, KeystoreSource.SW_SEED);
        Assertions.assertSame(legacy, MintFlow.fundingWallet(legacy));
    }

    @Test
    public void hardwareAndWatchOnlyAreRefused() throws Exception {
        Assertions.assertTrue(MintFlow.refusal(taproot).isEmpty());
        Assertions.assertTrue(MintFlow.refusal(wallet("segwit", ScriptType.P2WPKH, KeystoreSource.SW_SEED)).isEmpty());

        String hardware = MintFlow.refusal(wallet("hw", ScriptType.P2WPKH, KeystoreSource.HW_USB)).orElseThrow();
        Assertions.assertTrue(hardware.contains("hardware wallet"), hardware);
        String watch = MintFlow.refusal(wallet("watch", ScriptType.P2WPKH, KeystoreSource.SW_WATCH)).orElseThrow();
        Assertions.assertTrue(watch.contains("watch-only"), watch);

        Wallet dogecoin = wallet("doge", ScriptType.P2PKH, KeystoreSource.SW_SEED);
        dogecoin.setCoin(Coin.DOGECOIN);
        Assertions.assertTrue(MintFlow.refusal(dogecoin).orElseThrow().contains("Litecoin"));
    }

    @Test
    public void pendingRecordHoldsOnlyRecoveryData() throws Exception {
        WalletNode node = taproot.getFreshNode(KeyPurpose.RECEIVE);
        InscriptionMint.Plan plan = MintFlow.plan(node, Inscription.text("hello litecoin"), null, 2.0);
        Assertions.assertEquals(node.getAddress(), plan.destination());
        Assertions.assertArrayEquals(node.getPubKey().getPubKeyXCoord(), plan.revealKey().getPubKeyXCoord());

        Transaction commit = commit(plan);
        PendingMint mint = MintFlow.pendingRecord(plan, node, commit, null);
        Assertions.assertEquals(PendingMint.Kind.INSCRIPTION, mint.getKind());
        Assertions.assertEquals(node.getDerivationPath(), mint.getKeyPath());
        Assertions.assertArrayEquals(plan.scriptHash(), mint.getScriptHash());
        Assertions.assertEquals(commit.getTxId(), mint.getCommitTxid());
        Assertions.assertEquals(0, mint.getCommitVout());
        Assertions.assertEquals(plan.commitValue(), mint.getCommitValue());
        Assertions.assertEquals(Inscription.TEXT, mint.getContentType());
        Assertions.assertNull(mint.getEtching());
    }

    @Test
    public void finishChecksTheContent() throws Exception {
        WalletNode node = taproot.getFreshNode(KeyPurpose.RECEIVE);
        byte[] content = "the original".getBytes(StandardCharsets.UTF_8);
        InscriptionMint.Plan plan = MintFlow.plan(node, new Inscription("text/plain;charset=utf-8", content, null), null, 3.0);
        Transaction commit = commit(plan);
        PendingMint mint = MintFlow.pendingRecord(plan, node, commit, null);

        InscriptionMint.Plan rebuilt = MintFlow.rebuild(mint, taproot, content);
        Assertions.assertEquals(plan.commitAddress(), rebuilt.commitAddress());
        Assertions.assertEquals(node.getAddress(), rebuilt.destination());
        Assertions.assertDoesNotThrow(() -> MintFlow.checkCommit(mint, rebuilt, commit.getOutputs().get(0)));

        IllegalArgumentException wrong = Assertions.assertThrows(IllegalArgumentException.class,
                () -> MintFlow.rebuild(mint, taproot, "something else".getBytes(StandardCharsets.UTF_8)));
        Assertions.assertTrue(wrong.getMessage().contains("not the content"), wrong.getMessage());

        IllegalArgumentException replaced = Assertions.assertThrows(IllegalArgumentException.class,
                () -> MintFlow.checkCommit(mint, rebuilt, commit.getOutputs().get(1)));
        Assertions.assertTrue(replaced.getMessage().contains("replaced"), replaced.getMessage());
    }

    @Test
    public void etchWaitsForSixConfirmations() throws Exception {
        WalletNode node = taproot.getFreshNode(KeyPurpose.RECEIVE);
        Runestone.Etching etching = new Runestone.Etching(RuneName.parse("SPARROW•SCRYPT•TEST"), 2, (int)'S', BigInteger.valueOf(1_000), null, false);
        Inscription inscription = MintFlow.etchInscription(etching, null, null);
        Assertions.assertEquals(Inscription.TEXT, inscription.contentType());
        Assertions.assertArrayEquals("SPARROW•SCRYPT•TEST".getBytes(StandardCharsets.UTF_8), inscription.body());
        Assertions.assertArrayEquals(etching.name().commitment(), inscription.runeCommitment());

        InscriptionMint.Plan plan = MintFlow.plan(node, inscription, etching, 2.0);
        PendingMint mint = MintFlow.pendingRecord(plan, node, commit(plan), etching);
        Assertions.assertEquals(PendingMint.Kind.ETCH, mint.getKind());
        Assertions.assertEquals(etching, mint.getEtching());
        for(int confirmations = 0; confirmations < InscriptionMint.COMMIT_CONFIRMATIONS; confirmations++) {
            String wait = MintFlow.revealWait(mint, confirmations).orElseThrow();
            Assertions.assertTrue(wait.contains(confirmations + " of 6"), wait);
        }
        Assertions.assertTrue(MintFlow.revealWait(mint, 6).isEmpty());

        //The default etch body is rebuilt from the record alone
        Assertions.assertEquals(plan.commitAddress(), MintFlow.rebuild(mint, taproot, null).commitAddress());

        PendingMint text = MintFlow.pendingRecord(MintFlow.plan(node, Inscription.text("x"), null, 2.0), node, commit(plan), null);
        Assertions.assertTrue(MintFlow.revealWait(text, 0).isEmpty());

        byte[] image = new byte[] {1, 2, 3};
        Inscription withImage = MintFlow.etchInscription(etching, "image/png", image);
        Assertions.assertEquals("image/png", withImage.contentType());
        Assertions.assertArrayEquals(image, withImage.body());
    }
}
