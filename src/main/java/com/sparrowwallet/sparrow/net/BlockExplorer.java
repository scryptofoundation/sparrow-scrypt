package com.sparrowwallet.sparrow.net;

import com.sparrowwallet.drongo.Coin;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.io.Server;
import org.girod.javafx.svgimage.SVGImage;
import org.girod.javafx.svgimage.SVGLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URL;
import java.util.Locale;

public enum BlockExplorer {
    LITECOINSPACE_ORG("https://litecoinspace.org", Coin.LITECOIN),
    //Blockchair's path segment is the coin name (litecoin, dogecoin), so the transaction URL is resolved
    //from the active coin at call time. Of the Scrypto coins it indexes Litecoin and Dogecoin only.
    BLOCKCHAIR_COM(null, null),
    //Blockchair indexes none of the chains below, so each opens its own explorer. Every URL here was
    //verified to serve a transaction page at <url>/tx/<txid>. Nintondo's is the Bellscoin explorer behind
    //api.nintondo.io, the same instance PublicElectrumServer uses as that chain's REST backend.
    PEPPOOL_SPACE("https://peppool.space", Coin.PEPECOIN),
    LUCKYSCAN_ORG("https://luckyscan.org", Coin.LUCKYCOIN),
    JUNK_COIN_COM("https://explorer.junk-coin.com", Coin.JUNKCOIN),
    NINTONDO_IO("https://nintondo.io/bells/mainnet/explorer", Coin.BELLSCOIN),
    //Dingocoin's explorer is Iquidus rather than Esplora, which is why probing it for Esplora routes made
    //it look dead. The chain is live (tip past 2.68M) and this front-end serves transactions normally.
    DINGOCOIN_COM("https://explorer.dingocoin.com", Coin.DINGOCOIN),
    NONE("http://none", null);

    private static final Logger log = LoggerFactory.getLogger(BlockExplorer.class);

    private static final String BLOCKCHAIR_URL = "https://blockchair.com/";

    private final String url;
    //The only coin this explorer indexes, or null if it serves whichever coin is active
    private final Coin coin;

    BlockExplorer(String url, Coin coin) {
        this.url = url;
        this.coin = coin;
    }

    public Server getServer() {
        return new Server(getUrl());
    }

    public String getUrl() {
        if(this == BLOCKCHAIR_COM) {
            return BLOCKCHAIR_URL + Coin.get().getName() + "/transaction/{0}";
        }

        return url;
    }

    public boolean supportsCoin(Coin coin) {
        return this.coin == null || this.coin == coin;
    }

    /**
     * The default explorer for the active coin. Coins with a native explorer use it (Litecoin uses
     * litecoinspace.org, a native mempool.space instance); the rest use Blockchair, which indexes them
     * under their coin name.
     */
    public static Server getDefault() {
        for(BlockExplorer blockExplorer : values()) {
            if(blockExplorer.coin == Coin.get()) {
                return blockExplorer.getServer();
            }
        }

        return BLOCKCHAIR_COM.getServer();
    }

    /**
     * The explorer to open for the active coin, given the configured selection. The selection is stored as
     * a plain URL, so it can outlive the coin it was chosen under - a config migrated from Sparrow-LTC
     * carries litecoinspace.org, and a stored Blockchair URL has the coin frozen into its path. A known
     * explorer that does not index the active coin falls back to that coin's default, and a Blockchair URL
     * is re-resolved, so a transaction is never looked up on another chain's explorer. A custom URL is
     * opened as entered - there is no way to tell which coin it serves.
     */
    public static Server getConfigured(Server configured) {
        if(configured == null) {
            return getDefault();
        }

        BlockExplorer blockExplorer = fromServer(configured);
        if(blockExplorer == null) {
            return configured;
        }

        return blockExplorer.supportsCoin(Coin.get()) ? blockExplorer.getServer() : getDefault();
    }

    /**
     * The explorer this stored server was chosen from, or null if it is a custom URL.
     */
    public static BlockExplorer fromServer(Server server) {
        for(BlockExplorer blockExplorer : values()) {
            if(blockExplorer.matches(server)) {
                return blockExplorer;
            }
        }

        return null;
    }

    private boolean matches(Server server) {
        //A stored Blockchair URL contains the coin it was selected under, so match on the host alone
        return this == BLOCKCHAIR_COM ? server.getUrl().startsWith(BLOCKCHAIR_URL) : server.getUrl().equals(url);
    }

    public static SVGImage getSVGImage(Server server) {
        try {
            URL url = AppServices.class.getResource("/image/blockexplorer/" + server.getHost().toLowerCase(Locale.ROOT) + "-icon.svg");
            if(url != null) {
                return SVGLoader.load(url);
            }
        } catch(Exception e) {
            log.error("Could not load block explorer image for " + server.getHost());
        }

        return null;
    }
}
