package com.sparrowwallet.sparrow.net;

import com.github.arteam.simplejsonrpc.client.Transport;
import com.google.common.net.HostAndPort;
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
import com.sparrowwallet.sparrow.SparrowWallet;
import com.sparrowwallet.sparrow.io.Config;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.*;

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
     * Known gap, pinned: no coin here verifies block headers (Network.supportsHeaderVerification), so a height the server reports is taken as given,
     * and only references at height 0 or below are checked (ElectrumServer.getReferencedTransactions). A forged spend the server first reports as
     * mined is fetched and enters the wallet as confirmed without its signatures being looked at. It cannot be bumped (fee bump, cancel and CPFP are
     * offered only below height 1), but it shows the coins as spent and hides the real spend. Once the server reports it unconfirmed it is checked and
     * left out. Checking unproven confirmed references too would close this, and flip this test.
     */
    @Test
    public void knownGapForgedSpendReportedAsMinedIsNotChecked() throws Exception {
        for(Coin coin : Coin.values()) {
            Coin scope = Coin.beginScope(coin);
            try {
                Assertions.assertFalse(Network.get().supportsHeaderVerification(), coin.getName());

                ElectrumServer.clearPreviousServerState();
                Wallet wallet = wallet(coin, PolicyType.SINGLE_HD, ScriptType.P2PKH);
                Transaction funding = fund(wallet);
                Transaction forged = alterOutputs(signedSpend(wallet));
                ServingServer server = new ServingServer();
                server.serve(forged);
                ElectrumServer.electrumServerRpc = server;

                //First seen at a height: fetched from the server and kept
                WalletNode node = wallet.getNode(KeyPurpose.RECEIVE).getChildren().iterator().next();
                Map<WalletNode, Set<BlockTransactionHash>> mined = new LinkedHashMap<>();
                mined.put(node, new TreeSet<>(List.of(new BlockTransaction(funding.getTxId(), FUNDING_HEIGHT, new Date(), 0L, null),
                        new BlockTransaction(forged.getTxId(), FUNDING_HEIGHT + 1, null, null, null))));
                new ElectrumServer().getReferencedTransactions(wallet, mined);
                Assertions.assertEquals(Set.of(forged.getTxId().toString()), server.requested, coin.getName() + " forged spend fetched");
                Assertions.assertTrue(holds(mined, forged), coin.getName() + " forged spend at a reported height is kept");
                Assertions.assertTrue(wallet.getTransactions().containsKey(forged.getTxId()), coin.getName() + " forged spend in the wallet");

                //Then reported unconfirmed: checked and left out
                Map<WalletNode, Set<BlockTransactionHash>> unconfirmed = new LinkedHashMap<>();
                unconfirmed.put(node, new TreeSet<>(List.of(new BlockTransaction(funding.getTxId(), FUNDING_HEIGHT, new Date(), 0L, null),
                        new BlockTransaction(forged.getTxId(), 0, null, 1000L, null))));
                new ElectrumServer().getReferencedTransactions(wallet, unconfirmed);
                Assertions.assertFalse(holds(unconfirmed, forged), coin.getName() + " the same spend reported unconfirmed is left out");
            } finally {
                ElectrumServer.electrumServerRpc = new NoServer();
                Coin.endScope(scope);
            }
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
     * besides Litecoin with Taproot, is served by Esplora instances, whose EsploraServerRpc.getTransactions throws where the instance does not return a
     * transaction, rather than answering with the error marker an Electrum server gives. The whole history pass then fails, instead of only that spend
     * being left out as on Electrum (UnconfirmedSpendTest.fetchesTheTransactionsATaprootSpendCommitsTo).
     */
    @Test
    public void bellscoinTaprootSpendWithAnInputTheEsploraServerWillNotReturnFailsThePass() throws Exception {
        Coin scope = Coin.beginScope(Coin.BELLSCOIN);
        try {
            Wallet wallet = wallet(Coin.BELLSCOIN, PolicyType.SINGLE_HD, ScriptType.P2TR);
            Transaction funding = fund(wallet);
            Transaction withForeignInput = new Transaction(signedSpend(wallet).bitcoinSerialize());
            withForeignInput.addInput(Sha256Hash.of(new byte[] {9}), 0, new Script(new byte[0]));
            withForeignInput = new Transaction(withForeignInput.bitcoinSerialize());
            Map<WalletNode, Set<BlockTransactionHash>> history = history(wallet, funding, withForeignInput, 0);
            ElectrumServer.electrumServerRpc = new SimpleElectrumServerRpc() {
                @Override
                public Map<String, String> getTransactions(Transport transport, Wallet wallet, Set<String> txids) {
                    throw new ElectrumServerRpcException("Failed to retrieve transaction " + txids.iterator().next(), new RuntimeException("HTTP 404"));
                }
            };
            Assertions.assertThrows(ServerException.class, () -> new ElectrumServer().getReferencedTransactions(wallet, history));
        } finally {
            ElectrumServer.electrumServerRpc = new NoServer();
            Coin.endScope(scope);
        }
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
        Wallet wallet = new Wallet(coin.getName());
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
        TransactionParameters params = new TransactionParameters(List.of(new PriorityUtxoSelector(FUNDING_HEIGHT + 6)), Collections.emptyList(),
                List.of(new Payment(new P2PKHAddress(Utils.hexToBytes("cc00000000000000000000000000000000000033")), "test", 2_000_000_000L, false)),
                Collections.emptyList(), Collections.emptySet(), 10.0d, 1.0d, 1.0d, null, FUNDING_HEIGHT + 6, false, false, true,
                (w, utxos, outputs, vSize, rate) -> 0L);
        PSBT psbt = wallet.createWalletTransaction(params).createPSBT();
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
            return Collections.emptyMap();
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
