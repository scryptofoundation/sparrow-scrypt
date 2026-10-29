package com.sparrowwallet.sparrow.net;

import com.sparrowwallet.drongo.Coin;
import com.sparrowwallet.drongo.KeyPurpose;
import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.drongo.Utils;
import com.sparrowwallet.drongo.address.Address;
import com.sparrowwallet.drongo.policy.Policy;
import com.sparrowwallet.drongo.policy.PolicyType;
import com.sparrowwallet.drongo.protocol.ScriptType;
import com.sparrowwallet.drongo.protocol.Sha256Hash;
import com.sparrowwallet.drongo.protocol.Transaction;
import com.sparrowwallet.drongo.psbt.PSBT;
import com.sparrowwallet.drongo.wallet.*;
import com.sparrowwallet.sparrow.SparrowWallet;
import com.sparrowwallet.sparrow.io.Config;
import com.sparrowwallet.sparrow.mweb.MwebFeeEstimator;
import com.sparrowwallet.sparrow.net.esplora.EsploraClient;
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
 * A 2-of-3 multisig wallet on each coin, end to end through the app's own code:
 *
 * 1. Three throwaway seeds. Each co-signer is a copy of the 2-of-3 wallet that holds only its own seed and the other
 *    two xpubs; a fourth copy is watch-only. Native Segwit (P2WSH) where the coin has segwit, Legacy (P2SH) elsewhere.
 * 2. The watch-only copy syncs through ElectrumServer's history path and builds a self-send with the send screen's
 *    selectors, saving the unsigned PSBT to a file.
 * 3. Co-signer 1 loads that file and signs (one signature must not finalise it); co-signer 3 loads co-signer 1's file,
 *    signs and finalises. The watch-only copy extracts and broadcasts, and a fresh resync must list the transaction.
 *
 * Test networks and private chains only, never the user's wallets. Opt-in: set MULTISIG_E2E_DIR to a scratch folder;
 * each coin keeps its seeds, PSBT files and app home in a subfolder. A coin's first run prints the address to fund.
 * Servers default to the public test servers (LTC, PEP, JKC) and testnet-infra's local ElectrumX ports (DOGE 50101,
 * LKY 50102, DINGO 50103, BELLS 50104); override one with MULTISIG_E2E_SERVER_&lt;TICKER&gt;.
 */
public class MultisigEndToEndLiveTest {
    private static final int THRESHOLD = 2;
    private static final int COSIGNERS = 3;

    @Test
    public void litecoin() throws Exception {
        run(Coin.LITECOIN, ScriptType.P2WSH, PublicElectrumServer.TESTNET_BYSH_ME.getUrl(), 100_000L);
    }

    @Test
    public void bellscoin() throws Exception {
        run(Coin.BELLSCOIN, ScriptType.P2WSH, "tcp://127.0.0.1:50104", 100_000_000L);
    }

    @Test
    public void dogecoin() throws Exception {
        run(Coin.DOGECOIN, ScriptType.P2SH, "tcp://127.0.0.1:50101", 100_000_000L);
    }

    @Test
    public void pepecoin() throws Exception {
        run(Coin.PEPECOIN, ScriptType.P2SH, "https://pepe-testnet-api.nintondo.io", 100_000_000L);
    }

    @Test
    public void luckycoin() throws Exception {
        run(Coin.LUCKYCOIN, ScriptType.P2SH, "tcp://127.0.0.1:50102", 100_000_000L);
    }

    @Test
    public void dingocoin() throws Exception {
        run(Coin.DINGOCOIN, ScriptType.P2SH, "tcp://127.0.0.1:50103", 100_000_000L);
    }

    @Test
    public void junkcoin() throws Exception {
        run(Coin.JUNKCOIN, ScriptType.P2SH, "https://jkc-testnet-api.s3na.xyz", 100_000_000L);
    }

