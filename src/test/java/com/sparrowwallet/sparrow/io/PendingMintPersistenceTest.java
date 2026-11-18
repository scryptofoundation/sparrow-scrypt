package com.sparrowwallet.sparrow.io;

import com.sparrowwallet.drongo.ordinals.RuneName;
import com.sparrowwallet.drongo.ordinals.Runestone;
import com.sparrowwallet.drongo.protocol.Sha256Hash;
import com.sparrowwallet.drongo.wallet.MnemonicException;
import com.sparrowwallet.drongo.wallet.PendingMint;
import com.sparrowwallet.drongo.wallet.Wallet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.util.List;

public class PendingMintPersistenceTest extends IoTest {
    private static final Sha256Hash COMMIT = Sha256Hash.wrap("52b559ee298a9e57b1b6e743a53e68c9d9a7e75da6b090cfdd8e2bb260f3636e");

    private static PendingMint inscription() {
        return new PendingMint(PendingMint.Kind.INSCRIPTION, "m/0/3", Sha256Hash.hash(new byte[] {1, 2, 3}), COMMIT, 0, 10_330L, "image/png", null);
    }

    private static PendingMint etch() {
        Runestone.Terms terms = new Runestone.Terms(BigInteger.valueOf(100), BigInteger.TEN, 200L, null, null, 50L);
        Runestone.Etching etching = new Runestone.Etching(RuneName.parse("SPARROW•SCRYPT•TEST"), 2, (int)'S', BigInteger.valueOf(500_000), terms, true);
        return new PendingMint(PendingMint.Kind.ETCH, "m/0/4", Sha256Hash.hash(new byte[] {4, 5}), COMMIT, 1, 20_406L, "text/plain;charset=utf-8", etching);
    }

    private Storage save(Storage from, Wallet wallet) throws IOException, StorageException {
        File tempWallet = File.createTempFile("sparrow", "tmp");
        tempWallet.deleteOnExit();
        Storage tempStorage = new Storage(tempWallet);
        tempStorage.setKeyDeriver(from.getKeyDeriver());
        tempStorage.setEncryptionPubKey(from.getEncryptionPubKey());
        tempStorage.saveWallet(wallet);
        return tempStorage;
    }

    @Test
    public void pendingMintRoundTrips() throws IOException, MnemonicException, StorageException {
        System.setProperty(Wallet.ALLOW_DERIVATIONS_MATCHING_OTHER_NETWORKS_PROPERTY, "true");
        Storage storage = new Storage(getFile("sparrow-single-seed-wallet"));
        Wallet wallet = storage.loadEncryptedWallet("pass").getWallet();
        wallet.getPendingMints().addAll(List.of(inscription(), etch()));

        Storage saved = save(storage, wallet);
        List<PendingMint> loaded = new Storage(saved.getWalletFile()).loadEncryptedWallet("pass").getWallet().getPendingMints();
        Assertions.assertEquals(2, loaded.size());
        PendingMint first = loaded.stream().filter(m -> m.getKind() == PendingMint.Kind.INSCRIPTION).findFirst().orElseThrow();
        Assertions.assertEquals("m/0/3", first.getKeyPath());
        Assertions.assertArrayEquals(inscription().getScriptHash(), first.getScriptHash());
        Assertions.assertEquals(COMMIT, first.getCommitTxid());
        Assertions.assertEquals(0, first.getCommitVout());
        Assertions.assertEquals(10_330L, first.getCommitValue());
        Assertions.assertEquals("image/png", first.getContentType());
        Assertions.assertNull(first.getEtching());
        Assertions.assertNotNull(first.getId());

        PendingMint second = loaded.stream().filter(m -> m.getKind() == PendingMint.Kind.ETCH).findFirst().orElseThrow();
        Assertions.assertEquals(etch().getEtching(), second.getEtching());
        Assertions.assertEquals(1, second.getCommitVout());
    }

    @Test
    public void removedPendingMintIsGoneAfterReload() throws IOException, MnemonicException, StorageException {
        System.setProperty(Wallet.ALLOW_DERIVATIONS_MATCHING_OTHER_NETWORKS_PROPERTY, "true");
        Storage storage = new Storage(getFile("sparrow-single-seed-wallet"));
        Wallet wallet = storage.loadEncryptedWallet("pass").getWallet();
        wallet.getPendingMints().addAll(List.of(inscription(), etch()));
        Storage saved = save(storage, wallet);

        Wallet reloaded = new Storage(saved.getWalletFile()).loadEncryptedWallet("pass").getWallet();
        reloaded.getPendingMints().removeIf(m -> m.getKind() == PendingMint.Kind.INSCRIPTION);
        Assertions.assertEquals(1, reloaded.copy().getPendingMints().size());
        Storage savedAgain = save(saved, reloaded);
        List<PendingMint> loaded = new Storage(savedAgain.getWalletFile()).loadEncryptedWallet("pass").getWallet().getPendingMints();
        Assertions.assertEquals(1, loaded.size());
        Assertions.assertEquals(PendingMint.Kind.ETCH, loaded.getFirst().getKind());
    }

