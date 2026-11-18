package com.sparrowwallet.sparrow.io;

import com.sparrowwallet.drongo.ordinals.track.Found;
import com.sparrowwallet.drongo.ordinals.track.Piece;
import com.sparrowwallet.drongo.ordinals.track.UnitRange;
import com.sparrowwallet.drongo.ordinals.track.Verdict;
import com.sparrowwallet.drongo.protocol.Sha256Hash;
import com.sparrowwallet.drongo.wallet.InscriptionCheck;
import com.sparrowwallet.drongo.wallet.MnemonicException;
import com.sparrowwallet.drongo.wallet.Wallet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.util.List;

public class InscriptionCheckPersistenceTest extends IoTest {
    private static final Sha256Hash A = Sha256Hash.wrap("52b559ee298a9e57b1b6e743a53e68c9d9a7e75da6b090cfdd8e2bb260f3636e");
    private static final Sha256Hash B = Sha256Hash.wrap("0000000000000000000000000000000000000000000000000000000000000b00");

    private static InscriptionCheck inscribed() {
        return new InscriptionCheck(A, 0, Verdict.INSCRIBED, InscriptionCheck.Source.PUBLIC, true,
                List.of(new Found(A + "i0", 0, 42L, A, 2_500_000, 1_700_000_000L)), null, B, List.of());
    }

    private static InscriptionCheck unchecked() {
        return new InscriptionCheck(B, 2, Verdict.UNCHECKED, InscriptionCheck.Source.BUILT_IN, false, List.of(),
                "Lookup budget used up; use Check deeper", null, List.of(new Piece(A, Piece.FEE, new UnitRange(10, 20), 30)));
    }

    private Storage opened() throws IOException, StorageException, MnemonicException {
        System.setProperty(Wallet.ALLOW_DERIVATIONS_MATCHING_OTHER_NETWORKS_PROPERTY, "true");
        Storage storage = new Storage(getFile("sparrow-single-seed-wallet"));
        Wallet wallet = storage.loadEncryptedWallet("pass").getWallet();
        File tempWallet = File.createTempFile("sparrow", "tmp");
        tempWallet.deleteOnExit();
        Storage saved = new Storage(tempWallet);
        saved.setKeyDeriver(storage.getKeyDeriver());
        saved.setEncryptionPubKey(storage.getEncryptionPubKey());
        saved.saveWallet(wallet);
        return new Storage(saved.getWalletFile());
    }

    @Test
    public void checksRoundTrip() throws Exception {
        Storage open = opened();
        Wallet wallet = open.loadEncryptedWallet("pass").getWallet();
        wallet.getInscriptionChecks().addAll(List.of(inscribed(), unchecked()));
        open.saveInscriptionChecks(wallet);
        open.closeAndWait();

        List<InscriptionCheck> loaded = new Storage(open.getWalletFile()).loadEncryptedWallet("pass").getWallet().getInscriptionChecks();
        Assertions.assertEquals(2, loaded.size());
        Assertions.assertEquals(inscribed(), loaded.stream().filter(c -> c.verdict() == Verdict.INSCRIBED).findFirst().orElseThrow());
        Assertions.assertEquals(unchecked(), loaded.stream().filter(c -> c.verdict() == Verdict.UNCHECKED).findFirst().orElseThrow());
    }

    @Test
    public void replaceDropsRemovedChecks() throws Exception {
        Storage open = opened();
        Wallet wallet = open.loadEncryptedWallet("pass").getWallet();
        wallet.getInscriptionChecks().addAll(List.of(inscribed(), unchecked()));
        open.saveInscriptionChecks(wallet);
        wallet.getInscriptionChecks().removeIf(c -> c.verdict() == Verdict.UNCHECKED);
        open.saveInscriptionChecks(wallet);
        open.closeAndWait();

        List<InscriptionCheck> loaded = new Storage(open.getWalletFile()).loadEncryptedWallet("pass").getWallet().getInscriptionChecks();
        Assertions.assertEquals(List.of(inscribed()), loaded);
    }

    @AfterEach
    void tearDown() {
        System.setProperty(Wallet.ALLOW_DERIVATIONS_MATCHING_OTHER_NETWORKS_PROPERTY, "false");
    }
}
