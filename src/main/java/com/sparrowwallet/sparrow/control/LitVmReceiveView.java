package com.sparrowwallet.sparrow.control;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.client.j2se.MatrixToImageConfig;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.sparrowwallet.drongo.evm.EvmAddress;
import com.sparrowwallet.drongo.evm.LitVmNetwork;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Map;

/**
 * The Receive view of an EVM network: the account's address, which is the same on every EVM network, as text and a
 * QR code, with the explorer link and (on LiteForge) the faucet.
 */
class LitVmReceiveView extends VBox {
    static final String LITEFORGE_FAUCET = "https://liteforge.hub.caldera.xyz/";

    LitVmReceiveView(LitVmContext context, LitVmNetwork network) {
        super(14);
        setPadding(new Insets(20, 25, 20, 25));
        EvmAddress address = context.address();

        Label title = new Label("Receive");
        title.setStyle("-fx-font-size: 1.2em;");
        TextField addressField = new TextField(address.toString());
        addressField.setEditable(false);
        addressField.setPrefColumnCount(36);
        addressField.setStyle("-fx-font-family: 'Fragment Mono Regular';");
        Button copy = new Button("Copy");
        copy.setOnAction(e -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(address.toString());
            Clipboard.getSystemClipboard().setContent(content);
            context.status("Address copied.");
        });
        ImageView qr = new ImageView(qrCode(address.toString()));
        Hyperlink explorer = new Hyperlink("View in explorer");
        explorer.setOnAction(e -> context.showDocument(network.explorerAddressUrl(address)));
        HBox links = new HBox(16, explorer);
        if(network == LitVmNetwork.LITEFORGE_TESTNET) {
            Hyperlink faucet = new Hyperlink("Get free test zkLTC");
            faucet.setOnAction(e -> context.showDocument(LITEFORGE_FAUCET));
            links.getChildren().add(faucet);
        }
        String account = context.link().getSource().getDisplayName() + (context.link().getDerivationPath() == null ? "" : " at " + context.link().getDerivationPath());
        Label note = new Label("Receive " + network.getSymbol() + " or tokens on " + network.getDisplayName() + " (chain " + network.getChainId() + ") at this address. "
                + "The same address works on every EVM network listed here. Account: " + account + ".");
        note.setWrapText(true);
        note.setMaxWidth(620);
        getChildren().addAll(title, new HBox(8, addressField, copy), qr, links, note);
    }

    private static Image qrCode(String text) {
        try {
            BitMatrix matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 160, 160, Map.of(EncodeHintType.MARGIN, 2));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matrix, "PNG", out, new MatrixToImageConfig());
            return new Image(new ByteArrayInputStream(out.toByteArray()));
        } catch(Exception e) {
            return null;
        }
    }
}
