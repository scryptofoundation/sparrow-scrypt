package com.sparrowwallet.sparrow.io;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sparrowwallet.drongo.Coin;
import com.sparrowwallet.drongo.KeyDerivation;
import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.drongo.SecureString;
import com.sparrowwallet.drongo.Utils;
import com.sparrowwallet.drongo.crypto.Argon2KeyDeriver;
import com.sparrowwallet.drongo.crypto.ChildNumber;
import com.sparrowwallet.drongo.crypto.DeterministicKey;
import com.sparrowwallet.drongo.crypto.Key;
import com.sparrowwallet.drongo.policy.Policy;
import com.sparrowwallet.drongo.policy.PolicyType;
import com.sparrowwallet.drongo.protocol.ScriptType;
import com.sparrowwallet.drongo.wallet.*;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.*;

/**
 * Password-encrypted backup of a single-signature wallet's seed: the recovery words, the seed passphrase, and
 * everything needed to rebuild the wallet exactly (coin, network, script type, derivation, MWEB sub-wallet).
 * <p>
 * The backup has its own password, separate from the wallet password. The key is derived with Argon2id and the
 * payload sealed with AES-256-GCM, so a wrong password or a modified file fails authentication instead of
 * decrypting to garbage. All KDF and cipher parameters are stored in the file and bound to the ciphertext as
 * GCM associated data, so tampering with them (for example, weakening the KDF cost) is also detected.
 * <p>
 * File layout (JSON): {@code type, version, kdf{algorithm, salt, iterations, memoryKiB, parallelism},
 * cipher{algorithm, nonce}, ciphertext}. The ciphertext decrypts to a JSON payload with {@code walletName,
 * coin, network, scriptType, derivation, seedType, mnemonic, passphrase, mweb, birthDate}.
 */
public class SeedBackup implements WalletImport {
    public static final String FILE_EXTENSION = "seedbackup";

    private static final String TYPE = "sparrow-scrypt-seed-backup";
    private static final int VERSION = 1;
    private static final String KDF_ALGORITHM = "argon2id";
    private static final String CIPHER_ALGORITHM = "aes-256-gcm";
    private static final int GCM_NONCE_LENGTH = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final int KEY_LENGTH = 32;

    //Wallet-grade cost (drongo SPRW1): 10 passes over 256 MiB, 4 lanes. Stated explicitly rather than taken from
    //Argon2KeyDeriver's no-arg constructor, which silently drops to test parameters under a Gradle test worker.
    static final Argon2KeyDeriver.Argon2Parameters BACKUP_PARAMETERS = new Argon2KeyDeriver.Argon2Parameters(16, KEY_LENGTH, 10, 256 * 1024, 4);

    //Argon2 runs before the GCM tag can be checked, so these bounds are the only defence against a hostile file
    //claiming a cost that hangs or exhausts the machine. The minimums refuse a cost low enough to brute-force
    //(a weakened file can't decrypt anyway, since the parameters are authenticated, but there is no reason to try).
    private static final int MIN_ITERATIONS = 3;
    private static final int MIN_MEMORY_KIB = 64 * 1024;
    private static final int MAX_MEMORY_KIB = 1024 * 1024;
    private static final int MAX_ITERATIONS = 20;
    private static final int MAX_PARALLELISM = 16;
    private static final int MAX_SALT_LENGTH = 64;
    private static final int MAX_FILE_BYTES = 64 * 1024;

    //Tests encrypt with drongo's fast TEST_PARAMETERS, which sit below the minimums. Set only by tests.
    static boolean allowTestParameters = false;

    private static final SecureRandom secureRandom = new SecureRandom();

    @Override
    public String getName() {
        return "Encrypted Seed Backup";
    }

    @Override
    public WalletModel getWalletModel() {
        return WalletModel.SPARROW;
    }

    @Override
    public String getWalletImportDescription() {
        return "Restore a wallet from an encrypted seed backup (." + FILE_EXTENSION + ") saved from a wallet's seed words dialog. You will need the backup's own password.";
    }

    @Override
    public boolean isEncrypted(File file) {
        return true;
    }

    @Override
    public boolean isWalletImportScannable() {
        return false;
    }

    /**
     * Encrypts a backup of a single-signature seed wallet. The keystore must be decrypted (seed words readable).
     * When the wallet is a child (e.g. the MWEB Private sub-wallet), pass its master: the backup describes the
     * master and records whether an MWEB sub-wallet exists.
     */
    public static byte[] createBackup(Wallet masterWallet, Keystore decryptedKeystore, CharSequence backupPassword) throws ExportException {
        return createBackup(masterWallet, decryptedKeystore, backupPassword, BACKUP_PARAMETERS);
    }

