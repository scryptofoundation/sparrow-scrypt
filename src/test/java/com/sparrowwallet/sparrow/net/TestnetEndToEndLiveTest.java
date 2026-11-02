package com.sparrowwallet.sparrow.net;

import com.sparrowwallet.drongo.Coin;
import com.sparrowwallet.drongo.KeyPurpose;
import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.drongo.Utils;
import com.sparrowwallet.drongo.address.Address;
import com.sparrowwallet.drongo.policy.Policy;
import com.sparrowwallet.drongo.policy.PolicyType;
import com.sparrowwallet.drongo.protocol.*;
import com.sparrowwallet.drongo.psbt.PSBT;
import com.sparrowwallet.drongo.silentpayments.*;
import com.sparrowwallet.drongo.wallet.*;
import com.sparrowwallet.sparrow.SparrowWallet;
import com.sparrowwallet.sparrow.io.Config;
import com.sparrowwallet.sparrow.mweb.MwebFeeEstimator;
import com.sparrowwallet.sparrow.net.esplora.EsploraClient;
import com.sparrowwallet.sparrow.net.esplora.EsploraException;
import com.sparrowwallet.sparrow.net.esplora.EsploraServerRpc;
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
 * End-to-end run of each test-mode coin through the app's own code, with external signing:
 *
 * 1. A signer wallet (seed) and a separate watch-only wallet (its xpub only) - the watch-only wallet never sees a key.
 * 2. The watch-only wallet syncs through ElectrumServer's real history path (getHistory, getReferencedTransactions,
 *    calculateNodeHistory), exactly as TransactionHistoryService does, over the coin's own backend: Esplora REST
 *    (Pepecoin, Junkcoin) or a real Electrum TLS connection with the app's transport and reader thread (Litecoin).
 * 3. It builds a self-send with the send screen's selectors, filters and fee estimator and exports the unsigned PSBT
 *    to a file; the signer loads that file, signs, finalises and writes the signed PSBT; the watch-only wallet loads
 *    it, extracts the transaction, broadcasts it through ElectrumServer, then a fresh resync must list it.
 *
 * Throwaway TESTNET wallets only, never the user's real wallets. Opt-in: set TESTNET_E2E_DIR to a scratch folder.
 * Each coin keeps its seed, PSBT files and app home in a subfolder named after the coin, so no real config is read
 * or written. A coin's first run creates its seed and prints the address to fund; runs after funding do the spend.
 */
public class TestnetEndToEndLiveTest {
    @Test
    public void pepecoin() throws Exception {
        //100 test PEP, far above the 0.01 PEP dust limit; funded from testnet.pepeblocks.com/faucet
        run(Coin.PEPECOIN, ScriptType.P2PKH, Backend.ESPLORA, "https://pepe-testnet-api.nintondo.io", 100L * 100_000_000L);
    }

    @Test
    public void junkcoin() throws Exception {
        //The only Junkcoin testnet server that accepts broadcasts. 1 tJKC, above the 0.001 JKC dust limit.
        run(Coin.JUNKCOIN, ScriptType.P2PKH, Backend.ESPLORA, "https://jkc-testnet-api.s3na.xyz", 100_000_000L);
    }

    @Test
    public void litecoin() throws Exception {
        //Native segwit, the app's default for Litecoin. 0.002 tLTC, so one 0.01 tLTC faucet claim
        //(cypherfaucet.com/ltc-testnet) covers the run
        run(Coin.LITECOIN, ScriptType.P2WPKH, Backend.ELECTRUM, PublicElectrumServer.TESTNET_BYSH_ME.getUrl(), 200_000L);
    }

    /**
     * Bellscoin Taproot on a private Bells Core v3 regtest chain, where Taproot is always active (testnet-infra
     * sandbox-regtest.sh, its ElectrumX relayed to tcp://127.0.0.1:50106). The first run prints the Taproot wallet's
     * address; mine 31 blocks to it (sandbox-regtest.sh mine <address> 31: a coinbase matures after 30), then the next
     * run spends 1 BELLS of a mature mined coin (regtest pays 2 BELLS a block) by key path through the external-signing path.
     * Bells Core accepting it is the check.
     */
    @Test
    public void bellscoinRegtestTaproot() throws Exception {
        run(Coin.BELLSCOIN, ScriptType.P2TR, Backend.PRIVATE_ELECTRUM, "tcp://127.0.0.1:50106", 100_000_000L,
                Network.REGTEST, "bellscoin-regtest-taproot");
    }

