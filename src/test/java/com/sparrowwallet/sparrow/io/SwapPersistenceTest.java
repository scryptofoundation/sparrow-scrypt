package com.sparrowwallet.sparrow.io;

import com.sparrowwallet.drongo.protocol.Sha256Hash;
import com.sparrowwallet.drongo.wallet.MakerHistoryEntry;
import com.sparrowwallet.drongo.wallet.MnemonicException;
import com.sparrowwallet.drongo.wallet.PendingMint;
import com.sparrowwallet.drongo.wallet.SwapEntry;
import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.sparrow.wallet.SettingsWalletForm;
import com.sparrowwallet.sparrow.wallet.WalletForm;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Random;

/**
 * A wallet file written by a build with the swap tab holds swap rows (V14) sealed to the file's key. This build never
 * opens them, but carries them through every save unchanged, and refuses a password change that would leave them sealed
 * to the old key.
 */
public class SwapPersistenceTest extends IoTest {
    private static final Sha256Hash COMMIT = Sha256Hash.wrap("52b559ee298a9e57b1b6e743a53e68c9d9a7e75da6b090cfdd8e2bb260f3636e");
    private static final String MAKER_A = "a".repeat(64);
    private static final String MAKER_B = "0123456789abcdef".repeat(4);

    @BeforeEach
    void setUp() {
        System.setProperty(Wallet.ALLOW_DERIVATIONS_MATCHING_OTHER_NETWORKS_PROPERTY, "true");
    }

    @AfterEach
    void tearDown() {
        System.setProperty(Wallet.ALLOW_DERIVATIONS_MATCHING_OTHER_NETWORKS_PROPERTY, "false");
    }

    private static SwapEntry entry(int keyIndex, String status) {
        byte[] sealed = new byte[200 + keyIndex];
        new Random(keyIndex).nextBytes(sealed);
        return new SwapEntry(String.format("%032x", 0xabc000L + keyIndex), keyIndex, status, sealed, 1_760_000_000L + keyIndex);
    }

    private static PendingMint inscription() {
        return new PendingMint(PendingMint.Kind.INSCRIPTION, "m/0/3", Sha256Hash.hash(new byte[] {1, 2, 3}), COMMIT, 0, 10_330L, "image/png", null);
    }

    /** Every column of the row: id, swapId, keyIndex, status, sealed and updatedSeconds. */
    private static void assertSameRow(SwapEntry expected, SwapEntry actual) {
        Assertions.assertEquals(expected.getId(), actual.getId());
        Assertions.assertEquals(expected.getSwapId(), actual.getSwapId());
        Assertions.assertEquals(expected.getKeyIndex(), actual.getKeyIndex());
        Assertions.assertEquals(expected.getStatus(), actual.getStatus());
        Assertions.assertArrayEquals(expected.getSealed(), actual.getSealed());
        Assertions.assertEquals(expected.getUpdatedSeconds(), actual.getUpdatedSeconds());
    }

    /** Every column of the row: id, makerPubKey, completed, failed and noShow. */
    private static void assertSameRow(MakerHistoryEntry expected, MakerHistoryEntry actual) {
        Assertions.assertEquals(expected.getId(), actual.getId());
        Assertions.assertEquals(expected.getMakerPubKey(), actual.getMakerPubKey());
        Assertions.assertEquals(expected.getCompleted(), actual.getCompleted());
        Assertions.assertEquals(expected.getFailed(), actual.getFailed());
        Assertions.assertEquals(expected.getNoShow(), actual.getNoShow());
    }

    private static void assertSameRows(Wallet expected, Wallet actual) {
        List<SwapEntry> expectedEntries = expected.getSwapEntries().stream().sorted(Comparator.comparingLong(SwapEntry::getId)).toList();
        List<SwapEntry> actualEntries = actual.getSwapEntries().stream().sorted(Comparator.comparingLong(SwapEntry::getId)).toList();
        Assertions.assertEquals(expectedEntries.size(), actualEntries.size());
        for(int i = 0; i < expectedEntries.size(); i++) {
            assertSameRow(expectedEntries.get(i), actualEntries.get(i));
        }

        List<MakerHistoryEntry> expectedMakers = expected.getMakerHistory().stream().sorted(Comparator.comparingLong(MakerHistoryEntry::getId)).toList();
        List<MakerHistoryEntry> actualMakers = actual.getMakerHistory().stream().sorted(Comparator.comparingLong(MakerHistoryEntry::getId)).toList();
        Assertions.assertEquals(expectedMakers.size(), actualMakers.size());
        for(int i = 0; i < expectedMakers.size(); i++) {
            assertSameRow(expectedMakers.get(i), actualMakers.get(i));
        }
    }