    @Test
    public void pendingMintIsOnDiskWhenSaveReturns() throws IOException, MnemonicException, StorageException {
        //A mint's commit is broadcast only once its record is written, so savePendingMints waits for the write
        System.setProperty(Wallet.ALLOW_DERIVATIONS_MATCHING_OTHER_NETWORKS_PROPERTY, "true");
        Storage storage = new Storage(getFile("sparrow-single-seed-wallet"));
        Wallet wallet = storage.loadEncryptedWallet("pass").getWallet();
        Storage saved = save(storage, wallet);

        //Loading moves the saved file to the database format, as the app does
        Storage open = new Storage(saved.getWalletFile());
        Wallet opened = open.loadEncryptedWallet("pass").getWallet();
        opened.getPendingMints().add(inscription());
        open.savePendingMints(opened);
        Assertions.assertNotNull(opened.getPendingMints().getFirst().getId());

        //The file is locked while its storage is open, so reopen it as the app would after closing the wallet
        open.closeAndWait();
        List<PendingMint> loaded = new Storage(open.getWalletFile()).loadEncryptedWallet("pass").getWallet().getPendingMints();
        Assertions.assertEquals(1, loaded.size());
        Assertions.assertEquals(COMMIT, loaded.getFirst().getCommitTxid());
    }

    @Test
    public void pendingMintIsOnDiskWhenSaveReturnsUnencrypted() throws IOException, MnemonicException, StorageException {
        System.setProperty(Wallet.ALLOW_DERIVATIONS_MATCHING_OTHER_NETWORKS_PROPERTY, "true");
        Storage storage = new Storage(getFile("sparrow-single-seed-wallet"));
        Wallet wallet = storage.loadEncryptedWallet("pass").getWallet();
        wallet.decrypt("pass");
        //Moving to the database format names the file after the wallet; keep it apart from the encrypted tests' file
        wallet.setName("unencrypted" + System.nanoTime());
        File tempWallet = File.createTempFile("sparrow", "tmp");
        tempWallet.deleteOnExit();
        Storage saved = new Storage(tempWallet);
        saved.setEncryptionPubKey(Storage.NO_PASSWORD_KEY);
        saved.saveWallet(wallet);

        Storage open = new Storage(saved.getWalletFile());
        Wallet opened = open.loadUnencryptedWallet().getWallet();
        opened.getPendingMints().add(inscription());
        open.savePendingMints(opened);

        List<PendingMint> loaded = new Storage(open.getWalletFile()).loadUnencryptedWallet().getWallet().getPendingMints();
        Assertions.assertEquals(1, loaded.size());
        Assertions.assertEquals(COMMIT, loaded.getFirst().getCommitTxid());
    }

    private static final byte[] DOGINAL_CONTENT = "such inscription, very wow".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    private static PendingMint doginal() {
        return PendingMint.doginal("m/44'/3'/0'/0/5", Sha256Hash.hash(new byte[] {6}), COMMIT, 4_321_000L, "text/plain", DOGINAL_CONTENT, 1000.5,
                "DH5yaieqoZN36fDVciNyRueRGvGLR3mr7L").withStepsBroadcast(9);
    }

    private static void assertDoginal(PendingMint loaded) {
        Assertions.assertEquals(PendingMint.Kind.DOGINAL, loaded.getKind());
        Assertions.assertEquals("m/44'/3'/0'/0/5", loaded.getKeyPath());
        Assertions.assertEquals(COMMIT, loaded.getCommitTxid());
        Assertions.assertEquals(4_321_000L, loaded.getCommitValue());
        Assertions.assertArrayEquals(DOGINAL_CONTENT, loaded.getContent());
        Assertions.assertEquals(1000.5, loaded.getFeeRate());
        Assertions.assertEquals("DH5yaieqoZN36fDVciNyRueRGvGLR3mr7L", loaded.getDestination());
        Assertions.assertEquals(9, loaded.getStepsBroadcast());
    }

    @Test
    public void doginalPendingMintRoundTrips() throws IOException, MnemonicException, StorageException {
        System.setProperty(Wallet.ALLOW_DERIVATIONS_MATCHING_OTHER_NETWORKS_PROPERTY, "true");
        Storage storage = new Storage(getFile("sparrow-single-seed-wallet"));
        Wallet wallet = storage.loadEncryptedWallet("pass").getWallet();
        wallet.getPendingMints().addAll(List.of(inscription(), doginal()));

        //Through the file the first save writes
        Storage saved = save(storage, wallet);
        Storage open = new Storage(saved.getWalletFile());
        Wallet opened = open.loadEncryptedWallet("pass").getWallet();
        assertDoginal(opened.getPendingMints().stream().filter(m -> m.getKind() == PendingMint.Kind.DOGINAL).findFirst().orElseThrow());
        Assertions.assertNull(opened.getPendingMints().stream().filter(m -> m.getKind() == PendingMint.Kind.INSCRIPTION).findFirst().orElseThrow().getContent());

        //And through the database the app writes after loading
        opened.getPendingMints().replaceAll(m -> m.getKind() == PendingMint.Kind.DOGINAL ? m.withStepsBroadcast(9) : m);
        open.savePendingMints(opened);
        open.closeAndWait();
        List<PendingMint> loaded = new Storage(open.getWalletFile()).loadEncryptedWallet("pass").getWallet().getPendingMints();
        Assertions.assertEquals(2, loaded.size());
        assertDoginal(loaded.stream().filter(m -> m.getKind() == PendingMint.Kind.DOGINAL).findFirst().orElseThrow());
        PendingMint inscription = loaded.stream().filter(m -> m.getKind() == PendingMint.Kind.INSCRIPTION).findFirst().orElseThrow();
        Assertions.assertNull(inscription.getContent());
        Assertions.assertNull(inscription.getDestination());
        Assertions.assertEquals(0, inscription.getStepsBroadcast());
    }

    @AfterEach
    void tearDown() {
        System.setProperty(Wallet.ALLOW_DERIVATIONS_MATCHING_OTHER_NETWORKS_PROPERTY, "false");
    }
}