    private void run(Coin coin, ScriptType scriptType, String defaultServer, long sendAmount) throws Exception {
        String base = System.getenv("MULTISIG_E2E_DIR");
        Assumptions.assumeTrue(base != null && !base.isEmpty(), "set MULTISIG_E2E_DIR to run the multisig end-to-end tests");
        String server = Optional.ofNullable(System.getenv("MULTISIG_E2E_SERVER_" + coin.getTicker())).orElse(defaultServer);
        Path dir = Path.of(base, coin.getName());
        Files.createDirectories(dir.resolve("home"));
        System.setProperty(SparrowWallet.APP_HOME_PROPERTY, dir.resolve("home").toString());

        Network previousNetwork = Network.get();
        Coin previousCoin = Coin.beginScope(coin);
        Network.set(Network.TESTNET);
        try {
            runScoped(coin, scriptType, server, sendAmount, dir);
        } finally {
            ElectrumServer.closeActiveConnection();
            Network.set(previousNetwork);
            Coin.endScope(previousCoin);
        }
    }

    private void runScoped(Coin coin, ScriptType scriptType, String server, long sendAmount, Path dir) throws Exception {
        //1. Three seeds; every copy of the wallet lists the same three keys
        List<Keystore> seedKeystores = new ArrayList<>();
        for(int i = 1; i <= COSIGNERS; i++) {
            seedKeystores.add(seedKeystore(dir.resolve("multisig-seed-" + i + ".txt"), scriptType, i));
        }
        Wallet watch = multisigWallet(coin, scriptType, "watch", seedKeystores, -1);
        Wallet cosigner1 = multisigWallet(coin, scriptType, "cosigner-1", seedKeystores, 0);
        Wallet cosigner3 = multisigWallet(coin, scriptType, "cosigner-3", seedKeystores, 2);
        Assertions.assertTrue(watch.getKeystores().stream().noneMatch(Keystore::hasPrivateKey), "the watch-only copy must hold no private key");

        WalletNode fundingNode = watch.getFreshNode(KeyPurpose.RECEIVE);
        Address fundingAddress = watch.getAddress(fundingNode);
        Assertions.assertEquals(fundingAddress, cosigner1.getAddress(cosigner1.getFreshNode(KeyPurpose.RECEIVE)), "every copy must derive the same addresses");
        log(coin, THRESHOLD + "-of-" + COSIGNERS + " " + scriptType + " funding address (receive 0): " + fundingAddress);

        //2. Sync the watch-only copy through the app's history path
        int tip = connect(coin, server);
        watch.setStoredBlockHeight(tip);
        sync(watch);
        long balance = watch.getWalletUtxos().keySet().stream().mapToLong(BlockTransactionHashIndex::getValue).sum();
        log(coin, "tip " + tip + ", balance " + balance + " sats in " + watch.getWalletUtxos().size() + " utxo(s)");
        if(balance <= sendAmount * 2) {
            fundFromSingleKeyWallet(coin, tip, fundingAddress, sendAmount * 3);
        }
        Assumptions.assumeTrue(balance > sendAmount * 2, "not funded yet: send at least " + (sendAmount * 2) + " sats to " + fundingAddress);

        Address destination = watch.getFreshNode(KeyPurpose.RECEIVE).getAddress();
        List<Payment> payments = List.of(new Payment(destination, "multisig self-send", sendAmount, false));
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

        PSBT unsigned = walletTransaction.createPSBT();
        Path unsignedFile = dir.resolve("unsigned.psbt");
        Files.write(unsignedFile, unsigned.serialize());

        //3. Co-signer 1 signs; one signature of two must leave it unfinished
        PSBT first = new PSBT(Files.readAllBytes(unsignedFile));
        cosigner1.sign(first);
        Assertions.assertFalse(first.isSigned(), "one of two signatures must not complete the PSBT");
        Path firstFile = dir.resolve("signed-by-1.psbt");
        Files.write(firstFile, first.serialize());

        //Co-signer 3 signs the file co-signer 1 wrote, then finalises
        PSBT second = new PSBT(Files.readAllBytes(firstFile));
        cosigner3.sign(second);
        Assertions.assertTrue(second.isSigned(), "two of three signatures must complete the PSBT");
        cosigner3.finalise(second);
        Assertions.assertTrue(second.isFinalized(), "co-signer 3 must finalise every input");
        Path signedFile = dir.resolve("signed-by-1-and-3.psbt");
        Files.write(signedFile, second.serialize());

        PSBT signed = new PSBT(Files.readAllBytes(signedFile), true);
        Transaction transaction = signed.extractTransaction();
        Assertions.assertEquals(outputsOf(unsigned.getTransaction()), outputsOf(transaction), "signing must not change the outputs");

        //4. Broadcast through ElectrumServer, then a fresh resync must list it
        Sha256Hash txid = new ElectrumServer().broadcastTransaction(transaction);
        log(coin, "broadcast txid " + txid);
        Assertions.assertEquals(transaction.getTxId(), txid);

        Wallet resynced = null;
        for(int attempt = 0; attempt < 10; attempt++) {
            Thread.sleep(3000);
            resynced = multisigWallet(coin, scriptType, "watch", seedKeystores, -1);
            resynced.setStoredBlockHeight(tip);
            sync(resynced);
            if(resynced.getTransactions().containsKey(txid)) {
                break;
            }
        }
        Assertions.assertTrue(resynced.getTransactions().containsKey(txid), "the resynced wallet must list the new transaction");
        log(coin, "resynced wallet lists " + txid);
    }