    /**
     * An encrypted wallet file in the database format holding swap rows and maker history, as a build with the swap tab
     * leaves it: the same V14 tables, written through the same DAOs.
     */
    private File fileWithSwapRows() throws IOException, MnemonicException, StorageException {
        Storage storage = new Storage(getFile("sparrow-single-seed-wallet"));
        Wallet wallet = storage.loadEncryptedWallet("pass").getWallet();
        File tempWallet = File.createTempFile("sparrow", "tmp");
        tempWallet.deleteOnExit();
        Storage tempStorage = new Storage(tempWallet);
        tempStorage.setKeyDeriver(storage.getKeyDeriver());
        tempStorage.setEncryptionPubKey(storage.getEncryptionPubKey());
        tempStorage.saveWallet(wallet);

        //Loading moves the saved file to the database format, as the app does
        Storage migrated = new Storage(tempStorage.getWalletFile());
        Wallet dbWallet = migrated.loadEncryptedWallet("pass").getWallet();
        migrated.getWalletFile().deleteOnExit();

        //The rows go in as the swap tab's build writes them: into the V14 tables of the wallet's schema
        dbWallet.getSwapEntries().addAll(List.of(entry(0, "FUNDED"), entry(1, "OPEN"), entry(2, "COMPLETED")));
        dbWallet.getMakerHistory().addAll(List.of(new MakerHistoryEntry(MAKER_A, 3, 1, 2), new MakerHistoryEntry(MAKER_B, 0, 0, 5)));
        migrated.saveWallet(dbWallet);
        migrated.closeAndWait();
        return migrated.getWalletFile();
    }

    private static Wallet reopen(Storage storage) throws IOException, StorageException {
        //The file is locked while its storage is open, so reopen it as the app would after closing the wallet
        storage.closeAndWait();
        return new Storage(storage.getWalletFile()).loadEncryptedWallet("pass").getWallet();
    }

    @Test
    public void swapRowsAreReadAsWritten() throws Exception {
        Wallet loaded = new Storage(fileWithSwapRows()).loadEncryptedWallet("pass").getWallet();
        List<SwapEntry> entries = loaded.getSwapEntries().stream().sorted(Comparator.comparingInt(SwapEntry::getKeyIndex)).toList();
        Assertions.assertEquals(3, entries.size());
        for(int i = 0; i < 3; i++) {
            SwapEntry expected = entry(i, List.of("FUNDED", "OPEN", "COMPLETED").get(i));
            Assertions.assertEquals(expected.getSwapId(), entries.get(i).getSwapId());
            Assertions.assertEquals(expected.getStatus(), entries.get(i).getStatus());
            Assertions.assertArrayEquals(expected.getSealed(), entries.get(i).getSealed());
            Assertions.assertEquals(expected.getUpdatedSeconds(), entries.get(i).getUpdatedSeconds());
            Assertions.assertNotNull(entries.get(i).getId());
        }
        Assertions.assertEquals(2, loaded.getMakerHistory().size());
        Assertions.assertTrue(Storage.holdsSwapRecords(loaded));
    }

    @Test
    public void swapRowsSurviveAFullSaveUnchanged() throws Exception {
        Storage open = new Storage(fileWithSwapRows());
        Wallet wallet = open.loadEncryptedWallet("pass").getWallet();
        Wallet before = wallet.copy();

        //A full save cleans the schema and re-adds only what the wallet holds
        open.saveWallet(wallet);

        assertSameRows(before, reopen(open));
    }

    @Test
    public void swapRowsSurviveASettingsApply() throws Exception {
        Storage open = new Storage(fileWithSwapRows());
        Wallet live = open.loadEncryptedWallet("pass").getWallet();
        Wallet before = live.copy();

        //Settings snapshots the wallet when it opens
        WalletForm appWalletForm = new WalletForm(open, live);
        SettingsWalletForm settingsWalletForm = new SettingsWalletForm(open, live, appWalletForm);

        //Pending mints go on changing the live wallet while Settings is open
        live.getPendingMints().add(inscription());
        open.savePendingMints(live);

        //A gap limit change takes the refresh branch, a full save of the Settings copy
        settingsWalletForm.gapLimitChanged(live.getGapLimit() + 5);
        settingsWalletForm.saveAndRefresh();

        Wallet loaded = reopen(open);
        Assertions.assertEquals(live.getGapLimit() + 5, loaded.getGapLimit());
        assertSameRows(before, loaded);
        Assertions.assertEquals(1, loaded.getPendingMints().size());
        Assertions.assertEquals(COMMIT, loaded.getPendingMints().getFirst().getCommitTxid());
    }

    @Test
    public void passwordChangeIsRefusedWhileTheFileHoldsSwapRows() throws Exception {
        Storage open = new Storage(fileWithSwapRows());
        Wallet wallet = open.loadEncryptedWallet("pass").getWallet();
        Optional<String> refusal = Storage.passwordChangeRefusal(wallet);
        Assertions.assertEquals(Optional.of(Storage.SWAP_RECORDS_PASSWORD_REFUSAL), refusal);
        Assertions.assertTrue(refusal.get().contains("sealed to the wallet password"));
        Assertions.assertTrue(refusal.get().contains("only that build can seal them again"));
        //The Settings tab asks with its own copy of the wallet
        Assertions.assertEquals(refusal, Storage.passwordChangeRefusal(wallet.copy()));
        open.closeAndWait();

        //An account holding rows counts for the whole file
        Wallet master = new Storage(getFile("sparrow-single-seed-wallet")).loadEncryptedWallet("pass").getWallet();
        Assertions.assertEquals(Optional.empty(), Storage.passwordChangeRefusal(master));
        Wallet account = new Wallet("Account");
        account.setMasterWallet(master);
        master.getChildWallets().add(account);
        account.getSwapEntries().add(entry(0, "OPEN"));
        Assertions.assertEquals(Optional.of(Storage.SWAP_RECORDS_PASSWORD_REFUSAL), Storage.passwordChangeRefusal(master));
        Assertions.assertEquals(Optional.of(Storage.SWAP_RECORDS_PASSWORD_REFUSAL), Storage.passwordChangeRefusal(account));
    }
}
