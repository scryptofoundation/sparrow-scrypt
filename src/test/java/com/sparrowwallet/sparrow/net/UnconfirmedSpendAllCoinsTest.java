package com.sparrowwallet.sparrow.net;

import com.github.arteam.simplejsonrpc.client.Transport;
import com.google.common.eventbus.Subscribe;
import com.google.common.net.HostAndPort;
import com.sparrowwallet.drongo.Coin;
import com.sparrowwallet.drongo.KeyPurpose;
import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.drongo.Utils;
import com.sparrowwallet.drongo.address.P2PKHAddress;
import com.sparrowwallet.drongo.policy.Policy;
import com.sparrowwallet.drongo.policy.PolicyType;
import com.sparrowwallet.drongo.crypto.ECKey;
import com.sparrowwallet.drongo.protocol.*;
import com.sparrowwallet.drongo.psbt.PSBT;
import com.sparrowwallet.drongo.wallet.*;
import com.sparrowwallet.sparrow.EventManager;
import com.sparrowwallet.sparrow.SparrowWallet;
import com.sparrowwallet.sparrow.event.UncheckedTransactionsEvent;
import com.sparrowwallet.sparrow.event.UnsignedTransactionsEvent;
import com.sparrowwallet.sparrow.io.Config;
import com.sparrowwallet.sparrow.net.esplora.EsploraServerRpc;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * The history path that keeps forged spends of a wallet's coins out of it (ElectrumServer.getReferencedTransactions), run for every coin and script
 * type the app offers, under the wallet's own coin scope as the history service runs it. Complements UnconfirmedSpendTest, which covers Bitcoin's
 * rules on the default coin only.
 */
public class UnconfirmedSpendAllCoinsTest {
    private static final int FUNDING_HEIGHT = 800000;
    private static final long FUNDING_VALUE = 5_000_000_000L;

    //The app's log keeps a file open in the home folder, which Windows will not let JUnit delete
    @TempDir(cleanup = CleanupMode.NEVER)
    private static Path tempHome;

    private final Map<Coin, ServerType> previousServerTypes = new LinkedHashMap<>();
    private ElectrumServerRpc previousElectrumServerRpc;
    private CloseableTransport previousTransport;
    private ServerCapability previousServerCapability;
    private Network previousNetwork;

    @BeforeAll
    public static void setUpAll() {
        System.setProperty(SparrowWallet.APP_HOME_PROPERTY, tempHome.toString());
    }

    @AfterAll
    public static void tearDownAll() {
        System.clearProperty(SparrowWallet.APP_HOME_PROPERTY);
    }

    @BeforeEach
    public void setUp() {
        previousNetwork = Network.get();
        Network.set(Network.MAINNET);
        for(Coin coin : Coin.values()) {
            Coin scope = Coin.beginScope(coin);
            try {
                previousServerTypes.put(coin, Config.get().getServerType());
                Config.get().setServerType(ServerType.PUBLIC_ELECTRUM_SERVER);
            } finally {
                Coin.endScope(scope);
            }
        }
        previousElectrumServerRpc = ElectrumServer.electrumServerRpc;
        previousTransport = ElectrumServer.transport;
        previousServerCapability = ElectrumServer.serverCapability;
        ElectrumServer.electrumServerRpc = new NoServer();
        ElectrumServer.transport = new UnusedTransport();
        ElectrumServer.serverCapability = null;
    }

    @AfterEach
    public void tearDown() {
        for(Map.Entry<Coin, ServerType> entry : previousServerTypes.entrySet()) {
            Coin scope = Coin.beginScope(entry.getKey());
            try {
                Config.get().setServerType(entry.getValue());
            } finally {
                Coin.endScope(scope);
            }
        }
        ElectrumServer.electrumServerRpc = previousElectrumServerRpc;
        ElectrumServer.transport = previousTransport;
        ElectrumServer.serverCapability = previousServerCapability;
        ElectrumServer.clearPreviousServerState();
        Network.set(previousNetwork);
    }

    @TestFactory
    public List<DynamicTest> keepsSignedSpendsAndLeavesOutForgedOnesOnEveryCoin() {
        List<DynamicTest> tests = new ArrayList<>();
        for(Coin coin : Coin.values()) {
            for(PolicyType policyType : List.of(PolicyType.SINGLE_HD, PolicyType.MULTI_HD)) {
                for(ScriptType scriptType : scriptTypes(coin, policyType)) {
                    tests.add(DynamicTest.dynamicTest(coin.getName() + " " + policyType.getName() + " " + scriptType.getName(), () -> {
                        Coin scope = Coin.beginScope(coin);
                        try {
                            Assertions.assertTrue(isVerifyingSignatures(), "checked on a public Electrum or Esplora server");

                            Wallet wallet = wallet(coin, policyType, scriptType);
                            Transaction funding = fund(wallet);
                            Transaction spend = signedSpend(wallet);
                            Map<WalletNode, Set<BlockTransactionHash>> history = history(wallet, funding, spend, 0);
                            new ElectrumServer().getReferencedTransactions(wallet, history);
                            Assertions.assertTrue(holds(history, spend), "signed spend kept");
                            Assertions.assertTrue(wallet.getTransactions().containsKey(spend.getTxId()), "signed spend in the wallet");

                            ElectrumServer.clearPreviousServerState();
                            Wallet other = wallet(coin, policyType, scriptType);
                            Transaction otherFunding = fund(other);
                            Transaction forged = alterOutputs(signedSpend(other));
                            Map<WalletNode, Set<BlockTransactionHash>> forgedHistory = history(other, otherFunding, forged, 0);
                            new ElectrumServer().getReferencedTransactions(other, forgedHistory);
                            Assertions.assertFalse(holds(forgedHistory, forged), "forged spend left out");
                            Assertions.assertTrue(holds(forgedHistory, otherFunding), "funding kept");
                            new ElectrumServer().calculateNodeHistory(other, forgedHistory);
                            Assertions.assertTrue(other.getWalletUtxos().keySet().stream().anyMatch(utxo -> utxo.getHash().equals(otherFunding.getTxId())), "output unspent");
                        } finally {
                            Coin.endScope(scope);
                        }
                    }));
                }
            }
        }

        Assertions.assertEquals(24, tests.size());
        return tests;
    }

