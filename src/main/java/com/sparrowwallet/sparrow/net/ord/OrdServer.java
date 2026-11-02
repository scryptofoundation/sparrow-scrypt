package com.sparrowwallet.sparrow.net.ord;

import com.sparrowwallet.drongo.Coin;
import com.sparrowwallet.drongo.Network;

import java.net.URI;
import java.util.Locale;
import java.util.Map;

public final class OrdServer {
    public static final String PUBLIC_MAINNET_URL = "https://ordinalslite.com";
    private static final String NINTONDO_DOMAIN = ".nintondo.io";

    //Public indexers: ord-litecoin at ordinalslite.com (mainnet only), and Nintondo's for Bellscoin, Dogecoin and
    //Pepecoin, which follow inscriptions as they move (doge testnet answered with 502s, so it is left out). Junkcoin and
    //Luckycoin have none, and no Dingocoin coin carries an NFT; those coins offer Off and Custom.
    private static final Map<Coin, Map<Network, String>> PUBLIC_URLS = Map.of(
            Coin.LITECOIN, Map.of(Network.MAINNET, PUBLIC_MAINNET_URL),
            Coin.BELLSCOIN, Map.of(Network.MAINNET, "https://bells-mainnet-api.nintondo.io", Network.TESTNET, "https://bells-testnet-api.nintondo.io"),
            Coin.DOGECOIN, Map.of(Network.MAINNET, "https://doge-mainnet-api.nintondo.io"),
            Coin.PEPECOIN, Map.of(Network.MAINNET, "https://pepe-mainnet-api.nintondo.io", Network.TESTNET, "https://pepe-testnet-api.nintondo.io"));

    private OrdServer() {
    }

    /** The public inscriptions server for a coin and network, or null when there is none. */
    public static String publicUrl(Coin coin, Network network) {
        return PUBLIC_URLS.getOrDefault(coin, Map.of()).get(network);
    }

    /** True for Nintondo's indexer, which answers find_meta instead of ord's API. */
    public static boolean isNintondo(String url) {
        String host = URI.create(url).getHost();
        return host != null && host.toLowerCase(Locale.ROOT).endsWith(NINTONDO_DOMAIN);
    }

    /** The server URL for a setting of the current coin. */
    public static String resolveUrl(InscriptionServerMode mode, String customUrl, Network network) {
        return resolveUrl(mode, customUrl, Coin.get(), network);
    }

    /** The server URL for a setting, without a trailing slash, or null when checks are off or no server fits. */
    public static String resolveUrl(InscriptionServerMode mode, String customUrl, Coin coin, Network network) {
        if(mode == null || mode == InscriptionServerMode.OFF) {
            return null;
        }
        if(mode == InscriptionServerMode.PUBLIC) {
            return publicUrl(coin, network);
        }

        String url = customUrl == null ? "" : customUrl.trim();
        while(url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        URI uri;
        try {
            uri = URI.create(url);
        } catch(IllegalArgumentException e) {
            throw new IllegalArgumentException("Not a valid server URL: " + customUrl);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if(!(scheme.equals("http") || scheme.equals("https")) || uri.getHost() == null) {
            throw new IllegalArgumentException("The inscriptions server must be an http or https URL");
        }
        return url;
    }

    /** The URL to store for Custom: the typed URL if usable, otherwise blank, which leaves checks off. */
    public static String customUrlToSave(String typed, Network network) {
        try {
            String url = resolveUrl(InscriptionServerMode.CUSTOM, typed, network);
            return url == null ? "" : url;
        } catch(IllegalArgumentException e) {
            return "";
        }
    }

    public static boolean isOnion(String url) {
        String host = URI.create(url).getHost();
        return host != null && host.toLowerCase(Locale.ROOT).endsWith(".onion");
    }
}
