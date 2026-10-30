package com.sparrowwallet.sparrow.io;

import com.sparrowwallet.drongo.wallet.WalletModel;

/**
 * The Sparrow mobile app in air-gapped mode, which shows its account as a ur:crypto-account QR.
 * Its model puts full previous transactions in multi-input PSBT QRs, which the phone requires.
 */
public class SparrowMobile extends KeystoneSinglesig {
    @Override
    public String getName() {
        return "Sparrow Mobile";
    }

    @Override
    public String getKeystoreImportDescription(int account) {
        return "Import QR created on the Sparrow mobile app, by turning on Air-gapped mode and selecting Export to desktop";
    }

    @Override
    public WalletModel getWalletModel() {
        return WalletModel.SPARROW_MOBILE;
    }

    @Override
    public boolean isFileFormatAvailable() {
        return false;
    }

    @Override
    public boolean isWalletImportFileFormatAvailable() {
        return false;
    }
}