    /**
     * The server type is chosen per coin, so a user's own node for one coin must not switch the check off for another coin's public server
     */
    @Test
    public void ownNodeForOneCoinLeavesTheCheckOnForAnother() throws Exception {
        Coin scope = Coin.beginScope(Coin.DOGECOIN);
        try {
            Config.get().setServerType(ServerType.BITCOIN_CORE);
        } finally {
            Coin.endScope(scope);
        }

        for(Coin coin : List.of(Coin.LITECOIN, Coin.PEPECOIN, Coin.DINGOCOIN, Coin.DOGECOIN)) {
            scope = Coin.beginScope(coin);
            try {
                ElectrumServer.clearPreviousServerState();
                Wallet wallet = wallet(coin, PolicyType.SINGLE_HD, ScriptType.P2PKH);
                Transaction funding = fund(wallet);
                Transaction forged = alterOutputs(signedSpend(wallet));
                Map<WalletNode, Set<BlockTransactionHash>> history = history(wallet, funding, forged, 0);
                new ElectrumServer().getReferencedTransactions(wallet, history);
                if(coin == Coin.DOGECOIN) {
                    Assertions.assertFalse(isVerifyingSignatures());
                    Assertions.assertTrue(holds(history, forged), "the user's own Dogecoin node validated what it reports, so nothing is checked");
                } else {
                    Assertions.assertTrue(isVerifyingSignatures(), coin.getName());
                    Assertions.assertFalse(holds(history, forged), coin.getName() + " forged spend left out");
                }
            } finally {
                Coin.endScope(scope);
            }
        }
    }


    /**
     * No coin here verifies block headers (Network.supportsHeaderVerification), so a height the server reports proves nothing. A spend first seen at
     * such a height is judged as an unconfirmed one is: a forged one is left out and warned about, an honest one kept at its height.
     */
    @Test
    public void forgedSpendReportedAsMinedIsLeftOut() throws Exception {
        for(Coin coin : Coin.values()) {
            Coin scope = Coin.beginScope(coin);
            List<Set<Sha256Hash>> warnings = new ArrayList<>();
            Object listener = new Object() {
                @Subscribe
                public void unsignedTransactions(UnsignedTransactionsEvent event) {
                    warnings.add(event.getTxids());
                }
            };
            EventManager.get().register(listener);
            try {
                Assertions.assertFalse(Network.get().supportsHeaderVerification(), coin.getName());

                ElectrumServer.clearPreviousServerState();
                Wallet wallet = wallet(coin, PolicyType.SINGLE_HD, ScriptType.P2PKH);
                Transaction funding = fund(wallet);
                Transaction forged = alterOutputs(signedSpend(wallet));
                ServingServer server = new ServingServer();
                server.serve(forged);
                ElectrumServer.electrumServerRpc = server;

                Map<WalletNode, Set<BlockTransactionHash>> mined = minedHistory(wallet, funding, forged);
                new ElectrumServer().getReferencedTransactions(wallet, mined);
                Assertions.assertEquals(Set.of(forged.getTxId().toString()), server.requested, coin.getName() + " forged spend fetched");
                Assertions.assertFalse(holds(mined, forged), coin.getName() + " forged spend at a reported height is left out");
                Assertions.assertFalse(wallet.getTransactions().containsKey(forged.getTxId()), coin.getName() + " forged spend not in the wallet");
                Assertions.assertTrue(holds(mined, funding), coin.getName() + " funding kept");
                Assertions.assertEquals(List.of(Set.of(forged.getTxId())), warnings, coin.getName() + " warned");
            } finally {
                EventManager.get().unregister(listener);
                ElectrumServer.electrumServerRpc = new NoServer();
                Coin.endScope(scope);
            }
        }
    }

    @TestFactory
    public List<DynamicTest> keepsAnHonestSpendReportedAsMinedOnEveryCoin() {
        List<DynamicTest> tests = new ArrayList<>();
        for(Coin coin : Coin.values()) {
            for(PolicyType policyType : List.of(PolicyType.SINGLE_HD, PolicyType.MULTI_HD)) {
                for(ScriptType scriptType : scriptTypes(coin, policyType)) {
                    tests.add(DynamicTest.dynamicTest(coin.getName() + " " + policyType.getName() + " " + scriptType.getName(), () -> {
                        Coin scope = Coin.beginScope(coin);
                        try {
                            ElectrumServer.clearPreviousServerState();
                            Wallet wallet = wallet(coin, policyType, scriptType);
                            Transaction funding = fund(wallet);
                            Transaction spend = signedSpend(wallet);
                            ServingServer server = new ServingServer();
                            server.serve(spend);
                            ElectrumServer.electrumServerRpc = server;

                            Map<WalletNode, Set<BlockTransactionHash>> mined = minedHistory(wallet, funding, spend);
                            new ElectrumServer().getReferencedTransactions(wallet, mined);
                            Assertions.assertTrue(holds(mined, spend), "honest spend kept");
                            Assertions.assertEquals(FUNDING_HEIGHT + 1, wallet.getTransactions().get(spend.getTxId()).getHeight(), "at its height");
                        } finally {
                            ElectrumServer.electrumServerRpc = new NoServer();
                            Coin.endScope(scope);
                        }
                    }));
                }
            }
        }

        Assertions.assertEquals(24, tests.size());
        return tests;
    }

