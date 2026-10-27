package com.sparrowwallet.sparrow.io;

import com.google.gson.*;
import com.sparrowwallet.drongo.BitcoinUnit;
import com.sparrowwallet.drongo.Coin;
import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.drongo.protocol.Transaction;
import com.sparrowwallet.sparrow.SparrowWallet;
import com.sparrowwallet.sparrow.UnitFormat;
import com.sparrowwallet.sparrow.Mode;
import com.sparrowwallet.sparrow.Theme;
import com.sparrowwallet.sparrow.control.QRDensity;
import com.sparrowwallet.sparrow.control.QREncoding;
import com.sparrowwallet.sparrow.control.WebcamResolution;
import com.sparrowwallet.sparrow.net.*;
import com.sparrowwallet.sparrow.net.ord.InscriptionServerMode;
import com.sparrowwallet.sparrow.net.ord.OrdServer;
import com.sparrowwallet.sparrow.wallet.FeeRatesSelection;
import com.sparrowwallet.sparrow.wallet.OptimizationStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.lang.reflect.Type;
import java.util.*;
import java.util.stream.Collectors;

import static com.sparrowwallet.sparrow.AppServices.ENUMERATE_HW_PERIOD_SECS;
import static com.sparrowwallet.sparrow.net.PagedBatchRequestBuilder.DEFAULT_PAGE_SIZE;
import static com.sparrowwallet.sparrow.net.TcpTransport.DEFAULT_MAX_TIMEOUT;
import static com.sparrowwallet.sparrow.wallet.WalletUtxosEntry.DUST_ATTACK_THRESHOLD_SATS;

public class Config {
    private static final Logger log = LoggerFactory.getLogger(Config.class);

    public static final String CONFIG_FILENAME = "config";

    private Mode mode;
    private BitcoinUnit bitcoinUnit;
    private UnitFormat unitFormat;
    private Server blockExplorer;
    private FeeRatesSource feeRatesSource;
    private FeeRatesSelection feeRatesSelection;
    private OptimizationStrategy sendOptimizationStrategy;
    private Currency fiatCurrency;
    private ExchangeSource exchangeSource;
    private boolean loadRecentWallets = true;
    private boolean validateDerivationPaths = true;
    private boolean groupByAddress = true;
    private boolean includeMempoolOutputs = true;
    private boolean notifyNewTransactions = true;
    private boolean checkNewVersions = true;
    private Theme theme;
    private boolean openWalletsInNewWindows = false;
    private boolean chunkAddresses = true;
    private boolean hideEmptyUsedAddresses = false;
    private boolean hideAmounts = false;
    private boolean showTransactionHex = true;
    private boolean showLoadingLog = true;
    private boolean showAddressTransactionCount = false;
    private boolean showDeprecatedImportExport = false;
    private boolean signBsmsExports = false;
    private boolean preventSleep = false;
    private Boolean connectToBroadcast;
    private Boolean connectToResolve;
    private Boolean suggestSendToMany;
    private Boolean suggestChangeWalletsDir;
    private File walletsDir;
    private List<File> recentWalletFiles;
    private List<String> litVmTokens;
    private List<String> litVmBridgeHistory;
    private Integer keyDerivationPeriod;
    private long dustAttackThreshold = DUST_ATTACK_THRESHOLD_SATS;
    private int enumerateHwPeriod = ENUMERATE_HW_PERIOD_SECS;
    private QRDensity qrDensity;
    private QREncoding qrEncoding;
    private WebcamResolution webcamResolution;
    private boolean mirrorCapture = true;
    private boolean useZbar = true;
    private String webcamDevice;
    private String webcamDeviceId;
    private ServerType serverType;
    private Server publicElectrumServer;
    private Server coreServer;
    private List<Server> recentCoreServers;
    private CoreAuthType coreAuthType;
    private File coreDataDir;
    private String coreAuth;
    private boolean useLegacyCoreWallet;
    private boolean legacyServer;
    private Server electrumServer;
    private List<Server> recentElectrumServers;
    private File electrumServerCert;
    private Map<String, ServerSettings> coinServers;
    private Map<String, Server> coinBlockExplorers;
    private Map<String, InscriptionSettings> coinInscriptions;
    private boolean useProxy;
    private String proxyServer;
    private boolean autoSwitchProxy = true;
    private int maxServerTimeout = DEFAULT_MAX_TIMEOUT;
    private int maxPageSize = DEFAULT_PAGE_SIZE;
    private boolean usePayNym;
    private Map<String, String> linkedMwebWallets;
    private boolean mempoolFullRbf;
    private double minRelayFeeRate = Transaction.DEFAULT_MIN_RELAY_FEE;
    private Double appWidth;
    private Double appHeight;