    /**
     * A silent payment on the Litecoin regtest (testnet-infra ltc-regtest.sh, its ElectrumX on tcp://127.0.0.1:50105).
     * The first run prints the native segwit address to fund (ltc-regtest.sh fund <address> 1, then mine 1). The next
     * run pays 0.1 LTC to the silent payment address of a second throwaway seed: the signer computes the output as the
     * sign screen does, Litecoin Core accepting it is the first check, and the receiver's scan key finding that output
     * in the transaction the server returns is the second.
     */
    @Test
    public void litecoinRegtestSilentPayment() throws Exception {
        run(Coin.LITECOIN, ScriptType.P2WPKH, Backend.PRIVATE_ELECTRUM, "tcp://127.0.0.1:50105", 10_000_000L,
                Network.REGTEST, "litecoin-regtest-silent-payment", true);
    }

    private enum Backend { ESPLORA, ELECTRUM, PRIVATE_ELECTRUM }

    private void run(Coin coin, ScriptType scriptType, Backend backend, String server, long sendAmount) throws Exception {
        run(coin, scriptType, backend, server, sendAmount, Network.TESTNET, coin.getName());
    }

    private void run(Coin coin, ScriptType scriptType, Backend backend, String server, long sendAmount, Network network, String folder) throws Exception {
        run(coin, scriptType, backend, server, sendAmount, network, folder, false);
    }

    private void run(Coin coin, ScriptType scriptType, Backend backend, String server, long sendAmount, Network network, String folder, boolean silentPayment) throws Exception {
        String base = System.getenv("TESTNET_E2E_DIR");
        Assumptions.assumeTrue(base != null && !base.isEmpty(), "set TESTNET_E2E_DIR to run the testnet end-to-end tests");
        Path dir = Path.of(base, folder);
        Files.createDirectories(dir.resolve("home"));
        System.setProperty(SparrowWallet.APP_HOME_PROPERTY, dir.resolve("home").toString());

        Network previousNetwork = Network.get();
        Coin previousCoin = Coin.beginScope(coin);
        Network.set(network);
        try {
            runScoped(coin, scriptType, backend, server, sendAmount, dir, silentPayment);
        } finally {
            ElectrumServer.closeActiveConnection();
            Network.set(previousNetwork);
            Coin.endScope(previousCoin);
        }
    }