    /**
     * Optional: pays the multisig address from a throwaway single-key test wallet through the same send code, when
     * MULTISIG_E2E_FUNDER_&lt;TICKER&gt; names its seed file and MULTISIG_E2E_FUNDER_TYPE_&lt;TICKER&gt; its script type (default
     * P2PKH). Run again once the payment confirms.
     */
    private static void fundFromSingleKeyWallet(Coin coin, int tip, Address to, long amount) throws Exception {
        String seedPath = System.getenv("MULTISIG_E2E_FUNDER_" + coin.getTicker());
        if(seedPath == null || seedPath.isEmpty()) {
            return;
        }
        ScriptType funderType = ScriptType.valueOf(Optional.ofNullable(System.getenv("MULTISIG_E2E_FUNDER_TYPE_" + coin.getTicker())).orElse("P2PKH"));
        List<String> words = Arrays.asList(Files.readString(Path.of(seedPath), StandardCharsets.UTF_8).trim().split(" "));
        DeterministicSeed seed = new DeterministicSeed(new ArrayList<>(words), "", 0, DeterministicSeed.Type.BIP39);
        Wallet funder = new Wallet(coin.getName() + "-multisig-funder");
        funder.setCoin(coin);
        funder.setNetwork(Network.TESTNET);
        funder.setPolicyType(PolicyType.SINGLE_HD);
        funder.setScriptType(funderType);
        funder.getKeystores().add(Keystore.fromSeed(seed, PolicyType.SINGLE_HD, funderType.getDefaultDerivation()));
        funder.setDefaultPolicy(Policy.getPolicy(PolicyType.SINGLE_HD, funderType, funder.getKeystores(), 1));
        funder.setStoredBlockHeight(tip);
        sync(funder);
        long funderBalance = funder.getWalletUtxos().keySet().stream().mapToLong(BlockTransactionHashIndex::getValue).sum();
        log(coin, "funder balance " + funderBalance + " sats");
        Assumptions.assumeTrue(funderBalance > amount, "funder has too little to send " + amount + " sats");

        List<Payment> payments = List.of(new Payment(to, "fund multisig test", amount, false));
        double feeRate = coin.getRecommendedFeeRate();
        double minRelayFeeRate = coin.getMinRelayFeeRate();
        TransactionParameters params = new TransactionParameters(
                List.of(new BnBUtxoSelector(funder.getNoInputsFee(payments, feeRate), funder.getCostOfChange(feeRate, minRelayFeeRate)),
                        new KnapsackUtxoSelector(funder.getNoInputsFee(payments, feeRate))),
                List.of(new SpentTxoFilter(), new FrozenTxoFilter(), new CoinbaseTxoFilter(funder)),
                payments, List.of(), Collections.emptySet(), feeRate, minRelayFeeRate, minRelayFeeRate, null,
                tip, true, true, true, new MwebFeeEstimator());
        PSBT psbt = funder.createWalletTransaction(params).createPSBT();
        funder.sign(psbt);
        funder.finalise(psbt);
        Sha256Hash txid = new ElectrumServer().broadcastTransaction(psbt.extractTransaction());
        log(coin, "funded " + to + " with " + amount + " sats in " + txid + "; run again once it confirms");
    }