    /**
     * Only what is new to the wallet this pass is judged at a reported height: a confirmed spend already held is not judged again, so a pass over a
     * wallet that has not changed fetches nothing for it
     */
    @Test
    public void confirmedSpendAlreadyHeldIsNotJudgedAgain() throws Exception {
        Coin scope = Coin.beginScope(Coin.DOGECOIN);
        try {
            Wallet wallet = wallet(Coin.DOGECOIN, PolicyType.SINGLE_HD, ScriptType.P2PKH);
            Transaction funding = fund(wallet);
            Transaction forged = alterOutputs(signedSpend(wallet));
            //Held from before this check existed, at the height it is reported at
            Map<WalletNode, Set<BlockTransactionHash>> history = history(wallet, funding, forged, FUNDING_HEIGHT + 1);
            new ElectrumServer().getReferencedTransactions(wallet, history);
            Assertions.assertTrue(holds(history, forged));
        } finally {
            Coin.endScope(scope);
        }
    }

    /**
     * An input whose spent output is not one of the wallet's is not the wallet's to judge, so an incoming payment is kept and nothing is fetched
     */
    @Test
    public void keepsAnIncomingPaymentOnEveryCoin() throws Exception {
        for(Coin coin : Coin.values()) {
            Coin scope = Coin.beginScope(coin);
            try {
                ElectrumServer.clearPreviousServerState();
                Wallet wallet = wallet(coin, PolicyType.SINGLE_HD, ScriptType.P2PKH);
                Transaction incoming = new Transaction();
                incoming.addInput(Sha256Hash.of(new byte[] {7}), 0, new Script(new byte[0]));
                incoming.addOutput(FUNDING_VALUE, wallet.getAddress(wallet.getNode(KeyPurpose.RECEIVE).getChildren().iterator().next()));
                Set<Sha256Hash> unsigned = new ElectrumServer().getUnsignedTransactions(wallet, Map.of(incoming.getTxId(), incoming), txid -> body(wallet, txid));
                Assertions.assertTrue(unsigned.isEmpty(), coin.getName());
            } finally {
                Coin.endScope(scope);
            }
        }
    }

    /**
     * A spend whose input cannot be read at all (a script sig that does not parse) is left out, like any spend not shown to be signed
     */
    @Test
    public void leavesOutASpendThatCannotBeRead() throws Exception {
        Coin scope = Coin.beginScope(Coin.DOGECOIN);
        try {
            Wallet wallet = wallet(Coin.DOGECOIN, PolicyType.MULTI_HD, ScriptType.P2SH);
            Transaction funding = fund(wallet);
            Transaction unreadable = new Transaction(signedSpend(wallet).bitcoinSerialize());
            unreadable.getInputs().getFirst().setScriptBytes(new byte[] {0x4c});
            unreadable = new Transaction(unreadable.bitcoinSerialize());
            Map<WalletNode, Set<BlockTransactionHash>> history = history(wallet, funding, unreadable, 0);
            new ElectrumServer().getReferencedTransactions(wallet, history);
            Assertions.assertFalse(holds(history, unreadable));
        } finally {
            Coin.endScope(scope);
        }
    }

    /**
     * A Taproot spend commits to every output its transaction spends, so where one of them is not the wallet's it is fetched. Bellscoin, the one coin
     * besides Litecoin with Taproot, is served by Esplora instances. One that has no such transaction answers 404, which now affects only the spend that
     * needed it, as on an Electrum server, rather than failing the whole pass: left out when unconfirmed, kept unchecked when reported as mined, since
     * the input is not the wallet's (see minedTaprootSpendWithAForeignInputTheServerWillNotServeIsKept). An instance that fails otherwise still fails
     * the pass, as a lost connection must.
     */
    @Test
    public void bellscoinTaprootSpendWithAnInputTheEsploraServerWillNotReturn() throws Exception {
        Coin scope = Coin.beginScope(Coin.BELLSCOIN);
        Set<String> asked = ConcurrentHashMap.newKeySet();
        AtomicInteger status = new AtomicInteger(404);
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/", exchange -> {
            asked.add(exchange.getRequestURI().getPath());
            byte[] body = "Transaction not found".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        http.start();
        try {
            EsploraServerRpc esplora = new EsploraServerRpc("http://127.0.0.1:" + http.getAddress().getPort()) {
                @Override
                public Map<Integer, String> getBlockHeaders(Transport transport, Wallet wallet, Set<Integer> blockHeights) {
                    return blockHeights.stream().collect(Collectors.toMap(height -> height, height -> "01000000" + "00".repeat(76)));
                }
            };
            ElectrumServer.electrumServerRpc = esplora;

            for(int height : List.of(0, FUNDING_HEIGHT + 1)) {
                ElectrumServer.clearPreviousServerState();
                asked.clear();
                Wallet wallet = wallet(Coin.BELLSCOIN, PolicyType.SINGLE_HD, ScriptType.P2TR);
                Transaction funding = fund(wallet);
                Sha256Hash foreign = Sha256Hash.of(new byte[] {9});
                Transaction withForeignInput = new Transaction(signedSpend(wallet).bitcoinSerialize());
                withForeignInput.addInput(foreign, 0, new Script(new byte[0]));
                withForeignInput = new Transaction(withForeignInput.bitcoinSerialize());

                //Held unconfirmed from an earlier pass, and now reported at the given height
                Map<WalletNode, Set<BlockTransactionHash>> history = history(wallet, funding, withForeignInput, 0);
                if(height > 0) {
                    history.values().iterator().next().removeIf(reference -> reference.getHeight() <= 0);
                    history.values().iterator().next().add(new BlockTransaction(withForeignInput.getTxId(), height, null, 1000L, null));
                }
                new ElectrumServer().getReferencedTransactions(wallet, history);
                Assertions.assertTrue(asked.contains("/tx/" + foreign + "/hex"), "height " + height + " spent transaction asked for");
                //Unconfirmed, a spend not shown to be signed is left out; reported as mined, one that could not be checked for another party's input is kept
                Assertions.assertEquals(height > 0, holds(history, withForeignInput), "height " + height + (height > 0 ? " spend kept" : " spend left out"));
                Assertions.assertTrue(holds(history, funding), "height " + height + " funding kept");
            }

            //Any other failure is not an answer about the transaction, and fails the pass
            status.set(500);
            ElectrumServer.clearPreviousServerState();
            Wallet wallet = wallet(Coin.BELLSCOIN, PolicyType.SINGLE_HD, ScriptType.P2TR);
            Transaction funding = fund(wallet);
            Transaction withForeignInput = new Transaction(signedSpend(wallet).bitcoinSerialize());
            withForeignInput.addInput(Sha256Hash.of(new byte[] {9}), 0, new Script(new byte[0]));
            withForeignInput = new Transaction(withForeignInput.bitcoinSerialize());
            Map<WalletNode, Set<BlockTransactionHash>> history = history(wallet, funding, withForeignInput, 0);
            Assertions.assertThrows(ServerException.class, () -> new ElectrumServer().getReferencedTransactions(wallet, history));
        } finally {
            http.stop(0);
            ElectrumServer.electrumServerRpc = new NoServer();
            Coin.endScope(scope);
        }
    }

