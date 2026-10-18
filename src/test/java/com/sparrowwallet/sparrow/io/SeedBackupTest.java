package com.sparrowwallet.sparrow.io;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sparrowwallet.drongo.Coin;
import com.sparrowwallet.drongo.KeyPurpose;
import com.sparrowwallet.drongo.crypto.Argon2KeyDeriver;
import com.sparrowwallet.drongo.protocol.ScriptType;
import com.sparrowwallet.drongo.wallet.DeterministicSeed;
import com.sparrowwallet.drongo.wallet.Wallet;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

public class SeedBackupTest {
    private static final List<String> BIP39_WORDS = Arrays.asList("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".split(" "));
    private static final List<String> ELECTRUM_WORDS = Arrays.asList("wild father tree among universe such mobile favorite target dynamic credit identify".split(" "));
    private static final String PASSWORD = "correct horse battery staple";

    @BeforeAll
    public static void allowFastKdf() {
        SeedBackup.allowTestParameters = true;
    }

    private static byte[] backup(Wallet wallet) throws Exception {
        return SeedBackup.createBackup(wallet, wallet.getKeystores().getFirst(), PASSWORD, Argon2KeyDeriver.TEST_PARAMETERS);
    }

    private static Wallet restore(byte[] file, String password) throws Exception {
        return new SeedBackup().importWallet(new ByteArrayInputStream(file), password);
    }

    private static String firstAddress(Wallet wallet) {
        Coin previous = Coin.beginScope(wallet.getCoin());
        try {
            return wallet.getAddress(wallet.getFreshNode(KeyPurpose.RECEIVE)).toString();
        } finally {
            Coin.endScope(previous);
        }
    }

    @Test
    public void litecoinWithMwebAndPassphraseRoundTrips() throws Exception {
        Wallet original = CakeWallet.createWallet(Coin.LITECOIN, DeterministicSeed.Type.BIP39, BIP39_WORDS, "extra words", true);
        original.setName("Litecoin Main");

        byte[] file = backup(original);
        String text = new String(file, StandardCharsets.UTF_8);
        Assertions.assertFalse(text.contains("abandon"), "Seed words must not appear in the file");
        Assertions.assertFalse(text.contains("extra words"), "Passphrase must not appear in the file");

        Wallet restored = restore(file, PASSWORD);
        Assertions.assertEquals("Litecoin Main", restored.getName());
        Assertions.assertEquals(Coin.LITECOIN, restored.coin());
        Assertions.assertEquals(ScriptType.P2WPKH, restored.getScriptType());
        Assertions.assertEquals("m/84'/2'/0'", restored.getKeystores().getFirst().getKeyDerivation().getDerivationPath());
        Assertions.assertEquals(firstAddress(original), firstAddress(restored));

        Wallet originalMweb = original.getChildWallet(ScriptType.MWEB);
        Wallet restoredMweb = restored.getChildWallet(ScriptType.MWEB);
        Assertions.assertNotNull(restoredMweb);
        Assertions.assertEquals(firstAddress(originalMweb), firstAddress(restoredMweb));
    }

    @Test
    public void dogecoinRestoresOnItsOwnChainWhateverIsFocused() throws Exception {
        Wallet original = CakeWallet.createWallet(Coin.DOGECOIN, DeterministicSeed.Type.BIP39, BIP39_WORDS, "", false);
        byte[] file = backup(original);

        Coin previous = Coin.beginScope(Coin.LITECOIN);
        try {
            Wallet restored = restore(file, PASSWORD);
            Assertions.assertEquals(Coin.DOGECOIN, restored.coin());
            Assertions.assertEquals("m/44'/3'/0'", restored.getKeystores().getFirst().getKeyDerivation().getDerivationPath());
            Assertions.assertEquals(firstAddress(original), firstAddress(restored));
            Assertions.assertTrue(firstAddress(restored).startsWith("D"));
        } finally {
            Coin.endScope(previous);
        }
    }

    @Test
    public void electrumSeedRoundTrips() throws Exception {
        Wallet original = CakeWallet.createWallet(Coin.LITECOIN, DeterministicSeed.Type.ELECTRUM, ELECTRUM_WORDS, "", false);
        Wallet restored = restore(backup(original), PASSWORD);
        Assertions.assertEquals(DeterministicSeed.Type.ELECTRUM, restored.getKeystores().getFirst().getSeed().getType());
        Assertions.assertEquals("m/0'", restored.getKeystores().getFirst().getKeyDerivation().getDerivationPath());
        Assertions.assertEquals(firstAddress(original), firstAddress(restored));
    }

