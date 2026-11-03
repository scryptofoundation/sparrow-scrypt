package com.sparrowwallet.sparrow;

import com.sparrowwallet.drongo.protocol.Transaction;
import com.sparrowwallet.drongo.psbt.PSBT;
import com.sparrowwallet.sparrow.transaction.TransactionData;

import java.io.File;

public class TransactionTabData extends TabData {
    private final File file;
    private final TransactionData transactionData;
    //The transaction's chain, made the active coin when the tab is selected (see TransactionCoin)
    private final com.sparrowwallet.drongo.Coin coin;

    public TransactionTabData(TabType type, File file, TransactionData transactionData, com.sparrowwallet.drongo.Coin coin) {
        super(type);
        this.file = file;
        this.transactionData = transactionData;
        this.coin = coin;
    }

    public com.sparrowwallet.drongo.Coin getCoin() {
        return coin;
    }

    public File getFile() {
        return file;
    }

    public TransactionData getTransactionData() {
        return transactionData;
    }

    public Transaction getTransaction() {
        return transactionData.getTransaction();
    }

    public PSBT getPsbt() {
        return transactionData.getPsbt();
    }

    public void setPsbt(PSBT psbt) {
        transactionData.setPsbt(psbt);
    }
}
