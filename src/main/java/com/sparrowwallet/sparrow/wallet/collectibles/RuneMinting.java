package com.sparrowwallet.sparrow.wallet.collectibles;

import com.sparrowwallet.drongo.ordinals.InscriptionMint;
import com.sparrowwallet.drongo.ordinals.RuneName;
import com.sparrowwallet.sparrow.net.ord.OrdRune;

import java.math.BigInteger;
import java.util.Optional;

/** The rules for minting and etching runes that the dialogs check before anything is built, kept apart so they can be tested. */
public final class RuneMinting {
    /** Names from this value (AAAAAAAAAAAAAAAAAAAAAAAAAAA) up are reserved for runes etched without a name. */
    static final BigInteger RESERVED = new BigInteger("6402364363415443603228541259936211926");

    public static final String FRONT_RUNNING_WARNING = "Until the reveal confirms, someone else can etch the same name first (for example "
            + "someone who committed to it earlier), and then this etching is lost. The reveal can be signed once the commit is "
            + InscriptionMint.COMMIT_CONFIRMATIONS + " blocks deep; the name is yours only when the reveal is in a block.";

    private RuneMinting() {}

    /** Why the rune cannot be minted now, if it cannot. */
    public static Optional<String> mintProblem(OrdRune rune) {
        if(rune.mintable()) {
            return Optional.empty();
        }
        String reason;
        if(rune.terms() == null) {
            reason = "it was etched without open mint terms";
        } else if(rune.remainingMints().signum() == 0) {
            reason = "all " + rune.terms().cap() + " mints are taken";
        } else {
            reason = "its mint window is not open at this height";
        }
        return Optional.of(rune.spacedName() + " cannot be minted: " + reason + ".");
    }

    /** Why this name cannot be etched, given the ord server's minimum for the next block and whether it exists already. */
    public static Optional<String> etchProblem(RuneName name, RuneName minimum, boolean alreadyEtched) {
        if(name.value().compareTo(RESERVED) >= 0) {
            return Optional.of("Names from AAAAAAAAAAAAAAAAAAAAAAAAAAA up are reserved for runes etched without a name. Choose a name of at most 26 letters.");
        }
        if(minimum != null && name.value().compareTo(minimum.value()) < 0) {
            return Optional.of("Names this short cannot be etched yet. The next block accepts names from " + minimum.letters()
                    + " up (by length, then alphabetically); a longer name always works.");
        }
        if(alreadyEtched) {
            return Optional.of(name + " is already etched. Choose another name.");
        }
        return Optional.empty();
    }
}