    /**
     * A Taproot signature commits to every output its transaction spends, so a spend with an input that is not the wallet's (a payjoin, a coinjoin)
     * needs that input's transaction, which the server may not serve. Reported as mined, a spend whose only gap is that transaction is kept, forged or
     * not, since nothing in it can be checked and it may be real, with its own warning (once per transaction) rather than the not validly signed one,
     * since a server could make up such an input to keep a forgery. Unconfirmed it is left out, as any spend not shown signed. Where the transaction is
     * served the spend is judged, mined or not. A wallet type whose signatures do not commit to other inputs' outputs gains nothing from such a gap: a
     * forged spend from it is left out, mined or not.
     */
    @Test
    public void minedTaprootSpendWithAForeignInputTheServerWillNotServeIsKept() throws Exception {
        Coin scope = Coin.beginScope(Coin.LITECOIN);
        List<Set<Sha256Hash>> warnings = new ArrayList<>();
        List<Set<Sha256Hash>> uncheckedWarnings = new ArrayList<>();
        Object listener = new Object() {
            @Subscribe
            public void unsignedTransactions(UnsignedTransactionsEvent event) {
                warnings.add(event.getTxids());
            }

            @Subscribe
            public void uncheckedTransactions(UncheckedTransactionsEvent event) {
                uncheckedWarnings.add(event.getTxids());
            }
        };
        EventManager.get().register(listener);
        try {
            for(boolean forged : List.of(false, true)) {
                for(boolean served : List.of(false, true)) {
                    for(int height : List.of(FUNDING_HEIGHT + 1, 0)) {
                        String name = (forged ? "forged" : "honest") + (served ? ", served" : ", not served") + ", height " + height;
                        ElectrumServer.clearPreviousServerState();
                        warnings.clear();
                        uncheckedWarnings.clear();
                        Wallet wallet = wallet(Coin.LITECOIN, PolicyType.SINGLE_HD, ScriptType.P2TR);
                        Transaction funding = fund(wallet);
                        Transaction foreignFunding = new Transaction();
                        foreignFunding.addInput(Sha256Hash.of(new byte[] {8}), 0, new Script(new byte[0]));
                        foreignFunding.addOutput(FUNDING_VALUE, ScriptType.P2WPKH.getOutputScript(Utils.hexToBytes("dd00000000000000000000000000000000000044")));
                        foreignFunding = new Transaction(foreignFunding.bitcoinSerialize());
                        Transaction spend = payjoin(wallet, funding, foreignFunding);
                        Transaction knownForeign = foreignFunding;
                        //The payjoin is signed, as the wallet checks it knowing every spent transaction
                        Assertions.assertEquals(Set.of(), wallet.getUnsignedInputs(spend, txid -> txid.equals(knownForeign.getTxId()) ? knownForeign : body(wallet, txid),
                                wallet.getWalletOutputScripts(), null), "payjoin signed");
                        if(forged) {
                            spend = alterOutputs(spend);
                        }
                        ServingServer server = new ServingServer();
                        server.serve(spend);
                        if(served) {
                            server.serve(foreignFunding);
                        }
                        ElectrumServer.electrumServerRpc = server;

                        Map<WalletNode, Set<BlockTransactionHash>> history = minedHistory(wallet, funding, spend, height);
                        new ElectrumServer().getReferencedTransactions(wallet, history);
                        Assertions.assertTrue(server.requested.contains(foreignFunding.getTxId().toString()), name + ": spent transaction asked for");
                        boolean kept = served ? !forged : height > 0;
                        boolean unchecked = !served && height > 0;
                        Assertions.assertEquals(kept, holds(history, spend), name + (kept ? ": kept" : ": left out"));
                        Assertions.assertEquals(kept ? List.of() : List.of(Set.of(spend.getTxId())), warnings, name + ": not validly signed warnings");
                        Assertions.assertEquals(unchecked ? List.of(Set.of(spend.getTxId())) : List.of(), uncheckedWarnings, name + ": unchecked warnings");

                        //Judged again in this session, as on a later pass: no second warning
                        Transaction judged = spend;
                        new ElectrumServer().getUnsignedTransactions(wallet, Map.of(judged.getTxId(), judged), Map.of(judged.getTxId(), height), txid -> body(wallet, txid));
                        Assertions.assertEquals(unchecked ? 1 : 0, uncheckedWarnings.size(), name + ": unchecked warning once");
                    }
                }
            }

            for(int height : List.of(FUNDING_HEIGHT + 1, 0)) {
                ElectrumServer.clearPreviousServerState();
                Wallet wallet = wallet(Coin.LITECOIN, PolicyType.SINGLE_HD, ScriptType.P2WPKH);
                Transaction funding = fund(wallet);
                Transaction forged = new Transaction(alterOutputs(signedSpend(wallet)).bitcoinSerialize());
                forged.addInput(Sha256Hash.of(new byte[] {9}), 0, new Script(new byte[0]));
                forged = new Transaction(forged.bitcoinSerialize());
                ServingServer server = new ServingServer();
                server.serve(forged);
                ElectrumServer.electrumServerRpc = server;

                Map<WalletNode, Set<BlockTransactionHash>> history = minedHistory(wallet, funding, forged, height);
                new ElectrumServer().getReferencedTransactions(wallet, history);
                Assertions.assertFalse(holds(history, forged), "P2WPKH forged spend with an unknown input of another party, height " + height);
            }
        } finally {
            EventManager.get().unregister(listener);
            ElectrumServer.electrumServerRpc = new NoServer();
            Coin.endScope(scope);
        }
    }

