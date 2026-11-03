package com.sparrowwallet.sparrow.wallet;

import com.sparrowwallet.drongo.Coin;
import com.sparrowwallet.drongo.protocol.ScriptType;
import com.sparrowwallet.drongo.wallet.Wallet;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Enable MWEB links the new same-seed MWEB account to its public wallet. Wallets that had MWEB before that, and Cake or
 * backup imports that bring an MWEB account, were never linked, so Move to Private and Move to Public stayed hidden
 * until Settings > Link Private Wallet. Opening such a wallet now links the account, unless a link already exists.
 */
public class WalletLinkAutoTest {
    private static Wallet[] litecoinWithMwebAccount() {
        Wallet master = new Wallet("public");
        master.setCoin(Coin.LITECOIN);
        master.setScriptType(ScriptType.P2WPKH);
        Wallet account = new Wallet("private");
        account.setCoin(Coin.LITECOIN);
        account.setScriptType(ScriptType.MWEB);
        account.setMasterWallet(master);
        master.getChildWallets().add(account);
        return new Wallet[] {master, account};
    }

    @Test
    public void unlinkedSameSeedAccountIsLinked() {
        Wallet account = litecoinWithMwebAccount()[1];
        assertTrue(WalletLink.shouldLinkToOwnPublicWallet(account, null, null));
    }

    @Test
    public void anExistingLinkIsKept() {
        Wallet account = litecoinWithMwebAccount()[1];
        //The public wallet already points at another (separate-seed) private wallet the user chose
        assertFalse(WalletLink.shouldLinkToOwnPublicWallet(account, "other-mweb-wallet", null));
        //This account is already some public wallet's private side
        assertFalse(WalletLink.shouldLinkToOwnPublicWallet(account, null, "other-public-wallet"));
    }

    @Test
    public void onlyAnMwebAccountOfAPublicWallet() {
        Wallet[] wallets = litecoinWithMwebAccount();
        assertFalse(WalletLink.shouldLinkToOwnPublicWallet(wallets[0], null, null), "the public wallet itself");

        Wallet separate = new Wallet("separate");
        separate.setCoin(Coin.LITECOIN);
        separate.setScriptType(ScriptType.MWEB);
        assertFalse(WalletLink.shouldLinkToOwnPublicWallet(separate, null, null), "a separate-seed MWEB wallet");
    }
}
