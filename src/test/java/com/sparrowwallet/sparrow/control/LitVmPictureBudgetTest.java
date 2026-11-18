package com.sparrowwallet.sparrow.control;

import com.sparrowwallet.drongo.evm.Erc721Collection;
import com.sparrowwallet.drongo.evm.EvmAddress;
import com.sparrowwallet.drongo.evm.LitVmNetwork;
import com.sparrowwallet.drongo.evm.Nft;
import com.sparrowwallet.drongo.evm.NftMetadata;
import com.sparrowwallet.drongo.evm.SvgSubset;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The pictures of one listing are bounded together, not only one by one: a listing of as many pictures as the section
 * shows, each as costly as one picture may be, holds at most one listing's budget of drawing segments and path commands.
 */
public class LitVmPictureBudgetTest {
    private static final LitVmNetwork LITEFORGE = LitVmNetwork.LITEFORGE_TESTNET;
    private static final EvmAddress WATCHED = EvmAddress.fromString("0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266");
    private static final String SVG_HEAD = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 64 64\">";

    /** A picture close to the most one may be: two paths of the most segments, each filled and stroked (traced twice). */
    private static String maximal(int salt) {
        StringBuilder svg = new StringBuilder(SVG_HEAD);
        for(int path = 0; path < 2; path++) {
            svg.append("<path fill=\"red\" stroke=\"blue\" d=\"M").append(salt % 10).append(" 0");
            for(int i = 0; i < SvgSubset.MAX_PATH_SEGMENTS - 2; i++) {
                svg.append("l1 1");
            }
            svg.append("\"/>");
        }
        svg.append("</svg>");
        Assertions.assertTrue(svg.length() <= SvgSubset.MAX_CHARS, "the picture is longer than a picture may be: " + svg.length());
        return svg.toString();
    }

    private static Optional<NftMetadata> picture(String svg) {
        return Optional.of(new NftMetadata("Name", "", List.of(), new NftMetadata.Image.Svg(svg)));
    }

    @Test
    public void aListingOfMaximalPicturesStaysWithinOneBudget() {
        //One such picture alone is drawn, and is close to the most one picture may cost
        NftPicture.Plan alone = NftPicture.plan(picture(maximal(0)), 1, new NftPicture.Budget(NftPicture.LISTING_UNITS));
        Assertions.assertEquals(NftPicture.Kind.SVG, alone.kind());
        int each = alone.weight();
        Assertions.assertTrue(each > NftPicture.MAX_COMMANDS && each < 2 * NftPicture.MAX_COMMANDS, "one picture: " + each);
        //Laid out for the tile only: the detail is laid out when it opens
        Assertions.assertEquals(1, alone.prepared().size());
        Assertions.assertTrue(alone.preparedAt(NftPicture.pixels(NftPicture.TILE_SIZE, 1)).isPresent());

        Erc721Collection collection = new Erc721Collection(WATCHED, "Hostile", "HST", false, false);
        List<Nft> tokens = new ArrayList<>();
        for(int i = 0; i < CollectiblesPresenter.MAX_TILES; i++) {
            tokens.add(new Nft(collection, BigInteger.valueOf(i), picture(maximal(i))));
        }
        LitVmContext.CollectionResult result = new LitVmContext.CollectionResult(WATCHED, false, collection, LitVmContext.CollectionStatus.LISTED, tokens, 0);
        LitVmCollectiblesView.Loaded loaded = LitVmCollectiblesView.load(LITEFORGE, List.of(result), 1);
        Assertions.assertEquals(CollectiblesPresenter.MAX_TILES, loaded.shown().size());

        long held = 0;
        int drawn = 0;
        int placeholders = 0;
        for(LitVmCollectiblesView.Shown shown : loaded.shown()) {
            NftPicture.Plan plan = shown.picture();
            held += plan.weight();
            if(plan.kind() == NftPicture.Kind.SVG) {
                drawn++;
                Assertions.assertEquals(1, plan.prepared().size());
            } else {
                //Past the budget: the placeholder, which holds nothing
                placeholders++;
                Assertions.assertEquals(NftPicture.NO_PICTURE, plan.placeholder());
                Assertions.assertEquals(0, plan.weight());
                Assertions.assertNull(plan.drawing());
            }
        }
        Assertions.assertTrue(held <= NftPicture.LISTING_UNITS, "the listing holds " + held);
        //Without the budget the same listing would hold every picture, at both sizes
        Assertions.assertTrue((long)each * CollectiblesPresenter.MAX_TILES > 5L * NftPicture.LISTING_UNITS);
        Assertions.assertEquals(NftPicture.LISTING_UNITS / each, drawn);
        Assertions.assertEquals(CollectiblesPresenter.MAX_TILES - drawn, placeholders);

        //Once a tile is drawn the view keeps the plan's name only
        NftPicture.Plan released = alone.released();
        Assertions.assertTrue(released.isReleased());
        Assertions.assertEquals(0, released.weight());
        Assertions.assertEquals(alone.key(), released.key());
        Assertions.assertNull(released.drawing());
        //And the detail is laid out from the metadata when it opens, for its own size only
        NftPicture.Plan detail = NftPicture.planFor(picture(maximal(0)), 1, NftPicture.DETAIL_SIZE);
        Assertions.assertEquals(alone.key(), detail.key());
        Assertions.assertTrue(detail.preparedAt(NftPicture.pixels(NftPicture.DETAIL_SIZE, 1)).isPresent());
        Assertions.assertEquals(1, detail.prepared().size());
    }

    @Test
    public void picturesThatCostNothingAreNotCounted() {
        NftPicture.Budget spent = new NftPicture.Budget(0);
        NftPicture.Plan hosted = NftPicture.plan(Optional.of(new NftMetadata("Name", "", List.of(), new NftMetadata.Image.Hosted("example.com"))), 1, spent);
        Assertions.assertEquals(NftPicture.Kind.HOSTED, hosted.kind());
        Assertions.assertEquals("Picture not shown: hosted at example.com", hosted.placeholder());
        //A small drawing past the budget is the placeholder, and takes nothing
        NftPicture.Plan small = NftPicture.plan(picture(SVG_HEAD + "<rect width=\"8\" height=\"8\"/></svg>"), 1, spent);
        Assertions.assertEquals(NftPicture.Kind.NONE, small.kind());
        Assertions.assertEquals(NftPicture.NO_PICTURE, small.placeholder());
        Assertions.assertEquals(0, spent.left());
        //The same drawing within a budget is drawn, and takes its segments and its commands: 1 shape, 5 commands
        NftPicture.Budget budget = new NftPicture.Budget(100);
        Assertions.assertEquals(NftPicture.Kind.SVG, NftPicture.plan(picture(SVG_HEAD + "<rect width=\"8\" height=\"8\"/></svg>"), 1, budget).kind());
        Assertions.assertEquals(94, budget.left());
    }
}