    private void runScoped(Coin coin, ScriptType scriptType, Backend backend, String server, long sendAmount, Path dir, boolean silentPayment) throws Exception {
        //1. Signer (seed) and watch-only (xpub) wallets
        List<String> words = seedWords(dir.resolve("testnet-seed.txt"));
        Wallet signer = signerWallet(coin, scriptType, words);
        Wallet watch = watchOnlyWallet(coin, scriptType, signer);
        Assertions.assertFalse(watch.getKeystores().getFirst().hasPrivateKey(), "watch-only wallet must hold no private key");

        //Before any history is loaded the fresh receive node is index 0, the address to fund
        WalletNode fundingNode = watch.getFreshNode(KeyPurpose.RECEIVE);
        Assertions.assertEquals(0, fundingNode.getIndex());
        Address fundingAddress = watch.getAddress(fundingNode);
        log(coin, "funding address (receive 0, " + watch.getKeystores().getFirst().getKeyDerivation().getDerivationPath() + "): " + fundingAddress);

        //2. Connect the coin's backend and sync through the app's real history path
        int tip = connect(coin, backend, server);
        watch.setStoredBlockHeight(tip);
        sync(watch);
        long balance = watch.getWalletUtxos().keySet().stream().mapToLong(BlockTransactionHashIndex::getValue).sum();
        log(coin, "tip " + tip + ", watch-only balance " + balance + " sats, utxos " + watch.getWalletUtxos().size());
        Assumptions.assumeTrue(balance > sendAmount * 2, "not funded yet: send at least " + (sendAmount * 2) + " sats to " + fundingAddress);

        //3. Build with the send screen's machinery: a self-send to a fresh receive address (or a silent payment to a
        //second seed), change back to the wallet
        SilentPaymentScanAddress receiver = null;
        Address destination;
        List<Payment> payments;
        if(silentPayment) {
            DeterministicSeed receiverSeed = new DeterministicSeed(new ArrayList<>(seedWords(dir.resolve("receiver-seed.txt"))), "", 0, DeterministicSeed.Type.BIP39);
            receiver = SilentPaymentScanAddress.from(receiverSeed, 0);
            log(coin, "silent payment address: " + receiver.getSilentPaymentAddress());
            Assertions.assertTrue(watch.canSendSilentPayments(), "the wallet must be able to send silent payments");
            destination = null;
            payments = List.of(new SilentPayment(receiver.getSilentPaymentAddress(), "e2e silent payment", sendAmount, false));
        } else {
            destination = watch.getFreshNode(KeyPurpose.RECEIVE).getAddress();
            payments = List.of(new Payment(destination, "e2e self-send", sendAmount, false));
        }
        double feeRate = coin.getRecommendedFeeRate();
        double minRelayFeeRate = coin.getMinRelayFeeRate();
        long noInputsFee = watch.getNoInputsFee(payments, feeRate);
        long costOfChange = watch.getCostOfChange(feeRate, minRelayFeeRate);
        TransactionParameters params = new TransactionParameters(
                List.of(new BnBUtxoSelector(noInputsFee, costOfChange), new KnapsackUtxoSelector(noInputsFee)),
                List.of(new SpentTxoFilter(), new FrozenTxoFilter(), new CoinbaseTxoFilter(watch)),
                payments, List.of(), Collections.emptySet(), feeRate, minRelayFeeRate, minRelayFeeRate, null,
                tip, true, true, true, new MwebFeeEstimator());
        WalletTransaction walletTransaction = watch.createWalletTransaction(params);
        log(coin, "built: " + walletTransaction.getSelectedUtxos().size() + " input(s), fee " + walletTransaction.getFee() + " sats");

        //Export the unsigned PSBT to a file, as "Save Transaction" does for an external signer
        PSBT unsigned = walletTransaction.createPSBT();
        Assertions.assertFalse(unsigned.isSigned(), "a watch-only wallet must produce an unsigned PSBT");
        Path unsignedFile = dir.resolve("unsigned.psbt");
        Files.write(unsignedFile, unsigned.serialize());

        //4. External signer: load the file, sign, finalise, write the signed PSBT
        PSBT received = new PSBT(Files.readAllBytes(unsignedFile));
        PSBT expected = unsigned;
        if(silentPayment) {
            //The signer works out the silent payment output from its input keys before signing, as the sign screen does
            List<SilentPayment> computed = signer.computeSilentPaymentOutputs(received, signer.getSigningNodes(received));
            Assertions.assertEquals(1, computed.size(), "one silent payment output to compute");
            destination = computed.getFirst().getAddress();
            Assertions.assertEquals(ScriptType.P2TR, destination.getScriptType(), "a silent payment output is Taproot");
            log(coin, "silent payment output address " + destination);
            expected = received;
        }
        signer.sign(received);
        signer.finalise(received);
        Assertions.assertTrue(received.isFinalized(), "signer must finalise every input");
        Path signedFile = dir.resolve("signed.psbt");
        Files.write(signedFile, received.serialize());

        //5. Back in the watch-only wallet: load the signed PSBT, extract, check it is the transaction we built
        PSBT signed = new PSBT(Files.readAllBytes(signedFile), true);
        Transaction transaction = signed.extractTransaction();
        Assertions.assertEquals(outputsOf(expected.getTransaction()), outputsOf(transaction), "signing must not change the outputs");
        Address paid = destination;
        Assertions.assertTrue(transaction.getOutputs().stream().anyMatch(output -> sendAmount == output.getValue()
                && paid.equals(output.getScript().getToAddress())), "payment output missing");

        //6. Broadcast through ElectrumServer (the app's broadcast path), then a fresh resync must list it
        Sha256Hash txid = new ElectrumServer().broadcastTransaction(transaction);
        log(coin, "broadcast txid " + txid);
        Assertions.assertEquals(transaction.getTxId(), txid);

        Wallet resynced = null;
        for(int attempt = 0; attempt < 10; attempt++) {
            Thread.sleep(3000);
            resynced = watchOnlyWallet(coin, scriptType, signer);
            resynced.setStoredBlockHeight(tip);
            sync(resynced);
            if(resynced.getTransactions().containsKey(txid)) {
                break;
            }
        }
        Assertions.assertTrue(resynced.getTransactions().containsKey(txid), "resynced watch-only wallet must list the new transaction");
        log(coin, "resynced watch-only wallet lists " + txid + "; history " + resynced.getTransactions().size() + " tx(s)");

        if(receiver != null) {
            //7. The receiver, holding only its scan key and spend public key, scans the transaction as the server returned it
            Map<Sha256Hash, BlockTransaction> history = resynced.getTransactions();
            Transaction onChain = history.get(txid).getTransaction();
            Map<HashIndex, Script> spentScripts = new HashMap<>();
            for(TransactionInput input : onChain.getInputs()) {
                TransactionOutPoint outpoint = input.getOutpoint();
                spentScripts.put(new HashIndex(outpoint.getHash(), outpoint.getIndex()),
                        history.get(outpoint.getHash()).getTransaction().getOutputs().get((int)outpoint.getIndex()).getScript());
            }
            byte[] tweak = SilentPaymentUtils.getTweak(onChain, spentScripts);
            Assertions.assertNotNull(tweak, "the transaction must be eligible for silent payments");
            List<SilentPaymentScanMatch> matches = SilentPaymentUtils.scanTransactionOutputs(receiver.getScanKey(), receiver.getSpendKey(),
                    Set.of(), tweak, onChain.getOutputs());
            Assertions.assertEquals(1, matches.size(), "the receiver must find exactly its one output");
            TransactionOutput found = onChain.getOutputs().get(matches.getFirst().outputIndex());
            Assertions.assertEquals(sendAmount, found.getValue());
            Assertions.assertEquals(destination, found.getScript().getToAddress());
            log(coin, "receiver's scan found output " + matches.getFirst().outputIndex() + " (" + found.getValue() + " sats)");
        }
    }