    static byte[] createBackup(Wallet masterWallet, Keystore decryptedKeystore, CharSequence backupPassword, Argon2KeyDeriver.Argon2Parameters parameters) throws ExportException {
        if(backupPassword == null || backupPassword.isEmpty()) {
            throw new ExportException("A backup password is required.");
        }
        if(masterWallet.getPolicyType() != PolicyType.SINGLE || masterWallet.getKeystores().size() != 1) {
            throw new ExportException("Seed backups are only available for single signature wallets.");
        }
        DeterministicSeed seed = decryptedKeystore.getSeed();
        if(seed == null || seed.isEncrypted() || seed.getMnemonicCode() == null) {
            throw new ExportException("The wallet must be unlocked to back up its seed.");
        }
        if(seed.getType() != DeterministicSeed.Type.BIP39 && seed.getType() != DeterministicSeed.Type.ELECTRUM) {
            throw new ExportException("Only BIP39 and Electrum seeds can be backed up.");
        }
        //A wallet opened after cancelling its passphrase prompt has no passphrase loaded. Backing that up would
        //record an empty passphrase, which restores a different, empty wallet.
        if(seed.needsPassphrase() && seed.getPassphrase() == null) {
            throw new ExportException("This wallet's seed passphrase is not loaded. Close and reopen the wallet, enter its passphrase, then try again.");
        }

        //The one invariant that matters: what this backup restores must be this wallet. Re-derive the account key
        //from the seed (with its passphrase) at the wallet's path and require it to equal the wallet's own key.
        Keystore walletKeystore = masterWallet.getKeystores().getFirst();
        DeterministicKey walletAccountKey = walletKeystore.getExtendedPublicKey().getKey();
        try {
            Keystore derived = Keystore.fromSeed(seed, walletKeystore.getKeyDerivation().getDerivation());
            if(!sameKey(derived.getExtendedPublicKey().getKey(), walletAccountKey)) {
                throw new ExportException("The seed does not reproduce this wallet's keys (is the passphrase correct?). No backup was written.");
            }
        } catch(MnemonicException e) {
            throw new ExportException("The seed could not be read.", e);
        }

        JsonObject payload = new JsonObject();
        payload.addProperty("walletName", masterWallet.getName());
        payload.addProperty("coin", masterWallet.getCoin().name());
        payload.addProperty("network", masterWallet.getNetwork() == null ? Network.get().name() : masterWallet.getNetwork().name());
        payload.addProperty("scriptType", masterWallet.getScriptType().name());
        //The master's own path: the decrypted keystore may be the MWEB child's (m/1000'), opened from the Private tab
        payload.addProperty("derivation", masterWallet.getKeystores().getFirst().getKeyDerivation().getDerivationPath());
        payload.addProperty("seedType", seed.getType().name());
        payload.addProperty("mnemonic", String.join(" ", seed.getMnemonicCode()));
        SecureString passphrase = seed.getPassphrase();
        payload.addProperty("passphrase", passphrase == null ? "" : passphrase.asString());
        payload.addProperty("mweb", masterWallet.getChildWallet(ScriptType.MWEB) != null);
        if(masterWallet.getBirthDate() != null) {
            payload.addProperty("birthDate", masterWallet.getBirthDate().getTime());
        }
        //Checked on restore: the rebuilt keys must reproduce exactly these
        payload.addProperty("fingerprint", walletKeystore.getKeyDerivation().getMasterFingerprint());
        payload.addProperty("accountKey", Utils.bytesToHex(walletAccountKey.getPubKey()) + ":" + Utils.bytesToHex(walletAccountKey.getChainCode()));

        byte[] plaintext = payload.toString().getBytes(StandardCharsets.UTF_8);
        byte[] salt = new byte[parameters.saltLength];
        secureRandom.nextBytes(salt);
        byte[] nonce = new byte[GCM_NONCE_LENGTH];
        secureRandom.nextBytes(nonce);

        Key key = new Argon2KeyDeriver(parameters, salt).deriveKey(backupPassword);
        try {
            byte[] aad = associatedData(parameters.iterations, parameters.memory, parameters.parallelism, salt, nonce);
            byte[] ciphertext = gcm(Cipher.ENCRYPT_MODE, key.getKeyBytes(), nonce, aad, plaintext);

            JsonObject kdf = new JsonObject();
            kdf.addProperty("algorithm", KDF_ALGORITHM);
            kdf.addProperty("salt", Utils.bytesToHex(salt));
            kdf.addProperty("iterations", parameters.iterations);
            kdf.addProperty("memoryKiB", parameters.memory);
            kdf.addProperty("parallelism", parameters.parallelism);

            JsonObject cipher = new JsonObject();
            cipher.addProperty("algorithm", CIPHER_ALGORITHM);
            cipher.addProperty("nonce", Utils.bytesToHex(nonce));

            JsonObject file = new JsonObject();
            file.addProperty("type", TYPE);
            file.addProperty("version", VERSION);
            file.add("kdf", kdf);
            file.add("cipher", cipher);
            file.addProperty("ciphertext", Base64.getEncoder().encodeToString(ciphertext));
            return file.toString().getBytes(StandardCharsets.UTF_8);
        } catch(GeneralSecurityException e) {
            throw new ExportException("Could not encrypt the backup.", e);
        } finally {
            key.clear();
            Arrays.fill(plaintext, (byte)0);
        }
    }

