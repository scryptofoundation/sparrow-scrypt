package com.sparrowwallet.sparrow.io;

import com.sparrowwallet.drongo.Coin;
import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.drongo.policy.Policy;
import com.sparrowwallet.drongo.policy.PolicyType;
import com.sparrowwallet.drongo.protocol.ScriptType;
import com.sparrowwallet.drongo.wallet.DeterministicSeed;
import com.sparrowwallet.drongo.wallet.Keystore;
import com.sparrowwallet.drongo.wallet.LitVmLink;
import com.sparrowwallet.drongo.wallet.Wallet;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * Writes a throwaway Litecoin testnet wallet with a same-seed LitVM link, for checking the LitVM tab by hand.
 * Env LITVM_TEST_WALLET_SEED is a file of BIP39 words for a test-only seed; LITVM_TEST_WALLET_OUT is the new
 * wallet file (refuses to overwrite). The wallet has no password.
 */
public class LitVmTestWalletCreator {
    @Test
    public void createWallet() throws Exception {
        String seedPath = System.getenv("LITVM_TEST_WALLET_SEED");
        String outPath = System.getenv("LITVM_TEST_WALLET_OUT");
        Assumptions.assumeTrue(seedPath != null && outPath != null);
        File out = new File(outPath);
        Assertions.assertFalse(out.exists(), "refusing to overwrite " + out);

        //LITVM_TEST_WALLET_COIN=JUNKCOIN writes a P2PKH Junkcoin testnet wallet instead (no LitVM link), for bridge tests
        Coin coin = Coin.valueOf(System.getenv().getOrDefault("LITVM_TEST_WALLET_COIN", "LITECOIN"));
        ScriptType scriptType = coin == Coin.LITECOIN ? ScriptType.P2WPKH : ScriptType.P2PKH;
        Network previousNetwork = Network.get();
        Network.set(Network.TESTNET);
        Coin previousCoin = Coin.beginScope(coin);
        try {
            DeterministicSeed seed = new DeterministicSeed(new ArrayList<>(Arrays.asList(Files.readString(Path.of(seedPath)).trim().split("\\s+"))), "", 0, DeterministicSeed.Type.BIP39);
            Keystore keystore = Keystore.fromSeed(seed, PolicyType.SINGLE_HD, scriptType.getDefaultDerivation());
            Wallet wallet = new Wallet(out.getName().replace(".mv.db", ""));
            wallet.setCoin(coin);
            wallet.setNetwork(Network.TESTNET);
            wallet.setPolicyType(PolicyType.SINGLE_HD);
            wallet.setScriptType(scriptType);
            wallet.getKeystores().add(keystore);
            wallet.setDefaultPolicy(Policy.getPolicy(PolicyType.SINGLE_HD, scriptType, wallet.getKeystores(), 1));
            if(coin == Coin.LITECOIN) {
                wallet.setLitVmLink(LitVmLink.fromWalletSeed(seed, 0));
            }
            Assertions.assertTrue(wallet.isValid());

            Storage storage = new Storage(out);
            storage.setEncryptionPubKey(Storage.NO_PASSWORD_KEY);
            storage.saveWallet(wallet);
            storage.restorePublicKeysFromSeed(wallet, null);
            System.out.println("wrote " + out + (coin == Coin.LITECOIN ? ", LitVM address " + wallet.getLitVmLink().getAddress()
                    : ", first receive address " + wallet.getFreshNode(com.sparrowwallet.drongo.KeyPurpose.RECEIVE).getAddress().getAddress(Network.TESTNET)));
        } finally {
            Coin.endScope(previousCoin);
            Network.set(previousNetwork);
        }
    }
}
