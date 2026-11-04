package com.sparrowwallet.sparrow;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Labeled;

/**
 * The welcome and About screens are Sparrow-LTC's, written for a Litecoin-only app. This names the running app in
 * them, and in the multi-coin app also drops the wording that only fits Litecoin.
 */
public class AppNameText {
    private static final String LTC_APP_NAME = "Sparrow-LTC";

    static String apply(String text, String appName, boolean unified) {
        if(text == null) {
            return null;
        }

        String named = text.replace(LTC_APP_NAME, appName);
        if(!unified) {
            return named;
        }

        return named.replace("is a Litecoin wallet", "is a wallet for seven Scrypt coins")
                .replace("to the Litecoin blockchain", "to each coin's blockchain")
                .replace("index all Litecoin transactions", "index all transactions");
    }

    /** Rewrites every label in the window that holds the given node */
    public static void applyToWindowOf(Node node) {
        Node root = node;
        while(root.getParent() != null) {
            root = root.getParent();
        }

        apply(root);
    }

    private static void apply(Node node) {
        if(node instanceof Labeled labeled) {
            labeled.setText(apply(labeled.getText(), SparrowWallet.APP_NAME, SparrowWallet.isUnified()));
        }
        if(node instanceof Parent parent) {
            parent.getChildrenUnmodifiable().forEach(AppNameText::apply);
        }
    }
}
