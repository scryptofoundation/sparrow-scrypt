package com.sparrowwallet.sparrow.wallet.collectibles.track;

import com.sparrowwallet.drongo.ordinals.track.ChainSource;
import com.sparrowwallet.drongo.ordinals.track.ChainTx;
import com.sparrowwallet.drongo.ordinals.track.DoginalsStep;
import com.sparrowwallet.drongo.ordinals.track.InscriptionLocator;
import com.sparrowwallet.drongo.protocol.Sha256Hash;

import java.io.IOException;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Checks an indexer's inscription id against the chain: the transaction it names must create it. */
public final class RevealCheck {
    private static final Pattern ID = Pattern.compile("([0-9a-f]{64})i(\\d{1,10})");

    private RevealCheck() {
    }

    /**
     * The transaction that creates the inscription, or null when the chain shows no such inscription: an ord id names a
     * reveal with at least that many envelopes; a Doginals id (index 0) names a chain's first step, whose scriptSig starts
     * with "ord".
     */
    public static ChainTx reveal(String inscriptionId, ChainSource source) throws IOException {
        Matcher matcher = ID.matcher(inscriptionId);
        if(!matcher.matches()) {
            return null;
        }
        //A transaction the server cannot return throws: an outage and an unknown txid look the same, and both leave the coin held
        ChainTx tx = source.transaction(Sha256Hash.wrap(matcher.group(1)));
        if(InscriptionLocator.ordIds(tx.tx()).contains(inscriptionId)) {
            return tx;
        }
        Optional<DoginalsStep> step = InscriptionLocator.doginals(tx.tx());
        if(step.isPresent() && step.get().genesis() && matcher.group(2).equals("0")) {
            return tx;
        }
        return null;
    }
}
