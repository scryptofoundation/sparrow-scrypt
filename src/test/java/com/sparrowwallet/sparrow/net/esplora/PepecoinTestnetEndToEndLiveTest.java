package com.sparrowwallet.sparrow.net.esplora;

import com.sparrowwallet.drongo.Coin;
import com.sparrowwallet.drongo.KeyPurpose;
import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.drongo.address.Address;
import com.sparrowwallet.drongo.policy.Policy;
import com.sparrowwallet.drongo.policy.PolicyType;
import com.sparrowwallet.drongo.protocol.ScriptType;
import com.sparrowwallet.drongo.protocol.Sha256Hash;
import com.sparrowwallet.drongo.protocol.Transaction;
import com.sparrowwallet.drongo.protocol.TransactionOutput;
import com.sparrowwallet.drongo.psbt.PSBT;
import com.sparrowwallet.drongo.wallet.*;
import com.sparrowwallet.sparrow.SparrowWallet;
import com.sparrowwallet.sparrow.mweb.MwebFeeEstimator;
import com.sparrowwallet.sparrow.net.ElectrumServer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.*;

/**
 * End-to-end Pepecoin testnet run through the app's own code, with external signing:
 *
 * 1. A signer wallet (seed) and a separate watch-only wallet (its xpub only) - the watch-only wallet never sees a key.
 * 2. The watch-only wallet syncs from the public testnet Esplora server through ElectrumServer's real history path
 *    (getHistory, getReferencedTransactions, calculateNodeHistory), exactly as TransactionHistoryService does.
 * 3. It builds a transaction with the send screen's selectors, filters and fee estimator, exports the unsigned PSBT
 *    to a file; the signer loads that file, signs and finalises, and writes the signed PSBT back; the watch-only
 *    wallet loads it, extracts the transaction and broadcasts it through ElectrumServer, then resyncs and finds it.
 *
 * Throwaway TESTNET wallets only, never the user's real wallets. Opt-in: set PEP_E2E_DIR to a scratch folder. The
 * first run creates the test seed there and prints the address to fund (e.g. from testnet.pepeblocks.com/faucet);
 * runs after funding perform the spend. The app home is pointed at that folder, so no real config is read or written.
 */
public class PepecoinTestnetEndToEndLiveTest {
    private static final String SERVER = "https://pepe-testnet-api.nintondo.io";
    private static final long SEND_AMOUNT = 100L * 100_000_000L; //100 test PEP, far above the 0.01 PEP dust limit

    @Test
    public void receiveBuildSignExternallyAndBroadcast() throws Exception {
        String dirName = System.getenv("PEP_E2E_DIR");
        Assumptions.assumeTrue(dirName != null && !dirName.isEmpty(), "set PEP_E2E_DIR to run the Pepecoin testnet end-to-end test");
        Path dir = Path.of(dirName);
        Files.createDirectories(dir.resolve("home"));
        System.setProperty(SparrowWallet.APP_HOME_PROPERTY, dir.resolve("home").toString());

        Network previousNetwork = Network.get();
        Coin previousCoin = Coin.beginScope(Coin.PEPECOIN);
        Network.set(Network.TESTNET);
        try {
            run(dir);
        } finally {
            Network.set(previousNetwork);
            Coin.endScope(previousCoin);
        }
    }

