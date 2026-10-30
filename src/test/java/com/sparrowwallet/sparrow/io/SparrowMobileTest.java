package com.sparrowwallet.sparrow.io;

import com.sparrowwallet.drongo.wallet.WalletModel;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class SparrowMobileTest {
    @Test
    public void scansThePhoneExportAsSparrowMobile() {
        SparrowMobile importer = new SparrowMobile();
        Assertions.assertEquals("Sparrow Mobile", importer.getName());
        Assertions.assertEquals(WalletModel.SPARROW_MOBILE, importer.getWalletModel());
        Assertions.assertTrue(importer.isKeystoreImportScannable());
        Assertions.assertFalse(importer.isFileFormatAvailable());
        Assertions.assertFalse(importer.isDeprecated());
    }
}
