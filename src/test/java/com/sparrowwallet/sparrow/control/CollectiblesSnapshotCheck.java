package com.sparrowwallet.sparrow.control;

import com.sparrowwallet.drongo.evm.*;
import com.sparrowwallet.sparrow.AppServices;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Opt in (NFT_SNAPSHOT_DIR names a folder): draws the Vault Badge and the Collectibles section with the real code on
 * the JavaFX toolkit, writes PNGs there, and checks the badge was actually drawn. With LITVM_LIVE=1 it also reads the
 * test account from LiteForge, read only, and draws the section from that. No window is shown.
 */
public class CollectiblesSnapshotCheck {
    private static final LitVmNetwork LITEFORGE = LitVmNetwork.LITEFORGE_TESTNET;
    private static final EvmAddress BADGE = EvmAddress.fromString("0x3eEd9a4c9AD8a1Ac42BDb9eEE6079c75C8fd51B3");
    private static final EvmAddress HOLDER = EvmAddress.fromString("0x840e41D4484041A43f9307a90389edeB3d111fb3");
    //The badge's colours (its SVG): the outer frame and the light disc
    private static final Color FRAME = Color.web("#0E1E38");
    private static final Color DISC = Color.web("#C8D0DC");

    private static File dir;

    @BeforeAll
    static void startToolkit() {
        String path = System.getenv("NFT_SNAPSHOT_DIR");
        Assumptions.assumeTrue(path != null && !path.isBlank(), "NFT_SNAPSHOT_DIR not set");
        dir = new File(path);
        Assumptions.assumeTrue(dir.isDirectory(), "NFT_SNAPSHOT_DIR is not a folder");
        try {
            Platform.startup(() -> {});
        } catch(IllegalStateException e) {
            //already started
        }
        Platform.setImplicitExit(false);
    }