    private void run(Path dir) throws Exception {
        //1. Signer (seed) and watch-only (xpub) wallets
        Path seedFile = dir.resolve("testnet-seed.txt");
        if(!Files.exists(seedFile)) {
            DeterministicSeed fresh = new DeterministicSeed(new SecureRandom(), 128, "");
            Files.writeString(seedFile, String.join(" ", fresh.getMnemonicCode()), StandardCharsets.UTF_8);
        }
        List<String> words = Arrays.asList(Files.readString(seedFile, StandardCharsets.UTF_8).trim().split(" "));
        Wallet signer = signerWallet(words);
        Wallet watch = watchOnlyWallet(signer);
        Assertions.assertFalse(watch.getKeystores().getFirst().hasPrivateKey(), "watch-only wallet must hold no private key");
        Assertions.assertEquals("m/44'/1'/0'", watch.getKeystores().getFirst().getKeyDerivation().getDerivationPath());

        //Before any history is loaded the fresh receive node is index 0, the address to fund
        WalletNode fundingNode = watch.getFreshNode(KeyPurpose.RECEIVE);
        Assertions.assertEquals(0, fundingNode.getIndex());
        Address fundingAddress = watch.getAddress(fundingNode);
        Assertions.assertTrue(fundingAddress.toString().startsWith("n"), "Pepecoin testnet P2PKH address: " + fundingAddress);
        log("funding address (receive 0): " + fundingAddress);

        //2. Sync through the app's real history path against the testnet Esplora server
        EsploraClient client = new EsploraClient(SERVER);
        connectEsplora();
        int tip = client.getTipHeight();
        watch.setStoredBlockHeight(tip);
        sync(watch);
        long balance = watch.getWalletUtxos().keySet().stream().mapToLong(BlockTransactionHashIndex::getValue).sum();
        log("tip " + tip + ", watch-only balance " + balance + " sats, utxos " + watch.getWalletUtxos().size());
        Assumptions.assumeTrue(balance > SEND_AMOUNT * 2, "not funded yet: send at least 200 test PEP to " + fundingAddress);

        //3. Build with the send screen's machinery: a self-send to a fresh receive address, change back to the wallet
        Address destination = watch.getFreshNode(KeyPurpose.RECEIVE).getAddress();
        List<Payment> payments = List.of(new Payment(destination, "e2e self-send", SEND_AMOUNT, false));
        double feeRate = Coin.PEPECOIN.getRecommendedFeeRate();
        double minRelayFeeRate = Coin.PEPECOIN.getMinRelayFeeRate();
        long noInputsFee = watch.getNoInputsFee(payments, feeRate);
        long costOfChange = watch.getCostOfChange(feeRate, minRelayFeeRate);
        TransactionParameters params = new TransactionParameters(
                List.of(new BnBUtxoSelector(noInputsFee, costOfChange), new KnapsackUtxoSelector(noInputsFee)),
                List.of(new SpentTxoFilter(), new FrozenTxoFilter(), new CoinbaseTxoFilter(watch)),
                payments, List.of(), Collections.emptySet(), feeRate, minRelayFeeRate, minRelayFeeRate, null,
                tip, true, true, true, new MwebFeeEstimator());
        WalletTransaction walletTransaction = watch.createWalletTransaction(params);
        log("built: " + walletTransaction.getSelectedUtxos().size() + " input(s), fee " + walletTransaction.getFee() + " sats");

        //Export the unsigned PSBT to a file, as "Save Transaction" does for an external signer
        PSBT unsigned = walletTransaction.createPSBT();
        Assertions.assertFalse(unsigned.isSigned(), "a watch-only wallet must produce an unsigned PSBT");
        Path unsignedFile = dir.resolve("unsigned.psbt");
        Files.write(unsignedFile, unsigned.serialize());

        //4. External signer: load the file, sign, finalise, write the signed PSBT
        PSBT received = new PSBT(Files.readAllBytes(unsignedFile));
        signer.sign(received);
        signer.finalise(received);
        Assertions.assertTrue(received.isFinalized(), "signer must finalise every input");
        Path signedFile = dir.resolve("signed.psbt");
        Files.write(signedFile, received.serialize());

        //5. Back in the watch-only wallet: load the signed PSBT, extract, check it is the transaction we built
        PSBT signed = new PSBT(Files.readAllBytes(signedFile), true);
        Transaction transaction = signed.extractTransaction();
        Assertions.assertEquals(outputsOf(unsigned.getTransaction()), outputsOf(transaction), "signing must not change the outputs");
        Assertions.assertTrue(transaction.getOutputs().stream().anyMatch(output -> SEND_AMOUNT == output.getValue()
                && destination.equals(output.getScript().getToAddress())), "payment output missing");

        //6. Broadcast through ElectrumServer (the app's broadcast path), then confirm the server and a resync see it
        Sha256Hash txid = new ElectrumServer().broadcastTransaction(transaction);
        log("broadcast txid " + txid);
        Assertions.assertEquals(transaction.getTxId(), txid);

        EsploraClient.EsploraTransaction seen = null;
        for(int i = 0; i < 20 && seen == null; i++) {
            try {
                seen = client.getTransaction(txid.toString());
            } catch(EsploraException e) {
                Thread.sleep(3000);
            }
        }
        Assertions.assertNotNull(seen, "server never reported the broadcast transaction");
        log("server sees it, confirmed=" + seen.isConfirmed());

        Wallet resynced = watchOnlyWallet(signer);
        resynced.setStoredBlockHeight(client.getTipHeight());
        sync(resynced);
        Assertions.assertTrue(resynced.getTransactions().containsKey(txid), "resynced watch-only wallet must list the new transaction");
        log("resynced watch-only wallet lists " + txid + "; history " + resynced.getTransactions().size() + " tx(s)");
    }

