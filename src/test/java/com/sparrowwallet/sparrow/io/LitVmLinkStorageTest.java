package com.sparrowwallet.sparrow.io;

import com.sparrowwallet.drongo.evm.EvmAddress;
import com.sparrowwallet.drongo.wallet.LitVmLink;
import com.sparrowwallet.drongo.wallet.MnemonicException;
import com.sparrowwallet.drongo.wallet.Wallet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;

public class LitVmLinkStorageTest extends IoTest {
    private static final EvmAddress ADDRESS = EvmAddress.fromString("0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266");

    @Test
    public void encryptedSeparateAccountSurvivesSaveAndLoad() throws IOException, MnemonicException, StorageException {
        System.setProperty(Wallet.ALLOW_DERIVATIONS_MATCHING_OTHER_NETWORKS_PROPERTY, "true");
        Storage storage = new Storage(getFile("sparrow-single-seed-wallet"));
        WalletAndKey walletAndKey = storage.loadEncryptedWallet("pass");
        Wallet wallet = walletAndKey.getWallet();

        LitVmLink link = LitVmLink.fromMnemonic("test test test test test test test test test test test junk", 0);
        wallet.setLitVmLink(link.encrypt(walletAndKey.getKey()));
        link.clear();

        File tempWallet = File.createTempFile("sparrow", "tmp");
        tempWallet.deleteOnExit();
        Storage tempStorage = new Storage(tempWallet);
        tempStorage.setKeyDeriver(storage.getKeyDeriver());
        tempStorage.setEncryptionPubKey(storage.getEncryptionPubKey());
        tempStorage.saveWallet(wallet);

        WalletAndKey loaded = new Storage(tempStorage.getWalletFile()).loadEncryptedWallet("pass");
        LitVmLink loadedLink = loaded.getWallet().getLitVmLink();
        Assertions.assertNotNull(loadedLink);
        Assertions.assertEquals(LitVmLink.Source.MNEMONIC, loadedLink.getSource());
        Assertions.assertEquals(ADDRESS, loadedLink.getAddress());
        Assertions.assertTrue(loadedLink.isEncrypted());
        Assertions.assertNull(loadedLink.getSecretBytes());

        Wallet copy = loaded.getWallet().copy();
        copy.decrypt(loaded.getKey());
        Assertions.assertEquals(ADDRESS, copy.getLitVmLink().getAccount(null).getAddress());
        copy.clearPrivate();
    }

    @AfterEach
    void tearDown() {
        System.setProperty(Wallet.ALLOW_DERIVATIONS_MATCHING_OTHER_NETWORKS_PROPERTY, "false");
    }
}
