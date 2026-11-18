package com.sparrowwallet.sparrow.wallet.collectibles;

import com.sparrowwallet.drongo.Coin;
import com.sparrowwallet.drongo.policy.Policy;
import com.sparrowwallet.drongo.policy.PolicyType;
import com.sparrowwallet.drongo.protocol.ScriptType;
import com.sparrowwallet.drongo.wallet.DeterministicSeed;
import com.sparrowwallet.drongo.wallet.Keystore;
import com.sparrowwallet.drongo.wallet.Wallet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class TaprootWalletsTest {
    private static final String ABANDON = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";
    private static final String ZOO = "zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo wrong";
    private Coin previousCoin;

    @BeforeEach
    public void setUp() {
        previousCoin = Coin.beginScope(Coin.LITECOIN);
    }

    @AfterEach
    public void tearDown() {
        Coin.endScope(previousCoin);
    }

    private static Wallet wallet(String words, ScriptType scriptType, Coin coin) throws Exception {
        DeterministicSeed seed = new DeterministicSeed(new ArrayList<>(List.of(words.split(" "))), "", 0, DeterministicSeed.Type.BIP39);
        Wallet wallet = new Wallet(scriptType.name());
        wallet.setPolicyType(PolicyType.SINGLE_HD);
        wallet.setScriptType(scriptType);
        wallet.getKeystores().add(Keystore.fromSeed(seed, PolicyType.SINGLE_HD, scriptType.getDefaultDerivation()));
        wallet.setDefaultPolicy(Policy.getPolicy(PolicyType.SINGLE_HD, scriptType, wallet.getKeystores(), 1));
        wallet.setCoin(coin);
        return wallet;
    }

    @Test
    public void findPrefersTheOpenWalletThenChildThenOtherOpenWallets() throws Exception {
        Wallet taproot = wallet(ABANDON, ScriptType.P2TR, Coin.LITECOIN);
        Assertions.assertSame(taproot, TaprootWallets.find(taproot, List.of(taproot)).orElseThrow());

        Wallet segwit = wallet(ABANDON, ScriptType.P2WPKH, Coin.LITECOIN);
        Wallet otherOpen = wallet(ABANDON, ScriptType.P2TR, Coin.LITECOIN);
        Assertions.assertSame(otherOpen, TaprootWallets.find(segwit, List.of(segwit, otherOpen)).orElseThrow());

        Wallet child = segwit.addTaprootChildWallet();
        Assertions.assertSame(child, TaprootWallets.find(segwit, List.of(segwit, otherOpen)).orElseThrow());

        Wallet mweb = segwit.addMwebChildWallet();
        Assertions.assertSame(child, TaprootWallets.find(mweb, List.of(segwit, mweb)).orElseThrow(), "the MWEB view follows its master's taproot wallet");
    }

    @Test
    public void bellscoinTaprootIsATaprootWallet() throws Exception {
        Wallet bells = wallet(ABANDON, ScriptType.P2TR, Coin.BELLSCOIN);
        Assertions.assertSame(bells, TaprootWallets.find(bells, List.of(bells)).orElseThrow());
        Wallet bellsSegwit = wallet(ABANDON, ScriptType.P2WPKH, Coin.BELLSCOIN);
        Assertions.assertSame(bells, TaprootWallets.find(bellsSegwit, List.of(bellsSegwit, bells)).orElseThrow());
        //A Litecoin wallet of the same seed never takes the Bellscoin taproot wallet, nor the reverse
        Wallet ltcSegwit = wallet(ABANDON, ScriptType.P2WPKH, Coin.LITECOIN);
        Assertions.assertEquals(Optional.empty(), TaprootWallets.find(ltcSegwit, List.of(ltcSegwit, bells)));
        Wallet ltcTaproot = wallet(ABANDON, ScriptType.P2TR, Coin.LITECOIN);
        Assertions.assertEquals(Optional.empty(), TaprootWallets.find(bellsSegwit, List.of(bellsSegwit, ltcTaproot)));

        String text = TaprootWallets.createText(bellsSegwit);
        Assertions.assertTrue(text.contains("m/86'/" + Coin.BELLSCOIN.getBip44CoinType() + "'/0'"), text);
        Assertions.assertFalse(text.contains("m/86'/2'"), text);
        Assertions.assertTrue(TaprootWallets.createText(ltcSegwit).contains("m/86'/2'/0'"));
    }

    @Test
    public void findIgnoresOtherSeedsAndOtherCoins() throws Exception {
        Wallet segwit = wallet(ABANDON, ScriptType.P2WPKH, Coin.LITECOIN);
        Wallet otherSeed = wallet(ZOO, ScriptType.P2TR, Coin.LITECOIN);
        Wallet otherCoin = wallet(ABANDON, ScriptType.P2TR, Coin.DOGECOIN);
        Assertions.assertEquals(Optional.empty(), TaprootWallets.find(segwit, List.of(segwit, otherSeed, otherCoin)));
    }
}