    private static Keystore seedKeystore(Path seedFile, ScriptType scriptType, int number) throws Exception {
        if(!Files.exists(seedFile)) {
            DeterministicSeed fresh = new DeterministicSeed(new SecureRandom(), 128, "");
            Files.writeString(seedFile, String.join(" ", fresh.getMnemonicCode()), StandardCharsets.UTF_8);
        }
        List<String> words = Arrays.asList(Files.readString(seedFile, StandardCharsets.UTF_8).trim().split(" "));
        DeterministicSeed seed = new DeterministicSeed(new ArrayList<>(words), "", 0, DeterministicSeed.Type.BIP39);
        Keystore keystore = Keystore.fromSeed(seed, PolicyType.MULTI_HD, scriptType.getDefaultDerivation());
        keystore.setLabel("Cosigner " + number);
        return keystore;
    }

    /** A copy of the 2-of-3 wallet holding the seed at {@code seedIndex} only (-1 for none). */
    private static Wallet multisigWallet(Coin coin, ScriptType scriptType, String name, List<Keystore> seedKeystores, int seedIndex) {
        List<Keystore> keystores = new ArrayList<>();
        for(int i = 0; i < seedKeystores.size(); i++) {
            Keystore source = seedKeystores.get(i);
            if(i == seedIndex) {
                keystores.add(source);
            } else {
                Keystore xpubOnly = new Keystore(source.getLabel());
                xpubOnly.setSource(KeystoreSource.SW_WATCH);
                xpubOnly.setWalletModel(WalletModel.SPARROW);
                xpubOnly.setKeyDerivation(source.getKeyDerivation());
                xpubOnly.setExtendedPublicKey(source.getExtendedPublicKey());
                keystores.add(xpubOnly);
            }
        }
        Wallet wallet = new Wallet(coin.getName() + "-multisig-" + name);
        wallet.setCoin(coin);
        wallet.setNetwork(Network.TESTNET);
        wallet.setPolicyType(PolicyType.MULTI_HD);
        wallet.setScriptType(scriptType);
        wallet.getKeystores().addAll(keystores);
        wallet.setDefaultPolicy(Policy.getPolicy(PolicyType.MULTI_HD, scriptType, wallet.getKeystores(), THRESHOLD));
        Assertions.assertTrue(wallet.isValid(), wallet.getName() + " must be a valid wallet");
        return wallet;
    }

    /** Opens the coin's backend the way ConnectionService does, and returns the chain tip. */
    private static int connect(Coin coin, String server) throws Exception {
        ElectrumServer.closeActiveConnection();
        if(server.startsWith("https://")) {
            setStatic("electrumServerRpc", new EsploraServerRpc(server));
            setStatic("esploraConnected", true);
            setStatic("connectedCoin", coin);
            return new EsploraClient(server).getTipHeight();
        }

        com.sparrowwallet.sparrow.io.Server electrum = new com.sparrowwallet.sparrow.io.Server(server);
        if(server.startsWith("tcp://")) {
            //A private local ElectrumX, as testnet-infra runs for the private chains
            Config.get().setServerType(ServerType.ELECTRUM_SERVER);
            Config.get().setElectrumServer(electrum);
            Config.get().setUseProxy(false);
        } else {
            Config.get().setServerType(ServerType.PUBLIC_ELECTRUM_SERVER);
            Config.get().setPublicElectrumServer(PublicElectrumServer.fromServer(electrum).getServer());
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
        System.out.println("[multisig " + coin.getName() + "] " + message);
    }
}