    /**
     * A spend of the wallet's funded output together with another party's output, the wallet's input signed by its key path over both spent outputs
     * as a payjoin would be, the other input carrying a witness the wallet does not check
     */
    private static Transaction payjoin(Wallet wallet, Transaction funding, Transaction foreignFunding) throws Exception {
        WalletNode node = wallet.getNode(KeyPurpose.RECEIVE).getChildren().iterator().next();
        ECKey key = wallet.getKeystores().getFirst().getKey(node);
        Transaction transaction = new Transaction();
        transaction.setVersion(2);
        transaction.setSegwitFlag(Transaction.DEFAULT_SEGWIT_FLAG);
        transaction.addInput(funding.getTxId(), 0, new Script(new byte[0]));
        transaction.addInput(foreignFunding.getTxId(), 0, new Script(new byte[0]));
        transaction.addOutput(2 * FUNDING_VALUE - 10_000L, ScriptType.P2PKH.getOutputScript(Utils.hexToBytes("cc00000000000000000000000000000000000033")));
        List<TransactionOutput> spentOutputs = List.of(funding.getOutputs().getFirst(), foreignFunding.getOutputs().getFirst());
        Sha256Hash hash = transaction.hashForTaprootSignature(spentOutputs, 0, false, null, SigHash.DEFAULT, null);
        byte[] signature = key.getTweakedOutputKey().sign(hash, SigHash.DEFAULT, TransactionSignature.Type.SCHNORR).encodeToBitcoin();
        transaction.getInputs().get(0).setWitness(new TransactionWitness(transaction, List.of(signature)));
        transaction.getInputs().get(1).setWitness(new TransactionWitness(transaction, List.of(new byte[64])));
        return new Transaction(transaction.bitcoinSerialize());
    }

    /**
     * A mined Luckycoin spend is checked by the rule at the height the server says it was mined at, which is what ConnectBlock checks: Bitcoin's hash
     * below 276500, the chain id from 276500 on, but the old rule only where that height fits what the wallet knows (Wallet.getSigHashPrefix(
     * Transaction, Integer)). Luckycoin test chains (testnet parameters) are used so the wallet can sign either way, by the tip it knows. A spend
     * signed by Bitcoin's hash is kept below 276500 and left out from it. One signed with the chain id is kept at both: at 276499 the height is not
     * consistent (it spends an output mined at 800000), so it is judged by the chain id rule it was signed by.
     */
    @Test
    public void luckycoinSpendsReportedAsMinedAreJudgedByTheirHeight() throws Exception {
        Network.set(Network.TESTNET);
        Coin scope = Coin.beginScope(Coin.LUCKYCOIN);
        try {
            for(boolean chainId : List.of(false, true)) {
                for(int height : List.of(Coin.LUCKYCOIN_CHAIN_ID_HEIGHT - 1, Coin.LUCKYCOIN_CHAIN_ID_HEIGHT)) {
                    ElectrumServer.clearPreviousServerState();
                    Wallet wallet = wallet(Coin.LUCKYCOIN, PolicyType.SINGLE_HD, ScriptType.P2PKH);
                    Transaction funding = fund(wallet);
                    Transaction spend = signedSpend(wallet, chainId ? Coin.LUCKYCOIN_CHAIN_ID_HEIGHT : 1000);
                    ServingServer server = new ServingServer();
                    server.serve(spend);
                    ElectrumServer.electrumServerRpc = server;

                    Map<WalletNode, Set<BlockTransactionHash>> mined = minedHistory(wallet, funding, spend, height);
                    new ElectrumServer().getReferencedTransactions(wallet, mined);
                    boolean kept = chainId || height < Coin.LUCKYCOIN_CHAIN_ID_HEIGHT;
                    Assertions.assertEquals(kept, holds(mined, spend), (chainId ? "chain id" : "Bitcoin's hash") + " at height " + height);
                }
            }
        } finally {
            ElectrumServer.electrumServerRpc = new NoServer();
            Coin.endScope(scope);
        }
    }

