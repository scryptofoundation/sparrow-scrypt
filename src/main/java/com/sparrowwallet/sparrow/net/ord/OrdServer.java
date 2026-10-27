package com.sparrowwallet.sparrow.net.ord;

import com.sparrowwallet.drongo.Network;

import java.net.URI;
import java.util.Locale;

public final class OrdServer {
    public static final String PUBLIC_MAINNET_URL = "https://ordinalslite.com";

    private OrdServer() {
    }

    /** The server URL for a setting, without a trailing slash, or null when checks are off or no server fits. */
    public static String resolveUrl(InscriptionServerMode mode, String customUrl, Network network) {
        if(mode == null || mode == InscriptionServerMode.OFF) {
            return null;
        }
        if(mode == InscriptionServerMode.PUBLIC) {
            //The public server indexes Litecoin mainnet only
            return network == Network.MAINNET ? PUBLIC_MAINNET_URL : null;
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