    private static Wallet signerWallet(List<String> words) throws MnemonicException {
        DeterministicSeed seed = new DeterministicSeed(new ArrayList<>(words), "", 0, DeterministicSeed.Type.BIP39);
        Keystore keystore = Keystore.fromSeed(seed, ScriptType.P2PKH.getDefaultDerivation());
        return wallet("pep-e2e-signer", keystore);
    }

    private static Wallet watchOnlyWallet(Wallet signer) {
        Keystore source = signer.getKeystores().getFirst();
        Keystore keystore = new Keystore("Watch only");
        keystore.setSource(KeystoreSource.SW_WATCH);
        keystore.setWalletModel(WalletModel.SPARROW);
        keystore.setKeyDerivation(source.getKeyDerivation());
        keystore.setExtendedPublicKey(source.getExtendedPublicKey());
        return wallet("pep-e2e-watch", keystore);
    }

    private static Wallet wallet(String name, Keystore keystore) {
        Wallet wallet = new Wallet(name);
        wallet.setCoin(Coin.PEPECOIN);
        wallet.setNetwork(Network.TESTNET);
        wallet.setPolicyType(PolicyType.SINGLE);
        wallet.setScriptType(ScriptType.P2PKH);
        wallet.getKeystores().add(keystore);
        wallet.setDefaultPolicy(Policy.getPolicy(PolicyType.SINGLE, ScriptType.P2PKH, wallet.getKeystores(), 1));
        Assertions.assertTrue(wallet.isValid(), name + " must be a valid wallet");
        return wallet;
    }

    /** The steps TransactionHistoryService runs for a wallet. */
    private static void sync(Wallet wallet) throws Exception {
        ElectrumServer electrumServer = new ElectrumServer();
        Map<WalletNode, Set<BlockTransactionHash>> nodeTransactionMap = electrumServer.getHistory(wallet);
        electrumServer.getReferencedTransactions(wallet, nodeTransactionMap);
        electrumServer.calculateNodeHistory(wallet, nodeTransactionMap);
    }

    /** Points the app's RPC layer at the testnet Esplora server, as ConnectionService.esploraCall does on connect. */
    private static void connectEsplora() throws Exception {
        setStatic("electrumServerRpc", new EsploraServerRpc(SERVER));
        setStatic("esploraConnected", true);
        setStatic("connectedCoin", Coin.PEPECOIN);
    }

    private static void setStatic(String name, Object value) throws Exception {
        Field field = ElectrumServer.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }

    private static List<String> outputsOf(Transaction transaction) {
        return transaction.getOutputs().stream().map(output -> output.getValue() + ":" + com.sparrowwallet.drongo.Utils.bytesToHex(output.getScriptBytes())).toList();
    }

    private static void log(String message) {
        System.out.println("[pep-e2e] " + message);
    }
}