    /**
     * Signatures are read as consensus reads them: one in an encoding older chains accepted (here a sequence length that does not match) verifies
     * and the spend is kept, mined or not; one consensus cannot read (an R that is not an integer) is no signature, and the spend is left out, mined or
     * not, since a height no header proves is only the server's word.
     */
    @Test
    public void minedSpendSignaturesAreReadAsConsensusReadsThem() throws Exception {
        Coin scope = Coin.beginScope(Coin.DOGECOIN);
        try {
            for(boolean readable : List.of(true, false)) {
                for(int height : List.of(FUNDING_HEIGHT + 1, 0)) {
                    ElectrumServer.clearPreviousServerState();
                    Wallet wallet = wallet(Coin.DOGECOIN, PolicyType.SINGLE_HD, ScriptType.P2PKH);
                    Transaction funding = fund(wallet);
                    Transaction reencoded = new Transaction(signedSpend(wallet).bitcoinSerialize());
                    List<com.sparrowwallet.drongo.protocol.ScriptChunk> chunks = reencoded.getInputs().getFirst().getScriptSig().getChunks();
                    byte[] signature = chunks.getFirst().data.clone();
                    if(readable) {
                        signature[1] = (byte)0x7f;
                    } else {
                        signature[2] = 0x03;
                    }
                    reencoded.getInputs().getFirst().setScriptBytes(new Script(List.of(com.sparrowwallet.drongo.protocol.ScriptChunk.fromData(signature), chunks.get(1))).getProgram());
                    reencoded = new Transaction(reencoded.bitcoinSerialize());
                    ServingServer server = new ServingServer();
                    server.serve(reencoded);
                    ElectrumServer.electrumServerRpc = server;

                    Map<WalletNode, Set<BlockTransactionHash>> history = minedHistory(wallet, funding, reencoded, height);
                    new ElectrumServer().getReferencedTransactions(wallet, history);
                    Assertions.assertEquals(readable, holds(history, reencoded), (readable ? "readable" : "unreadable") + " at height " + height);
                }
            }
        } finally {
            ElectrumServer.electrumServerRpc = new NoServer();
            Coin.endScope(scope);
        }
    }

    /**
     * An exception in the check leaves the spend out, mined or not, with one named case: keystores that repeat a key (DuplicateKeystoreKeysException),
     * a fault of the wallet that no server can cause and that would otherwise hide every mined spend of the wallet. That keeps a mined spend; an
     * unconfirmed one is left out, as upstream does.
     */
    @Test
    public void minedSpendWhoseCheckFailsIsLeftOutUnlessTheWalletRepeatsAKey() throws Exception {
        Coin scope = Coin.beginScope(Coin.DOGECOIN);
        try {
            for(boolean walletFault : List.of(false, true)) {
                for(int height : List.of(FUNDING_HEIGHT + 1, 0)) {
                    ElectrumServer.clearPreviousServerState();
                    Wallet wallet = configure(new Wallet("throws") {
                        @Override
                        public Set<TransactionInput> getUnsignedInputs(Transaction transaction, java.util.function.Function<Sha256Hash, Transaction> inputTransactions,
                                                                       Map<Script, WalletNode> walletOutputScripts, Integer blockHeight) {
                            throw walletFault ? new DuplicateKeystoreKeysException("Duplicate keys") : new IllegalStateException("The check failed");
                        }
                    }, Coin.DOGECOIN, PolicyType.SINGLE_HD, ScriptType.P2PKH);
                    Transaction funding = fund(wallet);
                    Transaction spend = signedSpend(wallet);
                    ServingServer server = new ServingServer();
                    server.serve(spend);
                    ElectrumServer.electrumServerRpc = server;

                    Map<WalletNode, Set<BlockTransactionHash>> history = minedHistory(wallet, funding, spend, height);
                    new ElectrumServer().getReferencedTransactions(wallet, history);
                    Assertions.assertEquals(walletFault && height > 0, holds(history, spend), (walletFault ? "wallet fault" : "other failure") + " at height " + height);
                }
            }
        } finally {
            ElectrumServer.electrumServerRpc = new NoServer();
            Coin.endScope(scope);
        }
    }

    /**
     * A Luckycoin mainnet spend signed by the old rule, mined consistently below 276500, is kept at that height; reported unconfirmed in the same pass
     * it is judged again, by the chain id rule the mempool uses, and left out
     */
    @Test
    public void spendKeptAtAHeightIsJudgedAgainWhenReportedUnconfirmedInTheSamePass() throws Exception {
        Coin scope = Coin.beginScope(Coin.LUCKYCOIN);
        try {
            Wallet wallet = wallet(Coin.LUCKYCOIN, PolicyType.SINGLE_HD, ScriptType.P2PKH);
            Transaction funding = fund(wallet);
            hold(wallet, funding, 276000);
            Transaction spend = oldRuleSpend(wallet);
            ElectrumServer server = new ElectrumServer();
            java.util.function.Function<Sha256Hash, Transaction> lookup = txid -> body(wallet, txid);
            Assertions.assertEquals(Set.of(), server.getUnsignedTransactions(wallet, Map.of(spend.getTxId(), spend), Map.of(spend.getTxId(), 276400), lookup), "kept mined");
            Assertions.assertEquals(Set.of(spend.getTxId()), server.getUnsignedTransactions(wallet, Map.of(spend.getTxId(), spend), Map.of(), lookup), "left out unconfirmed");
        } finally {
            Coin.endScope(scope);
        }
    }

