package com.sparrowwallet.sparrow.net.token;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/** A token index server: balances, transfer inscriptions and deploy details. Every answer is untrusted. */
public interface TokenClient {
    String host();

    List<TokenBalance> balances(String address) throws IOException;

    List<TransferInscription> transfers(String address, String tick) throws IOException;

    Optional<TokenInfo> token(String tick) throws IOException;

    default List<DuneBalance> dunesOnOutputs(List<String> outpoints) throws IOException {
        throw new UnsupportedOperationException("Dunes are not supported by " + host());
    }

    default Optional<String> duneEtchingTxid(String duneIdOrName) throws IOException {
        return Optional.empty();
    }
}