    private static <T> T onFx(Supplier<T> work) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                result.complete(work.get());
            } catch(Throwable t) {
                result.completeExceptionally(t);
            }
        });
        return result.get(120, TimeUnit.SECONDS);
    }

    private static Optional<NftMetadata> badgeMetadata() throws IOException {
        try(InputStream in = CollectiblesSnapshotCheck.class.getResourceAsStream("vault-badge-world-1-token-uri.txt")) {
            Assertions.assertNotNull(in);
            return NftMetadata.parse(new String(in.readAllBytes(), StandardCharsets.US_ASCII).strip());
        }
    }

    private static void write(WritableImage image, String name) throws IOException {
        Assertions.assertTrue(ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", new File(dir, name)), name);
    }

    @Test
    public void theBadgePictureIsDrawn() throws Exception {
        NftPicture.Plan plan = NftPicture.plan(badgeMetadata(), 1);
        Assertions.assertEquals(NftPicture.Kind.SVG, plan.kind());
        for(double size : new double[] {NftPicture.TILE_SIZE, NftPicture.DETAIL_SIZE}) {
            WritableImage image = onFx(() -> {
                Node node = NftPicture.node(plan, size, null);
                new Scene(new javafx.scene.Group(node));
                return node.snapshot(null, null);
            });
            write(image, size == NftPicture.TILE_SIZE ? "badge-tile.png" : "badge-detail.png");
            assertBadge(image, 0, 0, size);
        }
    }

    /** The badge drawn in the square at (x, y) of this side: several colours, its frame and disc where the SVG puts them, and white text low down. */
    private static void assertBadge(WritableImage image, double x, double y, double side) {
        PixelReader pixels = image.getPixelReader();
        Set<Integer> colours = new HashSet<>();
        for(int px = 0; px < (int)image.getWidth(); px++) {
            for(int py = 0; py < (int)image.getHeight(); py++) {
                colours.add(pixels.getArgb(px, py));
            }
        }
        Assertions.assertTrue(colours.size() > 3, "a blank picture: " + colours.size() + " colours");
        double unit = side / 64;
        assertNear(FRAME, pixels.getColor((int)(x + 1 * unit), (int)(y + 1 * unit)), "the outer frame");
        assertNear(DISC, pixels.getColor((int)(x + 20 * unit), (int)(y + 26 * unit)), "the light disc");
        //"WORLD 1" is white text centred at x = 32, baseline y = 54 (about 7 units tall)
        int white = 0, left = 0, right = 0;
        for(int px = (int)(x + 12 * unit); px < (int)(x + 52 * unit); px++) {
            for(int py = (int)(y + 47 * unit); py < (int)(y + 55 * unit); py++) {
                Color c = pixels.getColor(px, py);
                if(c.getRed() > 0.9 && c.getGreen() > 0.9 && c.getBlue() > 0.9) {
                    white++;
                    if(px < x + 32 * unit) {
                        left++;
                    } else {
                        right++;
                    }
                }
            }
        }
        Assertions.assertTrue(white > 10, "no white text: " + white);
        //Centred: about as much text either side of the middle
        Assertions.assertTrue(Math.abs(left - right) < 0.4 * white, "text not centred: " + left + " left, " + right + " right");
    }

    private static void assertNear(Color expected, Color actual, String what) {
        double distance = Math.abs(expected.getRed() - actual.getRed()) + Math.abs(expected.getGreen() - actual.getGreen())
                + Math.abs(expected.getBlue() - actual.getBlue());
        Assertions.assertTrue(distance < 0.12, what + ": expected " + expected + " got " + actual);
    }

    private static Nft badge(Optional<NftMetadata> metadata) {
        Erc721Collection collection = new Erc721Collection(BADGE, "Super Litecoin Vault Badge", "SLVB", false, true);
        BigInteger world1 = new BigInteger(1, HOLDER.getBytes()).shiftLeft(8).or(BigInteger.ONE);
        return new Nft(collection, world1, metadata);
    }

    /** The real view, filled from these results through the seam its own read uses, with the first tile selected. */
    private static WritableImage view(List<LitVmContext.CollectionResult> results) throws Exception {
        LitVmCollectiblesView.Loaded loaded = LitVmCollectiblesView.load(LITEFORGE, results, 1);
        LitVmCollectiblesView[] shown = new LitVmCollectiblesView[1];
        Scene scene = onFx(() -> {
            LitVmContext context = new LitVmContext(null, text -> {}, () -> null);
            LitVmCollectiblesView view = new LitVmCollectiblesView(context, LITEFORGE);
            shown[0] = view;
            //Not active, so putting it in a scene starts no read
            view.setActive(false);
            //900 wide, tall enough for the whole section (in the app it scrolls)
            Scene made = new Scene(view, 900, 1000);
            made.getStylesheets().add(AppServices.class.getResource("wallet/wallet.css").toExternalForm());
            view.show(loaded);
            view.drawPictures(Integer.MAX_VALUE);
            view.selectTile(0);
            return made;
        });
        //The detail's picture is planned off the FX thread once the tile's plan is released
        long end = System.nanoTime() + 30_000_000_000L;
        while(onFx(() -> shown[0].detailPending()) && System.nanoTime() < end) {
            Thread.sleep(50);
        }
        return onFx(() -> {
            shown[0].applyCss();
            shown[0].layout();
            return scene.snapshot(null);
        });
    }

    @Test
    public void theSectionShowsTheBadge() throws Exception {
        Nft nft = badge(badgeMetadata());
        LitVmContext.CollectionResult result = new LitVmContext.CollectionResult(BADGE, true, nft.collection(), LitVmContext.CollectionStatus.LISTED,
                List.of(nft), 0, 0, Optional.empty());
        WritableImage image = view(List.of(result));
        write(image, "collectibles-view.png");
        //The tile's picture is the first tile, below the title and buttons: find the badge's frame colour there
        PixelReader pixels = image.getPixelReader();
        int frame = 0;
        for(int px = 0; px < 300; px++) {
            for(int py = 0; py < 300; py++) {
                Color c = pixels.getColor(px, py);
                if(Math.abs(c.getRed() - FRAME.getRed()) + Math.abs(c.getGreen() - FRAME.getGreen()) + Math.abs(c.getBlue() - FRAME.getBlue()) < 0.05) {
                    frame++;
                }
            }
        }
        Assertions.assertTrue(frame > 100, "no badge in the tile: " + frame);
    }

    @Test
    public void theSectionShowsTheLiveBadge() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("LITVM_LIVE")), "LITVM_LIVE not set");
        //Read only: the real reader and lister against LiteForge for the throwaway test account
        List<LitVmContext.CollectionResult> results = LitVmContext.readCollectibles(new LitVmRpcClient(LITEFORGE, com.sparrowwallet.drongo.evm.EvmTransport.direct()), HOLDER);
        LitVmContext.CollectionResult badge = results.stream().filter(r -> r.contract().equals(BADGE)).findFirst().orElseThrow();
        Assertions.assertEquals(LitVmContext.CollectionStatus.LISTED, badge.status());
        Assertions.assertFalse(badge.tokens().isEmpty(), "the test account holds the World 1 badge");
        write(view(results), "collectibles-view-live.png");
    }
}