    /**
     * A spend the server reports at several heights is judged by the lowest: reported both mined and unconfirmed, it is judged as unconfirmed
     */
    @Test
    public void lowestOfSeveralReportedHeightsDecides() throws Exception {
        Coin scope = Coin.beginScope(Coin.LUCKYCOIN);
        try {
            for(boolean alsoUnconfirmed : List.of(false, true)) {
                ElectrumServer.clearPreviousServerState();
                Wallet wallet = wallet(Coin.LUCKYCOIN, PolicyType.SINGLE_HD, ScriptType.P2PKH);
                Transaction funding = fund(wallet);
                hold(wallet, funding, 276000);
                Transaction spend = oldRuleSpend(wallet);
                ServingServer server = new ServingServer();
                server.serve(spend);
                ElectrumServer.electrumServerRpc = server;

                WalletNode node = wallet.getNode(KeyPurpose.RECEIVE).getChildren().iterator().next();
                Set<BlockTransactionHash> references = new TreeSet<>(List.of(new BlockTransaction(funding.getTxId(), 276000, new Date(), 0L, null),
                        new BlockTransaction(spend.getTxId(), 276400, null, null, null)));
                if(alsoUnconfirmed) {
                    references.add(new BlockTransaction(spend.getTxId(), 0, null, null, null));
                }
                Map<WalletNode, Set<BlockTransactionHash>> history = new LinkedHashMap<>();
                history.put(node, references);
                new ElectrumServer().getReferencedTransactions(wallet, history);
                Assertions.assertEquals(!alsoUnconfirmed, holds(history, spend), alsoUnconfirmed ? "reported at 276400 and 0" : "reported at 276400 only");
            }
        } finally {
            ElectrumServer.electrumServerRpc = new NoServer();
            Coin.endScope(scope);
        }
    }

    /** Signed by Luckycoin's old rule, as a wallet on a test chain below the height signs: the keys and scripts are the same on every network */
    private static Transaction oldRuleSpend(Wallet wallet) throws Exception {
        Network previous = wallet.getNetwork();
        Integer stored = wallet.getStoredBlockHeight();
        wallet.setNetwork(Network.TESTNET);
        try {
            return signedSpend(wallet, 1000);
        } finally {
            wallet.setNetwork(previous);
            wallet.setStoredBlockHeight(stored);
        }
    }

    private static void hold(Wallet wallet, Transaction transaction, int height) {
        wallet.updateTransactions(Map.of(transaction.getTxId(), new BlockTransaction(transaction.getTxId(), height, height > 0 ? new Date() : null, 0L, transaction)));
    }

    /**
     * The history the server reports for the funded address with the spend first seen at a height, so it is fetched this pass
     */
    private static Map<WalletNode, Set<BlockTransactionHash>> minedHistory(Wallet wallet, Transaction funding, Transaction spend) {
        return minedHistory(wallet, funding, spend, FUNDING_HEIGHT + 1);
    }

    private static Map<WalletNode, Set<BlockTransactionHash>> minedHistory(Wallet wallet, Transaction funding, Transaction spend, int height) {
        WalletNode node = wallet.getNode(KeyPurpose.RECEIVE).getChildren().iterator().next();
        Map<WalletNode, Set<BlockTransactionHash>> nodeTransactionMap = new LinkedHashMap<>();
        nodeTransactionMap.put(node, new TreeSet<>(List.of(new BlockTransaction(funding.getTxId(), FUNDING_HEIGHT, new Date(), 0L, null),
                new BlockTransaction(spend.getTxId(), height, null, null, null))));
        return nodeTransactionMap;
    }

    private static Transaction body(Wallet wallet, Sha256Hash txid) {
        BlockTransaction blockTransaction = wallet.getWalletTransaction(txid);
        return blockTransaction == null ? null : blockTransaction.getTransaction();
    }

    private static boolean isVerifyingSignatures() throws ReflectiveOperationException {
        java.lang.reflect.Method method = ElectrumServer.class.getDeclaredMethod("isVerifyingSignatures");
        method.setAccessible(true);
        return (Boolean)method.invoke(null);
    }

    private static List<ScriptType> scriptTypes(Coin coin, PolicyType policyType) {
        Coin scope = Coin.beginScope(coin);
        try {
            return ScriptType.getAddressableScriptTypes(policyType).stream().filter(scriptType -> scriptType != ScriptType.MWEB).toList();
        } finally {
            Coin.endScope(scope);
        }
    }

    private static Wallet wallet(Coin coin, PolicyType policyType, ScriptType scriptType) throws MnemonicException {
        return configure(new Wallet(coin.getName()), coin, policyType, scriptType);
    }

    private static Wallet configure(Wallet wallet, Coin coin, PolicyType policyType, ScriptType scriptType) throws MnemonicException {
        wallet.setCoin(coin);
        wallet.setPolicyType(policyType);
        wallet.setScriptType(scriptType);
        int keystores = policyType == PolicyType.MULTI_HD ? 3 : 1;
        for(int i = 0; i < keystores; i++) {
            DeterministicSeed seed = new DeterministicSeed(Utils.hexToBytes(String.format("%032x", i + 1)), "", 0);
            Keystore keystore = Keystore.fromSeed(seed, policyType, scriptType.getDefaultDerivation());
            keystore.setLabel("Keystore " + (i + 1));
            wallet.getKeystores().add(keystore);
        }
        wallet.setDefaultPolicy(Policy.getPolicy(policyType, scriptType, wallet.getKeystores(), keystores > 1 ? 2 : null));
        wallet.setStoredBlockHeight(FUNDING_HEIGHT + 6);
        wallet.getNode(KeyPurpose.RECEIVE).fillToIndex(0);
        return wallet;
    }

