package com.sparrowwallet.sparrow.io;

import com.sparrowwallet.drongo.*;
import com.sparrowwallet.drongo.crypto.*;
import com.sparrowwallet.drongo.wallet.Keystore;
import com.sparrowwallet.drongo.wallet.MnemonicException;
import com.sparrowwallet.drongo.wallet.StandardAccount;
import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.net.ServerType;
import com.sparrowwallet.sparrow.SparrowWallet;
import javafx.concurrent.ScheduledService;
import javafx.concurrent.Service;
import javafx.concurrent.Task;
import org.apache.commons.lang3.concurrent.BasicThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Storage {
    private static final Logger log = LoggerFactory.getLogger(Storage.class);
    public static final ECKey NO_PASSWORD_KEY = ECKey.fromPublicOnly(ECKey.fromPrivate(Utils.hexToBytes("885e5a09708a167ea356a252387aa7c4893d138d632e296df8fbf5c12798bd28")));

    private static final DateFormat BACKUP_DATE_FORMAT = new SimpleDateFormat("yyyyMMddHHmmss");
    private static final Pattern DATE_PATTERN = Pattern.compile(".+-([0-9]{14}?).*");

    public static final String SPARROW_DIR = ".sparrow-ltc";
    public static final String WINDOWS_SPARROW_DIR = "Sparrow-LTC";
    public static final String WALLETS_DIR = "wallets";
    public static final String WALLETS_BACKUP_DIR = "backup";
    public static final String CERTS_DIR = "certs";
    public static final List<String> RESERVED_WALLET_NAMES = List.of("temp");

    private Persistence persistence;
    private File walletFile;
    private ECKey encryptionPubKey;

    public Storage(File walletFile) {
        this(!walletFile.exists() || walletFile.getName().endsWith("." + PersistenceType.DB.getExtension()) ? PersistenceType.DB : PersistenceType.JSON, walletFile);
    }

    public Storage(PersistenceType persistenceType, File walletFile) {
        this.persistence = persistenceType.getInstance();
        this.walletFile = walletFile;
    }

    public Storage(Persistence persistence, File walletFile) {
        this.persistence = persistence;
        this.walletFile = walletFile;
    }

    public File getWalletFile() {
        return walletFile;
    }

    public boolean isEncrypted() throws IOException {
        if(!walletFile.exists()) {
            return false;
        }

        return persistence.isEncrypted(walletFile);
    }

    public String getWalletId(Wallet wallet) {
        return persistence.getWalletId(this, wallet);
    }

    public String getWalletName(Wallet wallet) {
        return persistence.getWalletName(walletFile, wallet);
    }

    public String getWalletFileExtension() {
        if(walletFile.getName().endsWith("." + getType().getExtension())) {
            return getType().getExtension();
        }

        return "";
    }

    public WalletAndKey loadUnencryptedWallet() throws IOException, StorageException {
        Coin previousCoin = Coin.beginScope(getLoadCoin());
        try {
            WalletAndKey masterWalletAndKey = persistence.loadWallet(this);
            encryptionPubKey = NO_PASSWORD_KEY;
            inferLegacyCoin(masterWalletAndKey.getWallet());
            Coin.beginScope(masterWalletAndKey.getWallet().getCoin());
            return migrateToDb(masterWalletAndKey);
        } finally {
            Coin.endScope(previousCoin);
        }
    }

    public WalletAndKey loadEncryptedWallet(CharSequence password) throws IOException, StorageException {
        Coin previousCoin = Coin.beginScope(getLoadCoin());
        try {
            WalletAndKey masterWalletAndKey = persistence.loadWallet(this, password);
            encryptionPubKey = ECKey.fromPublicOnly(masterWalletAndKey.getEncryptionKey());
            inferLegacyCoin(masterWalletAndKey.getWallet());
            Coin.beginScope(masterWalletAndKey.getWallet().getCoin());
            return migrateToDb(masterWalletAndKey);
        } finally {
            Coin.endScope(previousCoin);
        }
    }

    /**
     * The coin a wallet is read under before its own stored coin is known. Loading runs off the FX thread,
     * so under strict scoping it needs an explicit scope, and a wallet with no stored coin (flavor era)
     * resolves Wallet.getCoin() through it. The file path is the strongest signal available without
     * reading the wallet; failing that, the startup coin - which is what an unstamped wallet has always
     * meant, and unlike the focused tab's coin does not change with what the user happens to be looking at.
     * A stored coin still wins: WalletDao re-scopes to it once the wallet row is read.
     */
    private Coin getLoadCoin() {
        Coin pathCoin = inferCoinFromPath();
        return pathCoin != null ? pathCoin : Config.getStartupCoin();
    }

    /**
     * Wallets created before the per-wallet coin field (the single-coin flavor era) have no stored coin
     * and would fall back to the process coin - mis-branding e.g. a Dogecoin wallet opened in the
     * Litecoin app and gating it off its own server. Child wallets follow the master via Wallet.getCoin().
     */
    private void inferLegacyCoin(Wallet wallet) {
        Coin pathCoin = inferCoinFromPath();

        if(wallet.coin() == null) {
            Coin inferred = pathCoin != null ? pathCoin : inferCoinFromDerivation(wallet);
            if(inferred != null) {
                log.info("Inferred coin " + inferred + " for legacy wallet " + wallet.getName() + " (" + walletFile.getAbsolutePath() + ")");
                wallet.setCoin(inferred);
            }
        } else if(wallet.coin() == Coin.LITECOIN && pathCoin != null && pathCoin != Coin.LITECOIN) {
            //Repair: interim 2026-07-22 builds could stamp LITECOIN into a legacy flavor wallet via the
            //derivation-based inference (all flavor wallets derive at Litecoin's 2'). The flavor home
            //dir the wallet lives in is authoritative over a stored LITECOIN that contradicts it.
            log.warn("Correcting stamped coin LITECOIN -> " + pathCoin + " for wallet " + wallet.getName() + " (" + walletFile.getAbsolutePath() + ")");
            wallet.setCoin(pathCoin);
        }
    }

    /**
     * Flavor-era wallets live in per-coin home dirs (%APPDATA%\Sparrow-DOGE, ~/.sparrow-doge). This is
     * the strongest signal for them, because the flavors created ALL wallets at Litecoin's m/44'/2' -
     * the coin type was not wired into ScriptType derivation until 2026-07-22 - so for those wallets the
     * derivation path identifies nothing.
     */
    private Coin inferCoinFromPath() {
        String path = walletFile.getAbsolutePath().toLowerCase(Locale.ROOT);
        for(Coin coin : Coin.values()) {
            if(path.contains("sparrow-" + coin.getTicker().toLowerCase(Locale.ROOT))) {
                return coin;
            }
        }

        //Wallets copied out of their flavor home often live in user folders named after the coin
        //(e.g. Desktop\Sparrow\Doge\wallet.mv.db). Match whole directory names only, deepest first so
        //the most specific folder wins; the filename itself is excluded.
        String[] segments = path.split("[/\\\\]");
        for(int i = segments.length - 2; i >= 0; i--) {
            for(Coin coin : Coin.values()) {
                if(segments[i].equals(coin.getTicker().toLowerCase(Locale.ROOT)) || segments[i].equals(coin.getName())) {
                    return coin;
                }
            }
        }

        return null;
    }

    /**
     * Ecosystem imports and post-2026-07-22 wallets derive at their coin's own slot. Only unambiguous,
     * non-Litecoin coin types are mapped: 2 is what every legacy flavor wallet carries regardless of its
     * actual coin, 0 is shared by Luckycoin/Junkcoin (and arbitrary imports), and Dingocoin deliberately
     * shares Dogecoin's 3 - so 3 maps to Dogecoin as the overwhelmingly common case.
     */
    private static Coin inferCoinFromDerivation(Wallet wallet) {
        for(Keystore keystore : wallet.getKeystores()) {
            if(keystore.getKeyDerivation() != null && keystore.getKeyDerivation().getDerivation() != null) {
                List<ChildNumber> derivation = keystore.getKeyDerivation().getDerivation();
                if(derivation.size() >= 2 && derivation.get(0).isHardened() && derivation.get(1).isHardened()) {
                    Coin inferred = switch(derivation.get(1).num()) {
                        case 3 -> Coin.DOGECOIN;
                        case 3434 -> Coin.PEPECOIN;
                        case 762 -> Coin.BELLSCOIN;
                        default -> null;
                    };

                    if(inferred != null) {
                        return inferred;
                    }
                }
            }
        }

        return null;
    }

    public void saveWallet(Wallet wallet) throws IOException, StorageException {
        File parent = walletFile.getParentFile();
        if(!parent.exists() && !Storage.createOwnerOnlyDirectory(parent)) {
            throw new IOException("Could not create folder " + parent);
        }

        if(encryptionPubKey != null && !NO_PASSWORD_KEY.equals(encryptionPubKey)) {
            walletFile = persistence.storeWallet(this, wallet, encryptionPubKey);
            return;
        }

        walletFile = persistence.storeWallet(this, wallet);
    }

    public void updateWallet(Wallet wallet) throws IOException, StorageException {
        if(encryptionPubKey != null && !NO_PASSWORD_KEY.equals(encryptionPubKey)) {
            persistence.updateWallet(this, wallet, encryptionPubKey);
        } else {
            persistence.updateWallet(this, wallet);
        }
    }

    public boolean isPersisted(Wallet wallet) {
        return persistence.isPersisted(this, wallet);
    }

    public boolean isClosed() {
        return persistence.isClosed();
    }

    public void close() {
        ClosePersistenceService closePersistenceService = new ClosePersistenceService();
        closePersistenceService.start();
    }

    public void restorePublicKeysFromSeed(Wallet wallet, Key key) throws MnemonicException {
        checkWalletNetwork(wallet);

        if(wallet.containsMasterPrivateKeys()) {
            //Derive xpub and master fingerprint from seed, potentially with passphrase
            Wallet copy = wallet.copy(false);
            if(wallet.isEncrypted()) {
                if(key == null) {
                    throw new IllegalStateException("Wallet was not encrypted, but seed is");
                }

                copy.decrypt(key);
            }

            for(int i = 0; i < copy.getKeystores().size(); i++) {
                Keystore copyKeystore = copy.getKeystores().get(i);
                if(copyKeystore.hasSeed() && copyKeystore.getSeed().getPassphrase() == null) {
                    if(copyKeystore.getSeed().needsPassphrase()) {
                        if(!wallet.isMasterWallet() && wallet.getMasterWallet().getKeystores().size() == copy.getKeystores().size() && wallet.getMasterWallet().getKeystores().get(i).hasSeed()) {
                            copyKeystore.getSeed().setPassphrase(wallet.getMasterWallet().getKeystores().get(i).getSeed().getPassphrase());
                        } else {
                            Optional<String> optionalPassphrase = AppServices.getInteractionServices().requestPassphrase(wallet.getFullDisplayName(), copyKeystore);
                            if(optionalPassphrase.isPresent()) {
                                copyKeystore.getSeed().setPassphrase(optionalPassphrase.get());
                            } else {
                                return;
                            }
                        }
                    } else {
                        copyKeystore.getSeed().setPassphrase("");
                    }
                }
            }

            StandardAccount standardAccount = wallet.getStandardAccountType();
            if(standardAccount != null && standardAccount.getMinimumGapLimit() != null && wallet.gapLimit() == null) {
                wallet.setGapLimit(standardAccount.getMinimumGapLimit());
            }

            for(int i = 0; i < wallet.getKeystores().size(); i++) {
                Keystore keystore = wallet.getKeystores().get(i);
                if(keystore.hasSeed()) {
                    Keystore copyKeystore = copy.getKeystores().get(i);
                    Keystore derivedKeystore = Keystore.fromSeed(copyKeystore.getSeed(), copyKeystore.getKeyDerivation().getDerivation());
                    keystore.setKeyDerivation(derivedKeystore.getKeyDerivation());
                    keystore.setExtendedPublicKey(derivedKeystore.getExtendedPublicKey());
                    keystore.getSeed().setPassphrase(copyKeystore.getSeed().getPassphrase());
                    keystore.setBip47ExtendedPrivateKey(derivedKeystore.getBip47ExtendedPrivateKey());
                    copyKeystore.getSeed().clear();
                } else if(keystore.hasMasterPrivateExtendedKey()) {
                    Keystore copyKeystore = copy.getKeystores().get(i);
                    Keystore derivedKeystore = Keystore.fromMasterPrivateExtendedKey(copyKeystore.getMasterPrivateExtendedKey(), copyKeystore.getKeyDerivation().getDerivation());
                    keystore.setKeyDerivation(derivedKeystore.getKeyDerivation());
                    keystore.setExtendedPublicKey(derivedKeystore.getExtendedPublicKey());
                    keystore.setBip47ExtendedPrivateKey(derivedKeystore.getBip47ExtendedPrivateKey());
                    copyKeystore.getMasterPrivateKey().clear();
                }
            }
        }

        for(Wallet childWallet : wallet.getChildWallets()) {
            if(childWallet.isBip47()) {
                try {
                    Keystore masterKeystore = wallet.getKeystores().get(0);
                    Keystore keystore = childWallet.getKeystores().get(0);
                    keystore.setBip47ExtendedPrivateKey(masterKeystore.getBip47ExtendedPrivateKey());
                    List<ChildNumber> derivation = keystore.getKeyDerivation().getDerivation();
                    keystore.setKeyDerivation(new KeyDerivation(masterKeystore.getKeyDerivation().getMasterFingerprint(), derivation));
                    DeterministicKey pubKey = keystore.getBip47ExtendedPrivateKey().getKey().dropPrivateBytes().dropParent();
                    keystore.setExtendedPublicKey(new ExtendedKey(pubKey, keystore.getBip47ExtendedPrivateKey().getParentFingerprint(), derivation.get(derivation.size() - 1)));
                } catch(Exception e) {
                    log.error("Cannot prepare BIP47 keystore", e);
                }
            }
        }
    }

    private void checkWalletNetwork(Wallet wallet) {
        if(wallet.getNetwork() != null && wallet.getNetwork() != Network.getCanonical()) {
            throw new IllegalStateException("Provided " + wallet.getNetwork() + " wallet is invalid on a " + Network.getCanonical() + " network. Use a " + wallet.getNetwork() + " configuration to load this wallet.");
        }
    }

    public void backupWallet() throws IOException {
        if(walletFile.toPath().startsWith(getWalletsDir().toPath())) {
            backupWallet(null);
        }
    }

    private void backupWallet(String prefix) throws IOException {
        File backupDir = getWalletsBackupDir();

        Date backupDate = new Date();
        String walletName = persistence.getWalletName(walletFile, null);
        String dateSuffix = "-" + BACKUP_DATE_FORMAT.format(backupDate);
        String backupName = walletName + dateSuffix + walletFile.getName().substring(walletName.length());

        if(prefix != null) {
            backupName = prefix + "_" + backupName;
        }

        File backupFile = new File(backupDir, backupName);
        if(!backupFile.exists()) {
            createOwnerOnlyFile(backupFile);
        }

        try(FileOutputStream outputStream = new FileOutputStream(backupFile)) {
            copyWallet(outputStream);
        }
    }

    public void copyWallet(OutputStream outputStream) throws IOException {
        persistence.copyWallet(walletFile, outputStream);
    }

    public boolean delete(boolean deleteBackups) {
        if(deleteBackups) {
            deleteBackups();
        }

        return IOUtils.secureDelete(walletFile);
    }

    public void deleteBackups() {
        deleteBackups(null);
    }

    private boolean hasStartedSince(File lastBackup) {
        try {
            Date date = BACKUP_DATE_FORMAT.parse(getBackupDate(lastBackup.getName()));
            ProcessHandle.Info processInfo = ProcessHandle.current().info();
            return (processInfo.startInstant().isPresent() && processInfo.startInstant().get().isAfter(date.toInstant()));
        } catch(Exception e) {
            log.error("Error parsing date for backup file " + lastBackup.getName(), e);
            return false;
        }
    }

    private void deleteBackups(String prefix) {
        File[] backups = getBackups(prefix);
        for(File backup : backups) {
            IOUtils.secureDelete(backup);
        }
    }

    File[] getBackups(String prefix) {
        File backupDir = getWalletsBackupDir();
        String walletName = persistence.getWalletName(walletFile, null);
        String extension = walletFile.getName().substring(walletName.length());
        File[] backups = backupDir.listFiles((dir, name) -> {
            return name.startsWith((prefix == null ? "" : prefix + "_") + walletName + "-") &&
                    getBackupDate(name) != null &&
                    (extension.isEmpty() || name.endsWith(extension));
        });

        backups = backups == null ? new File[0] : backups;
        Arrays.sort(backups, Comparator.comparing(o -> getBackupDate(((File)o).getName())).reversed());

        return backups;
    }

    private String getBackupDate(String backupFileName) {
        Matcher matcher = DATE_PATTERN.matcher(backupFileName);
        if(matcher.matches()) {
            return matcher.group(1);
        }

        return null;
    }

    private WalletAndKey migrateToDb(WalletAndKey masterWalletAndKey) throws IOException, StorageException {
        if(getType() == PersistenceType.JSON) {
            log.info("Migrating " + masterWalletAndKey.getWallet().getName() + " from JSON to DB persistence");
            masterWalletAndKey = migrateType(PersistenceType.DB, masterWalletAndKey.getWallet(), masterWalletAndKey.getEncryptionKey());
        }

        return masterWalletAndKey;
    }

    private WalletAndKey migrateType(PersistenceType type, Wallet wallet, ECKey encryptionKey) throws IOException, StorageException {
        File existingFile = walletFile;

        try {
            AsymmetricKeyDeriver keyDeriver = persistence.getKeyDeriver();
            persistence = type.getInstance();
            persistence.setKeyDeriver(keyDeriver);
            walletFile = new File(walletFile.getParentFile(), wallet.getName() + "." + type.getExtension());
            if(walletFile.exists()) {
                walletFile.delete();
            }

            saveWallet(wallet);
            if(type == PersistenceType.DB) {
                for(Wallet childWallet : wallet.getChildWallets()) {
                    saveWallet(childWallet);
                }
            }

            if(NO_PASSWORD_KEY.equals(encryptionPubKey)) {
                return persistence.loadWallet(this);
            }

            return persistence.loadWallet(this, null, encryptionKey);
        } catch(Exception e) {
            existingFile = null;
            throw e;
        } finally {
            if(existingFile != null) {
                existingFile.delete();
            }
        }
    }

    public ECKey getEncryptionPubKey() {
        return encryptionPubKey;
    }

    public void setEncryptionPubKey(ECKey encryptionPubKey) {
        this.encryptionPubKey = encryptionPubKey;
    }

    public ECKey getEncryptionKey(CharSequence password) throws IOException, StorageException {
        return persistence.getEncryptionKey(password);
    }

    public AsymmetricKeyDeriver getKeyDeriver() {
        return persistence.getKeyDeriver();
    }

    void setKeyDeriver(AsymmetricKeyDeriver keyDeriver) {
        persistence.setKeyDeriver(keyDeriver);
    }

    public PersistenceType getType() {
        return persistence.getType();
    }

    public static boolean walletExists(String walletName) {
        File encrypted = new File(getWalletsDir(), walletName.trim());
        if(encrypted.exists()) {
            return true;
        }

        for(PersistenceType persistenceType : PersistenceType.values()) {
            File unencrypted = new File(getWalletsDir(), walletName.trim() + "." + persistenceType.getExtension());
            if(unencrypted.exists()) {
                return true;
            }
        }

        if(AppServices.get().getOpenWallets().keySet().stream().anyMatch(wallet -> walletName.equals(wallet.getName()))) {
            return true;
        }

        return RESERVED_WALLET_NAMES.contains(walletName);
    }

    public static File getExistingWallet(String walletName) {
        return getExistingWallet(getWalletsDir(), walletName);
    }

    public static File getExistingWallet(File walletsDir, String walletName) {
        File encrypted = new File(walletsDir, walletName.trim());
        if(encrypted.exists()) {
            return encrypted;
        }

        for(PersistenceType persistenceType : PersistenceType.values()) {
            File unencrypted = new File(walletsDir, walletName.trim() + "." + persistenceType.getExtension());
            if(unencrypted.exists()) {
                return unencrypted;
            }
        }

        return null;
    }

    public static File getWalletFile(String walletName) {
        //TODO: Check for existing file
        return new File(getWalletsDir(), walletName);
    }

    public static boolean isWalletFile(File walletFile) {
        for(PersistenceType type : PersistenceType.values()) {
            if(walletFile.getName().endsWith("." + type.getExtension())) {
                return true;
            }

            try {
                if(type == PersistenceType.JSON && type.getInstance().isEncrypted(walletFile)) {
                    return true;
                }
            } catch(IOException e) {
                //ignore
            }
        }

        return false;
    }

    public static boolean isEncrypted(File walletFile) {
        try {
            for(PersistenceType type : PersistenceType.values()) {
                if(walletFile.getName().endsWith("." + type.getExtension())) {
                    return type.getInstance().isEncrypted(walletFile);
                }
            }

            PersistenceType detectedType = detectPersistenceType(walletFile);
            if(detectedType != null) {
                return detectedType.getInstance().isEncrypted(walletFile);
            }
        } catch(IOException e) {
            //ignore
        }

        return FileType.BINARY.equals(IOUtils.getFileType(walletFile));
    }

    public static PersistenceType detectPersistenceType(File walletFile) {
        try(Reader reader = new FileReader(walletFile, StandardCharsets.UTF_8)) {
            int firstChar = reader.read();

            if(firstChar == 'U' || firstChar == '{') {
                return PersistenceType.JSON;
            }

            if(firstChar == 'H') {
                return PersistenceType.DB;
            }
        } catch(IOException e) {
            log.error("Error detecting persistence type", e);
        }

        return null;
    }

    public static File getWalletsBackupDir() {
        File walletsBackupDir = new File(getWalletsDir(), WALLETS_BACKUP_DIR);
        if(!walletsBackupDir.exists()) {
            createOwnerOnlyDirectory(walletsBackupDir);
        }

        return walletsBackupDir;
    }

    public static File getWalletsDir() {
        File walletsDir = Config.get().getWalletsDir();
        if(walletsDir != null) {
            if(!walletsDir.exists() && (walletsDir.getParentFile() == null || !walletsDir.getParentFile().exists() || !walletsDir.getParentFile().canWrite())) {
                log.info("Configured wallets directory " + walletsDir.getAbsolutePath() + " is not reachable, reverting to default");
                walletsDir = null;
            }
        }
        if(walletsDir == null) {
            walletsDir = new File(getSparrowDir(), WALLETS_DIR);
        }
        if(!walletsDir.exists()) {
            createOwnerOnlyDirectory(walletsDir);
        }

        return walletsDir;
    }

    public static File getCertificateFile(String host) {
        return findCertFile(getCertName(host));
    }

    public static void saveCertificate(String host, Certificate cert) {
        writeCertPem(getCertName(host), cert);
    }

    public static File getCaCertificateFile(String host) {
        return findCertFile(host + ".cacert");
    }

    public static void saveCaCertificate(String host, Certificate cert) {
        writeCertPem(host + ".cacert", cert);
    }

    private static File findCertFile(String filename) {
        File[] certs = getCertsDir().listFiles((dir, name) -> name.equals(filename));
        if(certs != null && certs.length > 0) {
            return certs[0];
        }

        return null;
    }

    private static void writeCertPem(String filename, Certificate cert) {
        try(FileWriter writer = new FileWriter(new File(getCertsDir(), filename))) {
            writer.write("-----BEGIN CERTIFICATE-----\n");
            writer.write(Base64.getEncoder().encodeToString(cert.getEncoded()).replaceAll("(.{64})", "$1\n"));
            writer.write("\n-----END CERTIFICATE-----\n");
        } catch(CertificateEncodingException e) {
            log.error("Error encoding PEM certificate", e);
        } catch(IOException e) {
            log.error("Error writing PEM certificate", e);
        }
    }

    private static String getCertName(String host) {
        if(Config.get().getServerType() == ServerType.BITCOIN_CORE) {
            return host + ".bitcoind";
        }

        return host;
    }

    static File getCertsDir() {
        File certsDir = new File(getSparrowDir(), CERTS_DIR);
        if(!certsDir.exists()) {
            createOwnerOnlyDirectory(certsDir);
        }

        return certsDir;
    }

    public static File getSparrowDir() {
        File sparrowDir;
        Network network = Network.get();
        if(network != Network.MAINNET) {
            sparrowDir = new File(getSparrowHome(), network.getHome());
            if(!network.getName().equals(network.getHome()) && !sparrowDir.exists()) {
                File networkNameDir = new File(getSparrowHome(), network.getName());
                if(networkNameDir.exists() && networkNameDir.isDirectory() && !Files.isSymbolicLink(networkNameDir.toPath())) {
                    try {
                        if(networkNameDir.renameTo(sparrowDir) && !isWindows()) {
                            Files.createSymbolicLink(networkNameDir.toPath(), Path.of(sparrowDir.getName()));
                        }
                    } catch(Exception e) {
                        log.debug("Error creating symlink from " + networkNameDir.getAbsolutePath() + " to " + sparrowDir.getName(), e);
                    }
                }
            }
        } else {
            sparrowDir = getSparrowHome();
        }

        if(!sparrowDir.exists()) {
            createOwnerOnlyDirectory(sparrowDir);
        }

        if(!network.getName().equals(network.getHome()) && !isWindows()) {
            try {
                Path networkNamePath = getSparrowHome().toPath().resolve(network.getName());
                if(Files.isSymbolicLink(networkNamePath)) {
                    Path symlinkTarget = getSparrowHome().toPath().resolve(Files.readSymbolicLink(networkNamePath));
                    if(!Files.isSameFile(sparrowDir.toPath(), symlinkTarget)) {
                        Files.delete(networkNamePath);
                        Files.createSymbolicLink(networkNamePath, Path.of(sparrowDir.getName()));
                    }
                } else if(!Files.exists(networkNamePath)) {
                    Files.createSymbolicLink(networkNamePath, Path.of(sparrowDir.getName()));
                }
            } catch(Exception e) {
                log.debug("Error updating symlink from " + network.getName() + " to " + sparrowDir.getName(), e);
            }
        }

        return sparrowDir;
    }

    public static File getSparrowHome() {
        return getSparrowHome(false);
    }

    public static File getSparrowHome(boolean useDefault) {
        if(!useDefault && System.getProperty(SparrowWallet.APP_HOME_PROPERTY) != null) {
            return new File(System.getProperty(SparrowWallet.APP_HOME_PROPERTY));
        }

        //Home folder follows the app identity, fixed at startup: the historical "Sparrow-LTC" for the
        //Litecoin flavor, "Sparrow-DOGE" etc. for the other flavors, and "Sparrow-Scrypt" for the
        //unified multi-coin build. Deliberately NOT derived from the active coin, which changes with the
        //focused wallet tab - the home must never wander mid-session.
        String appName = SparrowWallet.APP_NAME;
        if(isWindows()) {
            return new File(getHomeDir(), appName);
        }

        return new File(getHomeDir(), "." + appName.toLowerCase(Locale.ROOT));
    }

    static File getHomeDir() {
        if(isWindows()) {
            return new File(System.getenv("APPDATA"));
        }

        return new File(System.getProperty("user.home"));
    }

    /**
     * One-time migration for the unified app: if this app's home has no config yet but the given legacy
     * app's home does, copy it so server settings, preferences and recent wallets carry over.
     */
    public static void migrateLegacyConfig(String legacyAppName) {
        try {
            File home = getSparrowHome();
            File config = new File(home, Config.CONFIG_FILENAME);
            if(config.exists()) {
                return;
            }

            File legacyHome = isWindows() ? new File(getHomeDir(), legacyAppName)
                    : new File(getHomeDir(), "." + legacyAppName.toLowerCase(Locale.ROOT));
            File legacyConfig = new File(legacyHome, Config.CONFIG_FILENAME);
            if(legacyConfig.exists()) {
                if(!home.exists() && !createOwnerOnlyDirectory(home)) {
                    return;
                }
                Files.copy(legacyConfig.toPath(), config.toPath());
                log.info("Migrated config from " + legacyConfig.getAbsolutePath() + " to " + config.getAbsolutePath());
            }
        } catch(Exception e) {
            log.warn("Could not migrate legacy config", e);
        }
    }

    public static boolean createOwnerOnlyDirectory(File directory) {
        try {
            if(isWindows()) {
                Files.createDirectories(directory.toPath());
                return true;
            }

            Files.createDirectories(directory.toPath(), PosixFilePermissions.asFileAttribute(getDirectoryOwnerOnlyPosixFilePermissions()));
            return true;
        } catch(UnsupportedOperationException e) {
            return directory.mkdirs();
        } catch(IOException e) {
            log.error("Could not create directory " + directory.getAbsolutePath(), e);
        }

        return false;
    }

    public static boolean createOwnerOnlyFile(File file) {
        try {
            if(isWindows()) {
                Files.createFile(file.toPath());
                return true;
            }

            Files.createFile(file.toPath(), PosixFilePermissions.asFileAttribute(getFileOwnerOnlyPosixFilePermissions()));
            return true;
        } catch(UnsupportedOperationException e) {
            return false;
        } catch(IOException e) {
            log.error("Could not create file " + file.getAbsolutePath(), e);
        }

        return false;
    }

    private static Set<PosixFilePermission> getDirectoryOwnerOnlyPosixFilePermissions() {
        Set<PosixFilePermission> ownerOnly = getFileOwnerOnlyPosixFilePermissions();
        ownerOnly.add(PosixFilePermission.OWNER_EXECUTE);

        return ownerOnly;
    }

    private static Set<PosixFilePermission> getFileOwnerOnlyPosixFilePermissions() {
        Set<PosixFilePermission> ownerOnly = EnumSet.noneOf(PosixFilePermission.class);
        ownerOnly.add(PosixFilePermission.OWNER_READ);
        ownerOnly.add(PosixFilePermission.OWNER_WRITE);

        return ownerOnly;
    }

    private static boolean isWindows() {
        return OsType.getCurrent() == OsType.WINDOWS;
    }

    public static class LoadWalletService extends Service<WalletAndKey> {
        private final Storage storage;
        private final SecureString password;

        private static Executor singleThreadedExecutor;

        public LoadWalletService(Storage storage) {
            this.storage = storage;
            this.password = null;
        }

        public LoadWalletService(Storage storage, SecureString password) {
            this.storage = storage;
            this.password = password;
        }

        @Override
        protected Task<WalletAndKey> createTask() {
            return new Task<>() {
                protected WalletAndKey call() throws IOException, StorageException {
                    WalletAndKey walletAndKey;

                    if(password != null) {
                        walletAndKey = storage.loadEncryptedWallet(password);
                        password.clear();
                    } else {
                        walletAndKey = storage.loadUnencryptedWallet();
                    }

                    return walletAndKey;
                }
            };
        }

        public static Executor getSingleThreadedExecutor() {
            if(singleThreadedExecutor == null) {
                BasicThreadFactory factory = BasicThreadFactory.builder().namingPattern("LoadWalletService-single").daemon(true).priority(Thread.MIN_PRIORITY).build();
                singleThreadedExecutor = Executors.newSingleThreadScheduledExecutor(factory);
            }

            return singleThreadedExecutor;
        }
    }

    public static class KeyDerivationService extends Service<ECKey> {
        private final Storage storage;
        private final SecureString password;
        private final boolean verifyPassword;

        public KeyDerivationService(Storage storage, SecureString password) {
            this.storage = storage;
            this.password = password;
            this.verifyPassword = false;
        }

        public KeyDerivationService(Storage storage, SecureString password, boolean verifyPassword) {
            this.storage = storage;
            this.password = password;
            this.verifyPassword = verifyPassword;
        }

        @Override
        protected Task<ECKey> createTask() {
            return new Task<>() {
                protected ECKey call() throws IOException, StorageException {
                    try {
                        ECKey encryptionFullKey = storage.getEncryptionKey(password);
                        if(verifyPassword && !ECKey.fromPublicOnly(encryptionFullKey).equals(storage.getEncryptionPubKey())) {
                            throw new InvalidPasswordException("Derived pubkey does not match stored pubkey");
                        }

                        return encryptionFullKey;
                    } finally {
                        password.clear();
                    }
                }
            };
        }
    }

    public static class DecryptWalletService extends Service<Wallet> {
        private final Wallet wallet;
        private final SecureString password;

        public DecryptWalletService(Wallet wallet, SecureString password) {
            this.wallet = wallet;
            this.password = password;
        }

        @Override
        protected Task<Wallet> createTask() {
            return new Task<>() {
                protected Wallet call() throws IOException, StorageException {
                    try {
                        wallet.decrypt(password);
                        return wallet;
                    } finally {
                        password.clear();
                    }
                }
            };
        }
    }

    public class ClosePersistenceService extends Service<Void> {
        @Override
        protected Task<Void> createTask() {
            return new Task<>() {
                protected Void call() {
                    persistence.close();
                    return null;
                }
            };
        }
    }

    public static class CopyWalletService extends Service<Void> {
        private final Wallet wallet;
        private final File newWalletFile;

        public CopyWalletService(Wallet wallet, File newWalletFile) {
            this.wallet = wallet;
            this.newWalletFile = newWalletFile;
        }

        @Override
        protected Task<Void> createTask() {
            return new Task<>() {
                protected Void call() throws IOException, ExportException {
                    Sparrow export = new Sparrow();
                    try(BufferedOutputStream outputStream = new BufferedOutputStream(new FileOutputStream(newWalletFile))) {
                        export.exportWallet(wallet, outputStream, null);
                    }

                    return null;
                }
            };
        }
    }

    public static class DeleteWalletService extends ScheduledService<Boolean> {
        private final Storage storage;
        private final boolean deleteBackups;

        public DeleteWalletService(Storage storage, boolean deleteBackups) {
            this.storage = storage;
            this.deleteBackups = deleteBackups;
        }

        @Override
        protected Task<Boolean> createTask() {
            return new Task<>() {
                protected Boolean call() {
                    return storage.delete(deleteBackups);
                }
            };
        }
    }
}