    private static Config INSTANCE;

    private static Gson getGson() {
        GsonBuilder gsonBuilder = new GsonBuilder();
        gsonBuilder.registerTypeAdapter(File.class, new FileSerializer());
        gsonBuilder.registerTypeAdapter(File.class, new FileDeserializer());
        gsonBuilder.registerTypeAdapter(Server.class, new ServerSerializer());
        gsonBuilder.registerTypeAdapter(Server.class, new ServerDeserializer());
        return gsonBuilder.setPrettyPrinting().disableHtmlEscaping().create();
    }

    private static File getConfigFile() {
        File sparrowDir = Storage.getSparrowDir();
        return new File(sparrowDir, CONFIG_FILENAME);
    }

    private static Config load() {
        File configFile = getConfigFile();
        if(configFile.exists()) {
            try {
                Reader reader = new FileReader(configFile);
                Config config = getGson().fromJson(reader, Config.class);
                reader.close();

                if(config != null) {
                    return config;
                }
            } catch(Exception e) {
                log.error("Error opening " + configFile.getAbsolutePath(), e);
                //Ignore and assume no config
            }
        }

        return new Config();
    }

    public static synchronized Config get() {
        if(INSTANCE == null) {
            INSTANCE = load();
        }

        return INSTANCE;
    }

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
        flush();
    }

    public BitcoinUnit getBitcoinUnit() {
        return bitcoinUnit;
    }

    public void setBitcoinUnit(BitcoinUnit bitcoinUnit) {
        this.bitcoinUnit = bitcoinUnit;
        flush();
    }

    public UnitFormat getUnitFormat() {
        return unitFormat;
    }

    public void setUnitFormat(UnitFormat unitFormat) {
        this.unitFormat = unitFormat;
        flush();
    }

    public boolean isBlockExplorerDisabled() {
        return BlockExplorer.NONE.getServer().equals(getBlockExplorer());
    }

    //The block explorer is chosen per coin, like the server settings above: each chain has its own explorer,
    //so one selection cannot serve them all. The flat field remains the startup coin's choice.
    public Server getBlockExplorer() {
        if(useFlatCoinSettings()) {
            return blockExplorer;
        }

        return coinBlockExplorers == null ? null : coinBlockExplorers.get(Coin.get().getName());
    }

    public void setBlockExplorer(Server blockExplorer) {
        if(useFlatCoinSettings()) {
            this.blockExplorer = blockExplorer;
        } else {
            if(coinBlockExplorers == null) {
                coinBlockExplorers = new LinkedHashMap<>();
            }
            coinBlockExplorers.put(Coin.get().getName(), blockExplorer);
        }
        flush();
    }

    public FeeRatesSource getFeeRatesSource() {
        return feeRatesSource;
    }

    public void setFeeRatesSource(FeeRatesSource feeRatesSource) {
        this.feeRatesSource = feeRatesSource;
        flush();
    }

    public FeeRatesSelection getFeeRatesSelection() {
        return feeRatesSelection;
    }

    public void setFeeRatesSelection(FeeRatesSelection feeRatesSelection) {
        this.feeRatesSelection = feeRatesSelection;
        flush();
    }

    public OptimizationStrategy getSendOptimizationStrategy() {
        return sendOptimizationStrategy;
    }

    public void setSendOptimizationStrategy(OptimizationStrategy sendOptimizationStrategy) {
        this.sendOptimizationStrategy = sendOptimizationStrategy;
        flush();
    }

    public Currency getFiatCurrency() {
        return fiatCurrency;
    }

    public void setFiatCurrency(Currency fiatCurrency) {
        this.fiatCurrency = fiatCurrency;
        flush();
    }

    public boolean isFetchRates() {
        return getExchangeSource() != ExchangeSource.NONE;
    }

    public ExchangeSource getExchangeSource() {
        return exchangeSource;
    }

    public void setExchangeSource(ExchangeSource exchangeSource) {
        this.exchangeSource = exchangeSource;
        flush();
    }

    public boolean isLoadRecentWallets() {
        return loadRecentWallets;
    }

    public void setLoadRecentWallets(boolean loadRecentWallets) {
        this.loadRecentWallets = loadRecentWallets;
        flush();
    }

    public boolean isValidateDerivationPaths() {
        return validateDerivationPaths;
    }

    public void setValidateDerivationPaths(boolean validateDerivationPaths) {
        this.validateDerivationPaths = validateDerivationPaths;
        flush();
    }

    public boolean isGroupByAddress() {
        return groupByAddress;
    }

    public void setGroupByAddress(boolean groupByAddress) {
        this.groupByAddress = groupByAddress;
        flush();
    }

    public boolean isIncludeMempoolOutputs() {
        return includeMempoolOutputs;
    }

    public void setIncludeMempoolOutputs(boolean includeMempoolOutputs) {
        this.includeMempoolOutputs = includeMempoolOutputs;
        flush();
    }

    public boolean isNotifyNewTransactions() {
        return notifyNewTransactions;
    }

    public void setNotifyNewTransactions(boolean notifyNewTransactions) {
        this.notifyNewTransactions = notifyNewTransactions;
        flush();
    }

    public boolean isCheckNewVersions() {
        return checkNewVersions;
    }

    public void setCheckNewVersions(boolean checkNewVersions) {
        this.checkNewVersions = checkNewVersions;
        flush();
    }

    public Theme getTheme() {
        return theme;
    }

    public void setTheme(Theme theme) {
        this.theme = theme;
        flush();
    }

    public boolean isOpenWalletsInNewWindows() {
        return openWalletsInNewWindows;
    }

    public void setOpenWalletsInNewWindows(boolean openWalletsInNewWindows) {
        this.openWalletsInNewWindows = openWalletsInNewWindows;
        flush();
    }

    public boolean isChunkAddresses() {
        return chunkAddresses;
    }

    public void setChunkAddresses(boolean chunkAddresses) {
        this.chunkAddresses = chunkAddresses;
        flush();
    }

    public boolean isHideEmptyUsedAddresses() {
        return hideEmptyUsedAddresses;
    }

    public void setHideEmptyUsedAddresses(boolean hideEmptyUsedAddresses) {
        this.hideEmptyUsedAddresses = hideEmptyUsedAddresses;
        flush();
    }

    public boolean isHideAmounts() {
        return hideAmounts;
    }

    public void setHideAmounts(boolean hideAmounts) {
        this.hideAmounts = hideAmounts;
        flush();
    }

    public boolean isShowTransactionHex() {
        return showTransactionHex;
    }

    public void setShowTransactionHex(boolean showTransactionHex) {
        this.showTransactionHex = showTransactionHex;
        flush();
    }

    public boolean isShowLoadingLog() {
        return showLoadingLog;
    }

    public void setShowLoadingLog(boolean showLoadingLog) {
        this.showLoadingLog = showLoadingLog;
        flush();
    }

    public boolean isShowAddressTransactionCount() {
        return showAddressTransactionCount;
    }

    public void setShowAddressTransactionCount(boolean showAddressTransactionCount) {
        this.showAddressTransactionCount = showAddressTransactionCount;
        flush();
    }

    public boolean isShowDeprecatedImportExport() {
        return showDeprecatedImportExport;
    }

    public void setShowDeprecatedImportExport(boolean showDeprecatedImportExport) {
        this.showDeprecatedImportExport = showDeprecatedImportExport;
        flush();
    }

    public boolean isSignBsmsExports() {
        return signBsmsExports;
    }

    public void setSignBsmsExports(boolean signBsmsExports) {
        this.signBsmsExports = signBsmsExports;
        flush();
    }

    public boolean isPreventSleep() {
        return preventSleep;
    }

    public void setPreventSleep(boolean preventSleep) {
        this.preventSleep = preventSleep;
        flush();
    }

    public Boolean getConnectToBroadcast() {
        return connectToBroadcast;
    }

    public void setConnectToBroadcast(Boolean connectToBroadcast) {
        this.connectToBroadcast = connectToBroadcast;
        flush();
    }

    public Boolean getConnectToResolve() {
        return connectToResolve;
    }

    public void setConnectToResolve(Boolean connectToResolve) {
        this.connectToResolve = connectToResolve;
        flush();
    }

    public Boolean getSuggestSendToMany() {
        return suggestSendToMany;
    }

    public void setSuggestSendToMany(Boolean suggestSendToMany) {
        this.suggestSendToMany = suggestSendToMany;
        flush();
    }

    public Boolean getSuggestChangeWalletsDir() {
        return suggestChangeWalletsDir;
    }

    public void setSuggestChangeWalletsDir(Boolean suggestChangeWalletsDir) {
        this.suggestChangeWalletsDir = suggestChangeWalletsDir;
        flush();
    }

    public File getWalletsDir() {
        return walletsDir;
    }

    public void setWalletsDir(File walletsDir) {
        this.walletsDir = walletsDir;
        flush();
    }

    public List<File> getRecentWalletFiles() {
        return recentWalletFiles;
    }

    /** ERC-20 contract addresses the user tracks in LitVM tabs (public, not wallet-specific). */
    public List<String> getLitVmTokens() {
        return litVmTokens == null ? new ArrayList<>() : new ArrayList<>(litVmTokens);
    }

    public void setLitVmTokens(List<String> litVmTokens) {
        this.litVmTokens = new ArrayList<>(litVmTokens);
        flush();
    }

    /** Bridge transactions made from LitVM tabs, as "kind|txHash|evmAddress" (public chain data, no secrets). */
    public List<String> getLitVmBridgeHistory() {
        return litVmBridgeHistory == null ? new ArrayList<>() : new ArrayList<>(litVmBridgeHistory);
    }

    public void setLitVmBridgeHistory(List<String> litVmBridgeHistory) {
        this.litVmBridgeHistory = new ArrayList<>(litVmBridgeHistory);
        flush();
    }

    public void setRecentWalletFiles(List<File> recentWalletFiles) {
        this.recentWalletFiles = recentWalletFiles;
        flush();
    }

    public Integer getKeyDerivationPeriod() {
        return keyDerivationPeriod;
    }

    public void setKeyDerivationPeriod(Integer keyDerivationPeriod) {
        this.keyDerivationPeriod = keyDerivationPeriod;
        flush();
    }

    public long getDustAttackThreshold() {
        return dustAttackThreshold;
    }

    public int getEnumerateHwPeriod() {
        return enumerateHwPeriod;
    }

    public QRDensity getQrDensity() {
        return qrDensity == null ? QRDensity.NORMAL : qrDensity;
    }

    public void setQrDensity(QRDensity qrDensity) {
        this.qrDensity = qrDensity;
        flush();
    }

    public QREncoding getQrEncoding() {
        return qrEncoding;
    }

    public void setQrEncoding(QREncoding qrEncoding) {
        this.qrEncoding = qrEncoding;
        flush();
    }

    public WebcamResolution getWebcamResolution() {
        return webcamResolution;
    }

    public void setWebcamResolution(WebcamResolution webcamResolution) {
        this.webcamResolution = webcamResolution;
        flush();
    }

    public boolean isMirrorCapture() {
        return mirrorCapture;
    }

    public void setMirrorCapture(boolean mirrorCapture) {
        this.mirrorCapture = mirrorCapture;
        flush();
    }

    public boolean isUseZbar() {
        return useZbar;
    }

    public String getWebcamDevice() {
        return webcamDevice;
    }

    public void setWebcamDevice(String webcamDevice) {
        this.webcamDevice = webcamDevice;
        flush();
    }

    public String getWebcamDeviceId() {
        return webcamDeviceId;
    }

    public void setWebcamDeviceId(String webcamDeviceId) {
        this.webcamDeviceId = webcamDeviceId;
        flush();
    }

    //Server connection settings for one coin (one app, per-wallet coin). The flat fields on Config remain
    //the store for the coin the process started as, so existing configs keep working unchanged; every other
    //coin gets an entry here, keyed by Coin name. All server accessors resolve against the active coin -
    //the focused wallet's coin on the FX thread, the syncing wallet's coin inside Coin.beginScope.
    private static class ServerSettings {
        private ServerType serverType;
        private Server publicElectrumServer;
        private Server coreServer;
        private List<Server> recentCoreServers;
        private CoreAuthType coreAuthType;
        private File coreDataDir;
        private String coreAuth;
        private boolean useLegacyCoreWallet;
        private boolean legacyServer;
        private Server electrumServer;
        private List<Server> recentElectrumServers;
        private File electrumServerCert;
    }

    //Where inscription and rune data comes from, per coin (Litecoin only for now). Off unless the user turns it on,
    //because a public server sees every output it is asked about.
    private static class InscriptionSettings {
        private InscriptionServerMode mode;
        private String customUrl;
        private boolean publicWarningShown;
    }

    private InscriptionSettings inscriptionSettings() {
        return coinInscriptions == null ? null : coinInscriptions.get(Coin.get().getName());
    }

    private InscriptionSettings editInscriptionSettings() {
        if(coinInscriptions == null) {
            coinInscriptions = new LinkedHashMap<>();
        }
        return coinInscriptions.computeIfAbsent(Coin.get().getName(), name -> new InscriptionSettings());
    }

    public InscriptionServerMode getInscriptionServerMode() {
        InscriptionSettings settings = inscriptionSettings();
        return settings == null || settings.mode == null ? InscriptionServerMode.OFF : settings.mode;
    }

    public String getInscriptionCustomUrl() {
        InscriptionSettings settings = inscriptionSettings();
        return settings == null ? null : settings.customUrl;
    }

    /** The server to ask, or null when checks are off, no server fits this network, or the custom URL is invalid. */
    public String getInscriptionServerUrl() {
        try {
            return OrdServer.resolveUrl(getInscriptionServerMode(), getInscriptionCustomUrl(), Network.get());
        } catch(IllegalArgumentException e) {
            return null;
        }
    }

    public void setInscriptionServer(InscriptionServerMode mode, String customUrl) {
        InscriptionSettings settings = editInscriptionSettings();
        settings.mode = mode;
        if(customUrl != null) {
            settings.customUrl = customUrl;
        }
        flush();
    }

    public boolean isInscriptionPublicWarningShown() {
        InscriptionSettings settings = inscriptionSettings();
        return settings != null && settings.publicWarningShown;
    }

    public void setInscriptionPublicWarningShown() {
        editInscriptionSettings().publicWarningShown = true;
        flush();
    }

    static Coin getStartupCoin() {
        String coinProperty = System.getProperty(SparrowWallet.COIN_SYSTEM_PROPERTY, System.getenv(SparrowWallet.COIN_ENV_PROPERTY));
        if(coinProperty != null) {
            try {
                return Coin.valueOf(coinProperty.toUpperCase(Locale.ROOT));
            } catch(Exception e) {
                //Invalid value already warned about at startup
            }
        }

        return Coin.LITECOIN;
    }

    private boolean useFlatCoinSettings() {
        return Coin.get() == getStartupCoin();
    }

    private ServerSettings coinSettings() {
        return coinServers == null ? null : coinServers.get(Coin.get().getName());
    }

    private ServerSettings editCoinSettings() {
        if(coinServers == null) {
            coinServers = new LinkedHashMap<>();
        }

        return coinServers.computeIfAbsent(Coin.get().getName(), name -> new ServerSettings());
    }

    public ServerType getServerType() {
        if(useFlatCoinSettings()) {
            return serverType;
        }
        ServerSettings settings = coinSettings();
        ServerType type = settings == null ? null : settings.serverType;
        if(type == null && PublicElectrumServer.supportedNetwork()) {
            //A coin with built-in public servers connects out of the box - no per-coin setup required.
            //The startup coin keeps its stored value so the first-run flow is unchanged.
            return ServerType.PUBLIC_ELECTRUM_SERVER;
        }
        return type;
    }

    public void setServerType(ServerType serverType) {
        if(useFlatCoinSettings()) {
            this.serverType = serverType;
        } else {
            editCoinSettings().serverType = serverType;
        }
        flush();
    }

    public boolean hasServer() {
        return getServer() != null;
    }

    public Server getServer() {
        return getServerType() == ServerType.BITCOIN_CORE ? getCoreServer() : (getServerType() == ServerType.PUBLIC_ELECTRUM_SERVER ? getPublicElectrumServer() : getElectrumServer());
    }

    public String getServerDisplayName() {
        return getServer() == null ? "server" : getServer().getDisplayName();
    }

    public boolean requiresInternalTor() {
        if(isUseProxy()) {
            return false;
        }

        return requiresTor();
    }

    public boolean requiresTor() {
        if(!hasServer()) {
            return false;
        }

        return getServer().isOnionAddress();
    }

    public Server getPublicElectrumServer() {
        if(useFlatCoinSettings()) {
            return publicElectrumServer;
        }
        ServerSettings settings = coinSettings();
        Server server = settings == null ? null : settings.publicElectrumServer;
        if(server == null) {
            //Auto-select the first built-in public server for this coin (failure rotation via
            //changePublicServer still applies once connections are attempted)
            List<PublicElectrumServer> servers = PublicElectrumServer.getServers();
            if(!servers.isEmpty()) {
                return servers.getFirst().getServer();
            }
        }
        return server;
    }

    public void setPublicElectrumServer(Server publicElectrumServer) {
        if(useFlatCoinSettings()) {
            this.publicElectrumServer = publicElectrumServer;
        } else {
            editCoinSettings().publicElectrumServer = publicElectrumServer;
        }
        flush();
    }

    public void changePublicServer() {
        List<Server> otherServers = PublicElectrumServer.getServers().stream().map(PublicElectrumServer::getServer).filter(server -> !server.equals(getPublicElectrumServer())).collect(Collectors.toList());
        if(!otherServers.isEmpty()) {
            setPublicElectrumServer(otherServers.get(new Random().nextInt(otherServers.size())));
        }
    }

    public Server getCoreServer() {
        if(useFlatCoinSettings()) {
            return coreServer;
        }
        ServerSettings settings = coinSettings();
        return settings == null ? null : settings.coreServer;
    }

    public void setCoreServer(Server coreServer) {
        if(useFlatCoinSettings()) {
            this.coreServer = coreServer;
        } else {
            editCoinSettings().coreServer = coreServer;
        }
        flush();
    }

    public List<Server> getRecentCoreServers() {
        List<Server> recent;
        if(useFlatCoinSettings()) {
            recent = recentCoreServers;
        } else {
            ServerSettings settings = coinSettings();
            recent = settings == null ? null : settings.recentCoreServers;
        }
        return recent == null ? new ArrayList<>() : recent;
    }

    private List<Server> editRecentCoreServers() {
        if(useFlatCoinSettings()) {
            if(recentCoreServers == null) {
                recentCoreServers = new ArrayList<>();
            }
            return recentCoreServers;
        }
        ServerSettings settings = editCoinSettings();
        if(settings.recentCoreServers == null) {
            settings.recentCoreServers = new ArrayList<>();
        }
        return settings.recentCoreServers;
    }

    public boolean addRecentCoreServer(Server coreServer) {
        List<Server> recent = editRecentCoreServers();
        int index = recent.indexOf(coreServer);
        if(index < 0) {
            recent.removeIf(server -> server.getHost().equals(coreServer.getHost()) && server.getAlias() == null);
            recent.add(coreServer);
            flush();
            return true;
        }

        return false;
    }

    public void removeRecentCoreServer(Server server) {
        List<Server> recent = editRecentCoreServers();
        int index = recent.indexOf(server);
        if(index >= 0) {
            recent.remove(index);
            flush();
        }
    }

    public void setCoreServerAlias(Server server) {
        List<Server> recent = editRecentCoreServers();
        int index = recent.indexOf(server);
        if(index >= 0) {
            recent.set(index, server);
            flush();
        }
    }

    public CoreAuthType getCoreAuthType() {
        if(useFlatCoinSettings()) {
            return coreAuthType;
        }
        ServerSettings settings = coinSettings();
        return settings == null ? null : settings.coreAuthType;
    }

    public void setCoreAuthType(CoreAuthType coreAuthType) {
        if(useFlatCoinSettings()) {
            this.coreAuthType = coreAuthType;
        } else {
            editCoinSettings().coreAuthType = coreAuthType;
        }
        flush();
    }

    public File getCoreDataDir() {
        if(useFlatCoinSettings()) {
            return coreDataDir;
        }
        ServerSettings settings = coinSettings();
        return settings == null ? null : settings.coreDataDir;
    }

    public void setCoreDataDir(File coreDataDir) {
        if(useFlatCoinSettings()) {
            this.coreDataDir = coreDataDir;
        } else {
            editCoinSettings().coreDataDir = coreDataDir;
        }
        flush();
    }

    public String getCoreAuth() {
        if(useFlatCoinSettings()) {
            return coreAuth;
        }
        ServerSettings settings = coinSettings();
        return settings == null ? null : settings.coreAuth;
    }

    public void setCoreAuth(String coreAuth) {
        if(useFlatCoinSettings()) {
            this.coreAuth = coreAuth;
        } else {
            editCoinSettings().coreAuth = coreAuth;
        }
        flush();
    }

    public boolean isUseLegacyCoreWallet() {
        if(useFlatCoinSettings()) {
            return useLegacyCoreWallet;
        }
        ServerSettings settings = coinSettings();
        return settings != null && settings.useLegacyCoreWallet;
    }

    public void setUseLegacyCoreWallet(boolean useLegacyCoreWallet) {
        if(useFlatCoinSettings()) {
            this.useLegacyCoreWallet = useLegacyCoreWallet;
        } else {
            editCoinSettings().useLegacyCoreWallet = useLegacyCoreWallet;
        }
        flush();
    }

    public boolean isLegacyServer() {
        if(useFlatCoinSettings()) {
            return legacyServer;
        }
        ServerSettings settings = coinSettings();
        return settings != null && settings.legacyServer;
    }

    public void setLegacyServer(boolean legacyServer) {
        if(useFlatCoinSettings()) {
            this.legacyServer = legacyServer;
        } else {
            editCoinSettings().legacyServer = legacyServer;
        }
        flush();
    }

    public Server getElectrumServer() {
        if(useFlatCoinSettings()) {
            return electrumServer;
        }
        ServerSettings settings = coinSettings();
        return settings == null ? null : settings.electrumServer;
    }

    public void setElectrumServer(Server electrumServer) {
        if(useFlatCoinSettings()) {
            this.electrumServer = electrumServer;
        } else {
            editCoinSettings().electrumServer = electrumServer;
        }
        flush();
    }

    public List<Server> getRecentElectrumServers() {
        List<Server> recent;
        if(useFlatCoinSettings()) {
            recent = recentElectrumServers;
        } else {
            ServerSettings settings = coinSettings();
            recent = settings == null ? null : settings.recentElectrumServers;
        }
        return recent == null ? new ArrayList<>() : recent;
    }

    private List<Server> editRecentElectrumServers() {
        if(useFlatCoinSettings()) {
            if(recentElectrumServers == null) {
                recentElectrumServers = new ArrayList<>();
            }
            return recentElectrumServers;
        }
        ServerSettings settings = editCoinSettings();
        if(settings.recentElectrumServers == null) {
            settings.recentElectrumServers = new ArrayList<>();
        }
        return settings.recentElectrumServers;
    }

    public boolean addRecentServer() {
        if(getServerType() == ServerType.BITCOIN_CORE && getCoreServer() != null) {
            return addRecentCoreServer(getCoreServer());
        } else if(getServerType() == ServerType.ELECTRUM_SERVER && getElectrumServer() != null) {
            return addRecentElectrumServer(getElectrumServer());
        }

        return false;
    }

    public boolean addRecentElectrumServer(Server electrumServer) {
        List<Server> recent = editRecentElectrumServers();
        int index = recent.indexOf(electrumServer);
        if(index < 0) {
            recent.removeIf(server -> server.getHost().equals(electrumServer.getHost()) && server.getAlias() == null);
            recent.add(electrumServer);
            flush();

            return true;
        }

        return false;
    }

    public void removeRecentElectrumServer(Server server) {
        List<Server> recent = editRecentElectrumServers();
        int index = recent.indexOf(server);
        if(index >= 0) {
            recent.remove(index);
            flush();
        }
    }

    public void setElectrumServerAlias(Server server) {
        List<Server> recent = editRecentElectrumServers();
        int index = recent.indexOf(server);
        if(index >= 0) {
            recent.set(index, server);
            flush();
        }
    }

    public File getElectrumServerCert() {
        if(useFlatCoinSettings()) {
            return electrumServerCert;
        }
        ServerSettings settings = coinSettings();
        return settings == null ? null : settings.electrumServerCert;
    }

    public void setElectrumServerCert(File electrumServerCert) {
        if(useFlatCoinSettings()) {
            this.electrumServerCert = electrumServerCert;
        } else {
            editCoinSettings().electrumServerCert = electrumServerCert;
        }
        flush();
    }

    public boolean isUseProxy() {
        return useProxy;
    }

    public void setUseProxy(boolean useProxy) {
        this.useProxy = useProxy;
        flush();
    }

    public String getProxyServer() {
        return proxyServer;
    }

    public void setProxyServer(String proxyServer) {
        this.proxyServer = proxyServer;
        flush();
    }

    public boolean isAutoSwitchProxy() {
        return autoSwitchProxy;
    }

    public void setAutoSwitchProxy(boolean autoSwitchProxy) {
        this.autoSwitchProxy = autoSwitchProxy;
        flush();
    }

    public int getMaxServerTimeout() {
        return maxServerTimeout;
    }

    public int getMaxPageSize() {
        return maxPageSize;
    }

    public boolean isUsePayNym() {
        return usePayNym;
    }

    public void setUsePayNym(boolean usePayNym) {
        this.usePayNym = usePayNym;
        flush();
    }

    public String getLinkedMwebWalletId(String publicWalletId) {
        return linkedMwebWallets == null ? null : linkedMwebWallets.get(publicWalletId);
    }

    public String getPublicWalletIdForMwebWalletId(String mwebWalletId) {
        if(linkedMwebWallets != null) {
            for(Map.Entry<String, String> entry : linkedMwebWallets.entrySet()) {
                if(entry.getValue().equals(mwebWalletId)) {
                    return entry.getKey();
                }
            }
        }
        return null;
    }

    public void setLinkedMwebWallet(String publicWalletId, String mwebWalletId) {
        if(linkedMwebWallets == null) {
            linkedMwebWallets = new HashMap<>();
        }
        //A public wallet links to exactly one MWEB wallet and vice versa - drop any prior pairing on either side
        linkedMwebWallets.values().removeIf(mwebWalletId::equals);
        linkedMwebWallets.remove(publicWalletId);
        if(mwebWalletId != null) {
            linkedMwebWallets.put(publicWalletId, mwebWalletId);
        }
        flush();
    }

    public void removeLinkedMwebWallet(String publicWalletId) {
        if(linkedMwebWallets != null && linkedMwebWallets.remove(publicWalletId) != null) {
            flush();
        }
    }

    public boolean isMempoolFullRbf() {
        return mempoolFullRbf;
    }

    public void setMempoolFullRbf(boolean mempoolFullRbf) {
        this.mempoolFullRbf = mempoolFullRbf;
        flush();
    }

    public double getMinRelayFeeRate() {
        return minRelayFeeRate;
    }

    public void setMinRelayFeeRate(double minRelayFeeRate) {
        this.minRelayFeeRate = minRelayFeeRate;
    }

    public Double getAppWidth() {
        return appWidth;
    }

    public void setAppWidth(Double appWidth) {
        this.appWidth = appWidth;
        flush();
    }

    public Double getAppHeight() {
        return appHeight;
    }

    public void setAppHeight(Double appHeight) {
        this.appHeight = appHeight;
        flush();
    }

    private synchronized void flush() {
        Gson gson = getGson();
        try {
            File configFile = getConfigFile();
            if(!configFile.exists()) {
                Storage.createOwnerOnlyFile(configFile);
            }

            Writer writer = new FileWriter(configFile);
            gson.toJson(this, writer);
            writer.flush();
            writer.close();
        } catch (IOException e) {
            //Ignore
        }
    }

    private static class FileSerializer implements JsonSerializer<File> {
        @Override
        public JsonElement serialize(File src, Type typeOfSrc, JsonSerializationContext context) {
            return new JsonPrimitive(src.getAbsolutePath());
        }
    }

    private static class FileDeserializer implements JsonDeserializer<File> {
        @Override
        public File deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            return new File(json.getAsJsonPrimitive().getAsString());
        }
    }

    private static class ServerSerializer implements JsonSerializer<Server> {
        @Override
        public JsonElement serialize(Server src, Type typeOfSrc, JsonSerializationContext context) {
            return new JsonPrimitive(src.toString());
        }
    }

    private static class ServerDeserializer implements JsonDeserializer<Server> {
        @Override
        public Server deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            return Server.fromString(json.getAsJsonPrimitive().getAsString());
        }
    }
}
