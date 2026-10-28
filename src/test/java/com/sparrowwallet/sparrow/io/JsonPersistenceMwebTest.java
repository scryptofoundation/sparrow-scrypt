package com.sparrowwallet.sparrow.io;

import com.sparrowwallet.drongo.crypto.ECKey;
import com.sparrowwallet.drongo.wallet.Keystore;
import com.sparrowwallet.drongo.wallet.MnemonicException;
import com.sparrowwallet.drongo.wallet.Wallet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;

public class JsonPersistenceMwebTest extends IoTest {
    @Test
    public void mwebKeysRoundTrip() throws IOException, MnemonicException, StorageException {
        System.setProperty(Wallet.ALLOW_DERIVATIONS_MATCHING_OTHER_NETWORKS_PROPERTY, "true");
        Storage storage = new Storage(getFile("sparrow-single-wallet"));
        Wallet wallet = storage.loadEncryptedWallet("pass").getWallet();
        Keystore keystore = wallet.getKeystores().getFirst();
        ECKey scanKey = ECKey.fromPrivate(new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32});
        ECKey spendKey = ECKey.fromPublicOnly(ECKey.fromPrivate(new byte[] {32, 31, 30, 29, 28, 27, 26, 25, 24, 23, 22, 21, 20, 19, 18, 17, 16, 15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1}).getPubKey());
        keystore.setMwebScanPrivateKey(scanKey);
        keystore.setMwebSpendPublicKey(spendKey);

        File tempWallet = File.createTempFile("sparrow", ".json");
        tempWallet.deleteOnExit();
        Storage tempStorage = new Storage(tempWallet);
        tempStorage.setKeyDeriver(storage.getKeyDeriver());
        tempStorage.setEncryptionPubKey(storage.getEncryptionPubKey());
        new JsonPersistence().storeWallet(tempStorage, wallet);

        Keystore loaded = new Storage(tempWallet).loadUnencryptedWallet().getWallet().getKeystores().getFirst();
        Assertions.assertArrayEquals(scanKey.getPrivKeyBytes(), loaded.getMwebScanPrivateKey().getPrivKeyBytes());
        Assertions.assertArrayEquals(spendKey.getPubKey(), loaded.getMwebSpendPublicKey().getPubKey());
        Assertions.assertFalse(loaded.getMwebSpendPublicKey().hasPrivKey());
    }

    @AfterEach
    void tearDown() {
        System.setProperty(Wallet.ALLOW_DERIVATIONS_MATCHING_OTHER_NETWORKS_PROPERTY, "false");
    }
}