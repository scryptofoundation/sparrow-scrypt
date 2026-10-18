package com.sparrowwallet.sparrow.net.cormorant.bitcoind;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * A single entry in an {@code importmulti} request, the pre-descriptor way to make a node watch an address.
 *
 * The Scrypto chains other than Litecoin and Bellscoin run Bitcoin 0.13/0.14-era wallets, which have no
 * {@code importdescriptors}. Their {@code importmulti} predates descriptor support too (that arrived in
 * Core 0.18), so each entry carries a concrete scriptPubKey rather than a ranged descriptor - the caller
 * expands the wallet's key range to addresses first.
 *
 * Null fields are omitted: these nodes validate the request object strictly and reject unknown or null keys.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ImportMultiRequest {
    private final Map<String, String> scriptPubKey;
    private final Object timestamp;
    private final Boolean watchonly;

    /**
     * @param address the address to watch
     * @param timestamp the UNIX time to rescan from, or the string "now" to skip rescanning
     */
    public ImportMultiRequest(String address, Object timestamp) {
        this.scriptPubKey = Map.of("address", address);
        this.timestamp = timestamp;
        this.watchonly = Boolean.TRUE;
    }

    public Map<String, String> getScriptPubKey() {
        return scriptPubKey;
    }

    public Object getTimestamp() {
        return timestamp;
    }

    public Boolean getWatchonly() {
        return watchonly;
    }
}