    private static List<String> seedWords(Path seedFile) throws Exception {
        if(!Files.exists(seedFile)) {
            DeterministicSeed fresh = new DeterministicSeed(new SecureRandom(), 128, "");
            Files.writeString(seedFile, String.join(" ", fresh.getMnemonicCode()), StandardCharsets.UTF_8);
        }
        return Arrays.asList(Files.readString(seedFile, StandardCharsets.UTF_8).trim().split(" "));
    }

    /** Opens the coin's backend the way ConnectionService does, and returns the chain tip. */
    private static int connect(Coin coin, Backend backend, String server) throws Exception {
        ElectrumServer.closeActiveConnection();
        if(backend == Backend.ESPLORA) {
            setStatic("electrumServerRpc", new EsploraServerRpc(server));
            setStatic("esploraConnected", true);
            setStatic("connectedCoin", coin);
            return new EsploraClient(server).getTipHeight();
        }

        //Electrum: the configured public server, or your own (private) one, with the app's transport and reader
        //thread and the simple RPC
        if(backend == Backend.PRIVATE_ELECTRUM) {
            Config.get().setServerType(ServerType.ELECTRUM_SERVER);
            Config.get().setElectrumServer(new com.sparrowwallet.sparrow.io.Server(server));
        } else {
            Config.get().setServerType(ServerType.PUBLIC_ELECTRUM_SERVER);
            Config.get().setPublicElectrumServer(PublicElectrumServer.fromServer(new com.sparrowwallet.sparrow.io.Server(server)).getServer());
        }
        ElectrumServer electrumServer = new ElectrumServer();
        electrumServer.connect();
        Coin readerCoin = coin;
        Thread reader = new Thread(() -> {
            Coin.beginScope(readerCoin);
            new ElectrumServer.ReadRunnable().run();
        }, "ElectrumServerReadThread");
        reader.setDaemon(true);
        reader.start();
        setStatic("electrumServerRpc", new SimpleElectrumServerRpc());
        log(coin, "server version " + electrumServer.getServerVersion());
        setStatic("connectedCoin", coin);
        return electrumServer.subscribeBlockHeaders().height;
    }

    private static Wallet signerWallet(Coin coin, ScriptType scriptType, List<String> words) throws MnemonicException {
        DeterministicSeed seed = new DeterministicSeed(new ArrayList<>(words), "", 0, DeterministicSeed.Type.BIP39);
        Keystore keystore = Keystore.fromSeed(seed, PolicyType.SINGLE_HD, scriptType.getDefaultDerivation());
        return wallet(coin, scriptType, coin.getName() + "-e2e-signer", keystore);
    }

    private static Wallet watchOnlyWallet(Coin coin, ScriptType scriptType, Wallet signer) {
        Keystore source = signer.getKeystores().getFirst();
        Keystore keystore = new Keystore("Watch only");
        keystore.setSource(KeystoreSource.SW_WATCH);
        keystore.setWalletModel(WalletModel.SPARROW);
        keystore.setKeyDerivation(source.getKeyDerivation());
        keystore.setExtendedPublicKey(source.getExtendedPublicKey());
        return wallet(coin, scriptType, coin.getName() + "-e2e-watch", keystore);
    }

    private static Wallet wallet(Coin coin, ScriptType scriptType, String name, Keystore keystore) {
        Wallet wallet = new Wallet(name);
        wallet.setCoin(coin);
        wallet.setNetwork(Network.get());
        wallet.setPolicyType(PolicyType.SINGLE_HD);
        wallet.setScriptType(scriptType);
        wallet.getKeystores().add(keystore);
        wallet.setDefaultPolicy(Policy.getPolicy(PolicyType.SINGLE_HD, scriptType, wallet.getKeystores(), 1));
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

    private static void setStatic(String name, Object value) throws Exception {
        Field field = ElectrumServer.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }

    private static List<String> outputsOf(Transaction transaction) {
        return transaction.getOutputs().stream().map(output -> output.getValue() + ":" + Utils.bytesToHex(output.getScriptBytes())).toList();
    }

    private static void log(Coin coin, String message) {
        System.out.println("[e2e " + coin.getName() + "] " + message);
    }
}