    @Test
    public void wrongPasswordIsRejected() throws Exception {
        byte[] file = backup(CakeWallet.createWallet(Coin.LITECOIN, DeterministicSeed.Type.BIP39, BIP39_WORDS, "", false));
        ImportException e = Assertions.assertThrows(ImportException.class, () -> restore(file, "wrong password"));
        Assertions.assertTrue(e.getMessage().contains("Incorrect backup password"));
        Assertions.assertThrows(ImportException.class, () -> restore(file, ""));
    }

    @Test
    public void tamperingIsDetected() throws Exception {
        byte[] file = backup(CakeWallet.createWallet(Coin.LITECOIN, DeterministicSeed.Type.BIP39, BIP39_WORDS, "", false));

        //Weakened KDF cost: the parameters are bound to the ciphertext, so this cannot decrypt
        JsonObject weakened = JsonParser.parseString(new String(file, StandardCharsets.UTF_8)).getAsJsonObject();
        weakened.getAsJsonObject("kdf").addProperty("iterations", 2);
        Assertions.assertThrows(ImportException.class, () -> restore(weakened.toString().getBytes(StandardCharsets.UTF_8), PASSWORD));

        //Flipped ciphertext byte
        JsonObject flipped = JsonParser.parseString(new String(file, StandardCharsets.UTF_8)).getAsJsonObject();
        byte[] ciphertext = Base64.getDecoder().decode(flipped.get("ciphertext").getAsString());
        ciphertext[5] ^= 0x01;
        flipped.addProperty("ciphertext", Base64.getEncoder().encodeToString(ciphertext));
        Assertions.assertThrows(ImportException.class, () -> restore(flipped.toString().getBytes(StandardCharsets.UTF_8), PASSWORD));

        //Not a backup at all
        Assertions.assertThrows(ImportException.class, () -> restore("{\"hello\":1}".getBytes(StandardCharsets.UTF_8), PASSWORD));
        Assertions.assertThrows(ImportException.class, () -> restore("not json".getBytes(StandardCharsets.UTF_8), PASSWORD));
    }

    @Test
    public void backupMustReproduceTheWalletsKeys() throws Exception {
        //Wallet made with a passphrase, but the keystore handed to the backup carries a different one: the
        //backup would restore other, empty addresses, so it must refuse rather than write a misleading file
        Wallet wallet = CakeWallet.createWallet(Coin.LITECOIN, DeterministicSeed.Type.BIP39, BIP39_WORDS, "real passphrase", false);
        Wallet wrong = CakeWallet.createWallet(Coin.LITECOIN, DeterministicSeed.Type.BIP39, BIP39_WORDS, "", false);
        Assertions.assertThrows(ExportException.class, () -> SeedBackup.createBackup(wallet, wrong.getKeystores().getFirst(), PASSWORD, Argon2KeyDeriver.TEST_PARAMETERS));
    }

    @Test
    public void freshBackupVerifies() throws Exception {
        Wallet wallet = CakeWallet.createWallet(Coin.LITECOIN, DeterministicSeed.Type.BIP39, BIP39_WORDS, "", true);
        byte[] file = backup(wallet);
        SeedBackup.verifyBackup(file, PASSWORD);
        Assertions.assertThrows(ImportException.class, () -> SeedBackup.verifyBackup(file, "wrong password"));
    }

    @Test
    public void hostileKdfCostIsRefusedBeforeDerivation() throws Exception {
        byte[] file = backup(CakeWallet.createWallet(Coin.LITECOIN, DeterministicSeed.Type.BIP39, BIP39_WORDS, "", false));
        JsonObject hostile = JsonParser.parseString(new String(file, StandardCharsets.UTF_8)).getAsJsonObject();
        hostile.getAsJsonObject("kdf").addProperty("memoryKiB", 4 * 1024 * 1024);
        ImportException e = Assertions.assertThrows(ImportException.class, () -> restore(hostile.toString().getBytes(StandardCharsets.UTF_8), PASSWORD));
        Assertions.assertTrue(e.getMessage().contains("encryption settings are invalid"));
    }

    @Test
    public void emptyBackupPasswordIsRefused() throws Exception {
        Wallet wallet = CakeWallet.createWallet(Coin.LITECOIN, DeterministicSeed.Type.BIP39, BIP39_WORDS, "", false);
        Assertions.assertThrows(ExportException.class, () -> SeedBackup.createBackup(wallet, wallet.getKeystores().getFirst(), "", Argon2KeyDeriver.TEST_PARAMETERS));
    }
}
