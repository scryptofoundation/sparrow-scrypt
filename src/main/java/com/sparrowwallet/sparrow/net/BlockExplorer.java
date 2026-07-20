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
    LITECOINSPACE_ORG("https://litecoinspace.org"),
    //Blockchair's path segment is the coin name (litecoin, dogecoin), so the transaction URL is resolved
    //from the active coin at call time.
    BLOCKCHAIR_COM(null),
    NONE("http://none");

    private static final Logger log = LoggerFactory.getLogger(BlockExplorer.class);

    private final String url;

    BlockExplorer(String url) {
        this.url = url;
    }

    public Server getServer() {
        return new Server(getUrl());
    }

    public String getUrl() {
        if(this == BLOCKCHAIR_COM) {
            return "https://blockchair.com/" + Coin.get().getName() + "/transaction/{0}";
        }

        return url;
    }

    /**
     * The default explorer for the active coin. Litecoin uses litecoinspace.org (a native mempool.space
     * instance); other coins use Blockchair, which indexes them under their coin name.
     */
    public static Server getDefault() {
        return Coin.get() == Coin.LITECOIN ? LITECOINSPACE_ORG.getServer() : BLOCKCHAIR_COM.getServer();
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
