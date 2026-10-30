package com.sparrowwallet.sparrow.control;

import com.sparrowwallet.drongo.Coin;
import com.sparrowwallet.drongo.KeyPurpose;
import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.drongo.policy.Policy;
import com.sparrowwallet.drongo.policy.PolicyType;
import com.sparrowwallet.drongo.protocol.ScriptType;
import com.sparrowwallet.drongo.wallet.Keystore;
import com.sparrowwallet.drongo.wallet.KeystoreSource;
import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.drongo.wallet.WalletModel;
import com.sparrowwallet.hummingbird.ResultType;
import com.sparrowwallet.hummingbird.UR;
import com.sparrowwallet.hummingbird.URDecoder;
import com.sparrowwallet.sparrow.io.SparrowMobile;
import com.sparrowwallet.sparrow.io.Storage;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Stands in for the webcam when a PC has none: feeds the phone's animated export QR, as the text of each
 * frame in scan order, through the scan dialog's own UR decoding, keeps the P2WPKH keystore the way the
 * airgapped import pane does with the Sparrow Mobile importer, and writes the watch-only wallet for the
 * app to open. Env SPARROW_MOBILE_SCAN_FRAMES is the frames file (one QR text per line);
 * SPARROW_MOBILE_SCAN_OUT is the new wallet file (refuses to overwrite). Regtest, Litecoin, no password.
 */
public class SparrowMobileScanImportLiveTest {
    @Test
    public void importPhoneExport() throws Exception {
        String framesPath = System.getenv("SPARROW_MOBILE_SCAN_FRAMES");
        String outPath = System.getenv("SPARROW_MOBILE_SCAN_OUT");
        Assumptions.assumeTrue(framesPath != null && outPath != null);
        File out = new File(outPath);
        Assertions.assertFalse(out.exists(), "refusing to overwrite " + out);

        Network previousNetwork = Network.get();
        Network.set(Network.REGTEST);
        Coin previousCoin = Coin.beginScope(Coin.LITECOIN);
        try {
            URDecoder decoder = new URDecoder();
            List<String> frames = Files.readAllLines(Path.of(framesPath)).stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
            for(String frame : frames) {
                decoder.receivePart(frame);
                if(decoder.getResult() != null) {
                    break;
                }
            }
            Assertions.assertNotNull(decoder.getResult(), "frames did not complete the UR");
            Assertions.assertEquals(ResultType.SUCCESS, decoder.getResult().type);
            UR ur = decoder.getResult().ur;

            //the dialog's listener reads nothing from the dialog when turning a UR into a result,
            //so it is allocated without its constructor (which needs a dialog, and a dialog needs a screen)
            Class<?> listenerClass = Class.forName(QRScanDialog.class.getName() + "$QRResultListener");
            Field theUnsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            Object unsafe = theUnsafe.get(null);
            Object listener = unsafe.getClass().getMethod("allocateInstance", Class.class).invoke(unsafe, listenerClass);
            Method extract = listenerClass.getDeclaredMethod("extractResultFromUR", UR.class);
            extract.setAccessible(true);
            QRScanDialog.Result result = (QRScanDialog.Result)extract.invoke(listener, ur);
            Assertions.assertNull(result.exception);
            Assertions.assertNotNull(result.wallets);

            //as FileImportPane.getScannedKeystore does for the chosen importer
            SparrowMobile importer = new SparrowMobile();
            Keystore keystore = result.wallets.stream().filter(w -> w.getScriptType() == ScriptType.P2WPKH && !w.getKeystores().isEmpty())
                    .findFirst().orElseThrow().getKeystores().get(0);
            if(Keystore.DEFAULT_LABEL.equals(keystore.getLabel())) {
                keystore.setLabel(importer.getName());
            }
            keystore.setSource(KeystoreSource.HW_AIRGAPPED);
            keystore.setWalletModel(importer.getWalletModel());

            Wallet wallet = new Wallet(out.getName().replace(".mv.db", ""));
            wallet.setCoin(Coin.LITECOIN);
            wallet.setNetwork(Network.REGTEST);
            wallet.setPolicyType(PolicyType.SINGLE_HD);
            wallet.setScriptType(ScriptType.P2WPKH);
            wallet.getKeystores().add(keystore);
            wallet.setDefaultPolicy(Policy.getPolicy(PolicyType.SINGLE_HD, ScriptType.P2WPKH, wallet.getKeystores(), 1));
            Assertions.assertTrue(wallet.isValid());
            Assertions.assertEquals(WalletModel.SPARROW_MOBILE, keystore.getWalletModel());
            Assertions.assertEquals("Sparrow Mobile", keystore.getLabel());

            Storage storage = new Storage(out);
            storage.setEncryptionPubKey(Storage.NO_PASSWORD_KEY);
            storage.saveWallet(wallet);
            System.out.println("wrote " + out + " from " + frames.size() + " frames");
            System.out.println("keystore " + keystore.getKeyDerivation().getMasterFingerprint() + " " + keystore.getKeyDerivation().getDerivationPath()
                    + " " + keystore.getExtendedPublicKey().toString() + " model " + keystore.getWalletModel() + " label " + keystore.getLabel());
            System.out.println("first receive address " + wallet.getFreshNode(KeyPurpose.RECEIVE).getAddress().getAddress(Network.REGTEST));
        } finally {
            Coin.endScope(previousCoin);
            Network.set(previousNetwork);
        }
    }
}