    @Override
    public Wallet importWallet(InputStream inputStream, String password) throws ImportException {
        if(password == null || password.isEmpty()) {
            throw new ImportException("Enter the backup's password.");
        }

        JsonObject payload = decryptPayload(inputStream, password);
        try {
            return createWallet(payload);
        } catch(ImportException e) {
            throw e;
        } catch(Exception e) {
            throw new ImportException("The backup could not be restored: " + e.getMessage(), e);
        }
    }

    static JsonObject decryptPayload(InputStream inputStream, CharSequence password) throws ImportException {
        JsonObject file;
        try {
            byte[] bytes = inputStream.readNBytes(MAX_FILE_BYTES + 1);
            if(bytes.length > MAX_FILE_BYTES) {
                throw new ImportException("This is not an encrypted seed backup file.");
            }
            file = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch(IOException | RuntimeException e) {
            //No cause attached: the import pane shows a cause's message in place of ours
            throw new ImportException("This is not an encrypted seed backup file.");
        }

        byte[] plaintext = null;
        Key key = null;
        try {
            if(!TYPE.equals(file.get("type").getAsString())) {
                throw new ImportException("This is not an encrypted seed backup file.");
            }
            if(file.get("version").getAsInt() != VERSION) {
                throw new ImportException("This backup was made by a newer version and cannot be read here.");
            }

            JsonObject kdf = file.getAsJsonObject("kdf");
            JsonObject cipher = file.getAsJsonObject("cipher");
            if(!KDF_ALGORITHM.equals(kdf.get("algorithm").getAsString()) || !CIPHER_ALGORITHM.equals(cipher.get("algorithm").getAsString())) {
                throw new ImportException("Unsupported backup encryption.");
            }

            byte[] salt = Utils.hexToBytes(kdf.get("salt").getAsString());
            int iterations = kdf.get("iterations").getAsInt();
            int memory = kdf.get("memoryKiB").getAsInt();
            int parallelism = kdf.get("parallelism").getAsInt();
            byte[] nonce = Utils.hexToBytes(cipher.get("nonce").getAsString());
            byte[] ciphertext = Base64.getDecoder().decode(file.get("ciphertext").getAsString());

            if(salt.length < 16 || salt.length > MAX_SALT_LENGTH || nonce.length != GCM_NONCE_LENGTH || parallelism < 1 || parallelism > MAX_PARALLELISM
                    || iterations < 1 || iterations > MAX_ITERATIONS || memory < 8 * parallelism || memory > MAX_MEMORY_KIB
                    || (!allowTestParameters && (iterations < MIN_ITERATIONS || memory < MIN_MEMORY_KIB))) {
                throw new ImportException("The backup's encryption settings are invalid.");
            }

            Argon2KeyDeriver.Argon2Parameters parameters = new Argon2KeyDeriver.Argon2Parameters(salt.length, KEY_LENGTH, iterations, memory, parallelism);
            key = new Argon2KeyDeriver(parameters, salt).deriveKey(password);
            try {
                plaintext = gcm(Cipher.DECRYPT_MODE, key.getKeyBytes(), nonce, associatedData(iterations, memory, parallelism, salt, nonce), ciphertext);
            } catch(GeneralSecurityException e) {
                throw new ImportException("Incorrect backup password, or the backup file has been damaged or modified.");
            }

            return JsonParser.parseString(new String(plaintext, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch(ImportException e) {
            throw e;
        } catch(RuntimeException e) {
            throw new ImportException("The backup file is damaged or incomplete.");
        } finally {
            if(key != null) {
                key.clear();
            }
            if(plaintext != null) {
                Arrays.fill(plaintext, (byte)0);
            }
        }
    }

    static Wallet createWallet(JsonObject payload) throws ImportException, MnemonicException {
        Network network = Network.valueOf(payload.get("network").getAsString());
        if(network != Network.get()) {
            throw new ImportException("This backup is for " + network.getName() + ", but the app is running on " + Network.get().getName() + ".");
        }

        Coin coin = Coin.valueOf(payload.get("coin").getAsString());
        ScriptType scriptType = ScriptType.valueOf(payload.get("scriptType").getAsString());
        if(!ScriptType.getScriptTypesForPolicyType(PolicyType.SINGLE).contains(scriptType) && scriptType != ScriptType.MWEB) {
            throw new ImportException("Unsupported script type in backup: " + scriptType);
        }

        DeterministicSeed.Type seedType = DeterministicSeed.Type.valueOf(payload.get("seedType").getAsString());
        List<String> words = Arrays.asList(payload.get("mnemonic").getAsString().split(" "));
        String passphrase = payload.has("passphrase") ? payload.get("passphrase").getAsString() : "";
        List<ChildNumber> derivation = KeyDerivation.parsePath(payload.get("derivation").getAsString());
        boolean mweb = payload.has("mweb") && payload.get("mweb").getAsBoolean();

        //Build under the backup's coin: derivation defaults, xpub headers and the MWEB child all resolve the active
        //coin, which on the FX thread would otherwise be whichever tab is focused
        Coin previousCoin = Coin.beginScope(coin);
        try {
            DeterministicSeed seed = new DeterministicSeed(new ArrayList<>(words), passphrase, 0, seedType);
            Keystore keystore = Keystore.fromSeed(seed, derivation);

            if(payload.has("accountKey")) {
                DeterministicKey restoredKey = keystore.getExtendedPublicKey().getKey();
                String restored = Utils.bytesToHex(restoredKey.getPubKey()) + ":" + Utils.bytesToHex(restoredKey.getChainCode());
                if(!restored.equals(payload.get("accountKey").getAsString())
                        || !keystore.getKeyDerivation().getMasterFingerprint().equals(payload.get("fingerprint").getAsString())) {
                    throw new ImportException("The restored keys do not match the wallet this backup was made from. Nothing was imported.");
                }
            }

            Wallet wallet = new Wallet(payload.get("walletName").getAsString());
            wallet.setCoin(coin);
            wallet.setNetwork(network);
            wallet.setPolicyType(PolicyType.SINGLE);
            wallet.setScriptType(scriptType);
            wallet.getKeystores().add(keystore);
            wallet.setDefaultPolicy(Policy.getPolicy(PolicyType.SINGLE, scriptType, wallet.getKeystores(), 1));
            if(payload.has("birthDate")) {
                wallet.setBirthDate(new Date(payload.get("birthDate").getAsLong()));
            }

            if(mweb && coin.isMwebSupported() && scriptType != ScriptType.MWEB) {
                wallet.addMwebChildWallet();
                wallet.setLabel("Public");
            }

            return wallet;
        } finally {
            Coin.endScope(previousCoin);
        }
    }

    /**
     * Decrypts and rebuilds a freshly written backup to prove it opens with this password and reproduces the
     * wallet's keys, before the user is told it was saved.
     */
    public static void verifyBackup(byte[] backup, CharSequence password) throws ImportException {
        JsonObject payload = decryptPayload(new java.io.ByteArrayInputStream(backup), password);
        try {
            Wallet restored = createWallet(payload);
            for(Keystore keystore : restored.getKeystores()) {
                keystore.clearPrivate();
            }
        } catch(ImportException e) {
            throw e;
        } catch(Exception e) {
            throw new ImportException("The backup could not be verified: " + e.getMessage(), e);
        }
    }

    private static boolean sameKey(DeterministicKey a, DeterministicKey b) {
        return Arrays.equals(a.getPubKey(), b.getPubKey()) && Arrays.equals(a.getChainCode(), b.getChainCode());
    }

    private static byte[] associatedData(int iterations, int memory, int parallelism, byte[] salt, byte[] nonce) {
        String header = TYPE + "|" + VERSION + "|" + KDF_ALGORITHM + "|" + Utils.bytesToHex(salt) + "|" + iterations + "|" + memory + "|" + parallelism
                + "|" + CIPHER_ALGORITHM + "|" + Utils.bytesToHex(nonce);
        return header.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] gcm(int mode, byte[] keyBytes, byte[] nonce, byte[] aad, byte[] input) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(keyBytes, "AES"), new GCMParameterSpec(GCM_TAG_BITS, nonce));
        cipher.updateAAD(aad);
        return cipher.doFinal(input);
    }
}
