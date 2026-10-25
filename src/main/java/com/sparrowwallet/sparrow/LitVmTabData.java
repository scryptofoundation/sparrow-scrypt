package com.sparrowwallet.sparrow;

import com.sparrowwallet.sparrow.wallet.WalletForm;

/**
 * The LitVM sub-tab under a Litecoin wallet. It carries the wallet's own form so menus, focus and the active coin
 * follow that wallet when it is selected, but it is not a wallet of its own: code that collects open wallets or
 * wallet forms from sub-tabs must skip it (see AppController.isWalletSubTab) or it would count the wallet twice.
 */
public class LitVmTabData extends WalletTabData {
    public LitVmTabData(WalletForm walletForm) {
        super(TabType.WALLET, walletForm);
    }
}
