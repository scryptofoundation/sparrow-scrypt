package com.sparrowwallet.sparrow.net;

import com.google.common.net.HostAndPort;
import com.sparrowwallet.drongo.Coin;
import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.drongo.policy.PolicyType;
import com.sparrowwallet.sparrow.io.Server;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public enum PublicElectrumServer {
    AFTREK_ORG("ltc.aftrek.org", "ssl://ltc.aftrek.org:50002", Coin.LITECOIN, Network.MAINNET),
    BYSH_ME("electrum-ltc.bysh.me", "ssl://electrum-ltc.bysh.me:50002", Coin.LITECOIN, Network.MAINNET),
    CIPIG_NET("electrum1.cipig.net", "ssl://electrum1.cipig.net:20063", Coin.LITECOIN, Network.MAINNET),
    ELECTRUM_LTC_ORG("backup.electrum-ltc.org", "ssl://backup.electrum-ltc.org:443", Coin.LITECOIN, Network.MAINNET),
    FEM_SH("l.fem.sh", "ssl://l.fem.sh:50002", Coin.LITECOIN, Network.MAINNET),
    FIATFAUCET_COM("fallacy.fiatfaucet.com", "ssl://fallacy.fiatfaucet.com:50002", Coin.LITECOIN, Network.MAINNET),
    JHOENICKE_DE("electrum.jhoenicke.de", "ssl://electrum.jhoenicke.de:50091", Coin.LITECOIN, Network.MAINNET),
    JOCHEN_HOENICKE_DE("electrum.jochen-hoenicke.de", "ssl://electrum.jochen-hoenicke.de:50091", Coin.LITECOIN, Network.MAINNET),
    PETRKR_NET("electrum.petrkr.net", "ssl://electrum.petrkr.net:60002", Coin.LITECOIN, Network.MAINNET),
    RENTONISK_COM("ltc.rentonisk.com", "ssl://ltc.rentonisk.com:50002", Coin.LITECOIN, Network.MAINNET),
    XURIOUS_COM("electrum.ltc.xurious.com", "ssl://electrum.ltc.xurious.com:50002", Coin.LITECOIN, Network.MAINNET),
    TESTNET_BYSH_ME("electrum-ltc.bysh.me", "ssl://electrum-ltc.bysh.me:51002", Coin.LITECOIN, Network.TESTNET),
    TESTNET_XURIOUS_COM("electrum.ltc.xurious.com", "ssl://electrum.ltc.xurious.com:51002", Coin.LITECOIN, Network.TESTNET),

    //Dogecoin public ElectrumX servers, verified reachable 2026-07-19. Three independent operators
    //(Komodo/cipig, Stack Wallet, aftrek), all running maintained ElectrumX with AuxPoW support.
    DOGE_CIPIG_1("doge.electrum1.cipig.net", "ssl://doge.electrum1.cipig.net:20060", Coin.DOGECOIN, Network.MAINNET),
    DOGE_CIPIG_2("doge.electrum2.cipig.net", "ssl://doge.electrum2.cipig.net:20060", Coin.DOGECOIN, Network.MAINNET),
    DOGE_STACKWALLET("dogecoin.stackwallet.com", "ssl://dogecoin.stackwallet.com:50022", Coin.DOGECOIN, Network.MAINNET),
    DOGE_AFTREK("doge.aftrek.org", "ssl://doge.aftrek.org:50002", Coin.DOGECOIN, Network.MAINNET),

    //Dingocoin public ElectrumX 1.19 servers (SSL 3342), verified 2026-10-17: Electrum handshake answers,
    //tip ~2.8M. Dingocoin shares Dogecoin's genesis hash, so tip height, not genesis, is what tells the
    //chains apart - never mix these with the Dogecoin entries above.
    DINGO_SEED1("seed1.dingocoin.com", "ssl://seed1.dingocoin.com:3342", Coin.DINGOCOIN, Network.MAINNET),
    DINGO_SEED3("seed3.dingocoin.com", "ssl://seed3.dingocoin.com:3342", Coin.DINGOCOIN, Network.MAINNET),
    DINGO_KMSOFTWARE("elecx2.kmsoftware.org", "ssl://elecx2.kmsoftware.org:3342", Coin.DINGOCOIN, Network.MAINNET),

    //Esplora REST endpoints for the chains with no public Electrum servers - detected by URL scheme and
    //served via EsploraServerRpc rather than the Electrum protocol (see ElectrumServer.isEsploraServer).
    //All verified live (tip height responds) on 2026-07-22.
    BELLS_NINTONDO("api.nintondo.io", "https://api.nintondo.io", Coin.BELLSCOIN, Network.MAINNET),
    PEPE_PEPPOOL("peppool.space", "https://peppool.space/api", Coin.PEPECOIN, Network.MAINNET),
    LUCKY_LUCKYSCAN("luckyscan.org", "https://luckyscan.org/api", Coin.LUCKYCOIN, Network.MAINNET),
    JUNK_EXPLORER("explorer.junk-coin.com", "https://explorer.junk-coin.com/api", Coin.JUNKCOIN, Network.MAINNET),

    //Pepecoin testnet Esplora (Nintondo), verified 2026-10-23: tip ~1.55M, genesis f9f4ea4a... matches
    PEPE_TESTNET_NINTONDO("pepe-testnet-api.nintondo.io", "https://pepe-testnet-api.nintondo.io", Coin.PEPECOIN, Network.TESTNET),

    //Junkcoin testnet Esplora, verified 2026-10-23 at tip 248438 with the testnet block 1 (ccedee1f...), which is
    //how it is told apart from mainnet (the two chains share a genesis hash). explorer.junk-coin.com/testnet/api
    //serves the same chain but has no POST /tx, so a wallet rotated onto it could not broadcast: not listed.
    JUNK_TESTNET_S3NA("jkc-testnet-api.s3na.xyz", "https://jkc-testnet-api.s3na.xyz", Coin.JUNKCOIN, Network.TESTNET);

    PublicElectrumServer(String name, String url, Coin coin, Network network) {
        this(name, url, coin, network, List.of(PolicyType.SINGLE_HD, PolicyType.MULTI_HD));
    }

    PublicElectrumServer(String name, String url, Coin coin, Network network, List<PolicyType> supportedPolicyTypes) {
        this.server = new Server(url, name);
        this.coin = coin;
        this.network = network;
        this.supportedPolicyTypes = supportedPolicyTypes;
    }

    public static final List<Network> SUPPORTED_NETWORKS = List.of(Network.MAINNET, Network.TESTNET, Network.SIGNET, Network.TESTNET4);

    private final Server server;
    private final Coin coin;
    private final Network network;
    private final List<PolicyType> supportedPolicyTypes;

    public Server getServer() {
        return server;
    }

    public String getUrl() {
        return server.getUrl();
    }

    public Coin getCoin() {
        return coin;
    }

    public Network getNetwork() {
        return network;
    }

    public boolean isSupportedPolicyType(PolicyType policyType) {
        return supportedPolicyTypes.contains(policyType);
    }

    public boolean supportsAllPolicyTypes(List<PolicyType> policyTypes) {
        return policyTypes.stream().allMatch(this::isSupportedPolicyType);
    }

    public static List<PublicElectrumServer> getServers() {
        return Arrays.stream(values()).filter(server -> server.coin == Coin.get() && server.network == Network.get()).collect(Collectors.toList());
    }

    /**
     * Whether the "Public Server" option applies to the active coin and network. False for chains with
     * no known public servers, which routes their configuration to a
     * Core node or a private/self-hosted Electrum server instead.
     */
    public static boolean supportedNetwork() {
        return SUPPORTED_NETWORKS.contains(Network.get()) && Arrays.stream(values()).anyMatch(server -> server.coin == Coin.get());
    }

    public static PublicElectrumServer fromServer(Server server) {
        for(PublicElectrumServer publicServer : values()) {
            if(publicServer.getServer().equals(server)) {
                return publicServer;
            }
        }

        return null;
    }

    public static boolean isPublicServer(HostAndPort hostAndPort) {
        for(PublicElectrumServer publicServer : values()) {
            if(publicServer.getServer().getHostAndPort().equals(hostAndPort)) {
                return true;
            }
        }

        return false;
    }

    @Override
    public String toString() {
        return server.getAlias();
    }
}