    private static Transaction fund(Wallet wallet) {
        WalletNode addressNode = wallet.getNode(KeyPurpose.RECEIVE).getChildren().iterator().next();
        Transaction funding = new Transaction();
        funding.addInput(Sha256Hash.of(new byte[] {1}), 0, new Script(new byte[0]));
        funding.addOutput(FUNDING_VALUE, wallet.getAddress(addressNode));
        wallet.updateTransactions(Map.of(funding.getTxId(), new BlockTransaction(funding.getTxId(), FUNDING_HEIGHT, new Date(), 0L, funding)));
        addressNode.getTransactionOutputs().add(new BlockTransactionHashIndex(funding.getTxId(), FUNDING_HEIGHT, new Date(), 0L, 0, FUNDING_VALUE));
        return funding;
    }

    private static Transaction signedSpend(Wallet wallet) throws Exception {
        return signedSpend(wallet, null);
    }

    /** Signed with the wallet knowing the given tip (its stored height), or the one it has where null */
    private static Transaction signedSpend(Wallet wallet, Integer tip) throws Exception {
        TransactionParameters params = new TransactionParameters(List.of(new PriorityUtxoSelector(FUNDING_HEIGHT + 6)), Collections.emptyList(),
                List.of(new Payment(new P2PKHAddress(Utils.hexToBytes("cc00000000000000000000000000000000000033")), "test", 2_000_000_000L, false)),
                Collections.emptyList(), Collections.emptySet(), 10.0d, 1.0d, 1.0d, null, FUNDING_HEIGHT + 6, false, false, true,
                (w, utxos, outputs, vSize, rate) -> 0L);
        PSBT psbt = wallet.createWalletTransaction(params).createPSBT();
        if(tip != null) {
            wallet.setStoredBlockHeight(tip);
        }
        wallet.sign(psbt);
        wallet.finalise(psbt);
        return new Transaction(psbt.extractTransaction().bitcoinSerialize());
    }

    private static Transaction alterOutputs(Transaction signed) {
        Transaction altered = new Transaction(signed.bitcoinSerialize());
        altered.getOutputs().getFirst().setScriptBytes(ScriptType.P2PKH.getOutputScript(Utils.hexToBytes("bb00000000000000000000000000000000000022")).getProgram());
        altered = new Transaction(altered.bitcoinSerialize());
        Assertions.assertNotEquals(signed.getTxId(), altered.getTxId());
        return altered;
    }

    /**
     * The history the server reports for the funded address, with the spend at the given height. The wallet already holds both transactions at
     * those heights, as after the pass that fetched them, so nothing is fetched.
     */
    private static Map<WalletNode, Set<BlockTransactionHash>> history(Wallet wallet, Transaction funding, Transaction spend, int spendHeight) {
        wallet.updateTransactions(Map.of(spend.getTxId(), new BlockTransaction(spend.getTxId(), spendHeight, spendHeight > 0 ? new Date() : null, 1000L, spend)));
        WalletNode node = wallet.getNode(KeyPurpose.RECEIVE).getChildren().iterator().next();
        Map<WalletNode, Set<BlockTransactionHash>> nodeTransactionMap = new LinkedHashMap<>();
        nodeTransactionMap.put(node, new TreeSet<>(List.of(new BlockTransaction(funding.getTxId(), FUNDING_HEIGHT, new Date(), 0L, null),
                new BlockTransaction(spend.getTxId(), spendHeight, spendHeight > 0 ? new Date() : null, 1000L, null))));
        return nodeTransactionMap;
    }

    private static boolean holds(Map<WalletNode, Set<BlockTransactionHash>> nodeTransactionMap, Transaction transaction) {
        return nodeTransactionMap.values().stream().flatMap(Collection::stream).anyMatch(reference -> reference.getHash().equals(transaction.getTxId()));
    }

    private static class NoServer extends SimpleElectrumServerRpc {
        @Override
        public Map<String, String> getTransactions(Transport transport, Wallet wallet, Set<String> txids) {
            throw new AssertionError("Nothing is fetched here: " + txids);
        }
    }

    private static class ServingServer extends SimpleElectrumServerRpc {
        private final Set<String> requested = new LinkedHashSet<>();
        private final Map<String, String> served = new HashMap<>();

        public void serve(Transaction transaction) {
            served.put(transaction.getTxId().toString(), Utils.bytesToHex(transaction.bitcoinSerialize()));
        }

        @Override
        public Map<String, String> getTransactions(Transport transport, Wallet wallet, Set<String> txids) {
            requested.addAll(txids);
            Map<String, String> result = new LinkedHashMap<>();
            txids.forEach(txid -> result.put(txid, served.getOrDefault(txid, Sha256Hash.ZERO_HASH.toString())));
            return result;
        }

        @Override
        public Map<Integer, String> getBlockHeaders(Transport transport, Wallet wallet, Set<Integer> blockHeights) {
            //Any header will do: no coin here verifies them
            return blockHeights.stream().collect(Collectors.toMap(height -> height, height -> "01000000" + "00".repeat(76)));
        }
    }

    private static class UnusedTransport extends TcpTransport {
        public UnusedTransport() {
            super(HostAndPort.fromParts("localhost", 1));
        }

        @Override
        public String pass(String request) {
            throw new UnsupportedOperationException("The fake server answers without the transport");
        }

        @Override
        public boolean isConnected() {
            return true;
        }
    }
}
