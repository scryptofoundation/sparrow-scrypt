package com.sparrowwallet.sparrow.wallet.collectibles;

import com.sparrowwallet.drongo.ordinals.track.Found;
import com.sparrowwallet.drongo.ordinals.track.Verdict;
import com.sparrowwallet.drongo.protocol.Sha256Hash;
import com.sparrowwallet.drongo.wallet.InscriptionCheck;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

public class InscriptionColumnTest {
    private static final Sha256Hash A = Sha256Hash.wrap(String.format("%064x", 1));

    private static InscriptionCheck check(Verdict verdict, InscriptionCheck.Source source, String reason, List<Found> found) {
        return new InscriptionCheck(A, 0, verdict, source, false, found, reason, A, List.of());
    }

    @Test
    public void text() {
        Assertions.assertEquals("Clean", CollectiblesManager.columnText(check(Verdict.CLEAN, InscriptionCheck.Source.PUBLIC, null, List.of())));
        Assertions.assertEquals("Inscribed", CollectiblesManager.columnText(check(Verdict.INSCRIBED, InscriptionCheck.Source.BUILT_IN, null,
                List.of(new Found(A + "i0", 0, null, A, 1, 1)))));
        Assertions.assertEquals("Unchecked", CollectiblesManager.columnText(check(Verdict.UNCHECKED, InscriptionCheck.Source.BUILT_IN, "x", List.of())));
        Assertions.assertEquals("", CollectiblesManager.columnText(null));
        InscriptionCheck runes = new InscriptionCheck(A, 0, Verdict.CLEAN, InscriptionCheck.Source.PUBLIC, true, List.of(), null, A, List.of());
        Assertions.assertEquals("Inscribed", CollectiblesManager.columnText(runes));
    }

    @Test
    public void tooltip() {
        Assertions.assertEquals("Lookup budget used up; use Check deeper",
                CollectiblesManager.columnTooltip(check(Verdict.UNCHECKED, InscriptionCheck.Source.BUILT_IN, "Lookup budget used up; use Check deeper", List.of())));
        Assertions.assertEquals("Checked by Public indexer", CollectiblesManager.columnTooltip(check(Verdict.CLEAN, InscriptionCheck.Source.PUBLIC, null, List.of())));
        Assertions.assertEquals("Checked by Your own indexer", CollectiblesManager.columnTooltip(check(Verdict.CLEAN, InscriptionCheck.Source.YOUR_OWN, null, List.of())));
        Assertions.assertEquals("Checked on-chain", CollectiblesManager.columnTooltip(check(Verdict.CLEAN, InscriptionCheck.Source.BUILT_IN, null, List.of())));
        Assertions.assertEquals("Checked from this wallet's own transactions", CollectiblesManager.columnTooltip(check(Verdict.CLEAN, InscriptionCheck.Source.OWN, null, List.of())));
        Assertions.assertEquals("Checked on-chain: " + A + "i0", CollectiblesManager.columnTooltip(check(Verdict.INSCRIBED, InscriptionCheck.Source.BUILT_IN, null,
                List.of(new Found(A + "i0", 0, null, A, 1, 1)))));
        Assertions.assertEquals("Not checked yet", CollectiblesManager.columnTooltip(null));
    }
}
