package com.sparrowwallet.sparrow.control;

import com.google.gson.Gson;
import com.sparrowwallet.drongo.evm.Erc721Collection;
import com.sparrowwallet.drongo.evm.EvmAddress;
import com.sparrowwallet.drongo.evm.KnownCollections;
import com.sparrowwallet.drongo.evm.LitVmNetwork;
import com.sparrowwallet.drongo.evm.Nft;
import com.sparrowwallet.drongo.evm.NftMetadata;
import com.sparrowwallet.sparrow.io.Config;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class LitVmCollectiblesTest {
    private static final LitVmNetwork LITEFORGE = LitVmNetwork.LITEFORGE_TESTNET;
    private static final EvmAddress BADGE = EvmAddress.fromString("0x3eEd9a4c9AD8a1Ac42BDb9eEE6079c75C8fd51B3");
    private static final EvmAddress OTHER = EvmAddress.fromString("0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266");
    private static final EvmAddress THIRD = EvmAddress.fromString("0x70997970C51812dc3A010C7d01b50e0d17dc79C8");

    private List<String> saved;

    @BeforeEach
    void save() {
        //The tests' config lives in build/test-home (sparrow-ltc.home), and the singleton is shared: put it back afterwards
        saved = Config.get().getLitVmNfts();
        Config.get().setLitVmNfts(List.of());
    }

    @AfterEach
    void restore() {
        Config.get().setLitVmNfts(saved);
    }

    private static Erc721Collection collection(EvmAddress contract, String name, boolean soulbound) {
        return new Erc721Collection(contract, name, "SYM", false, soulbound);
    }

    private static Nft nft(Erc721Collection collection, long id, NftMetadata metadata) {
        return new Nft(collection, BigInteger.valueOf(id), Optional.ofNullable(metadata));
    }

    private static NftMetadata metadata(String name, String description, List<NftMetadata.Attribute> attributes) {
        return new NftMetadata(name, description, attributes, new NftMetadata.Image.None());
    }

    @Test
    public void watchedCollectionsRoundTrip() throws Exception {
        Assertions.assertTrue(LitVmContext.watchCollection(LITEFORGE, OTHER));
        Assertions.assertFalse(LitVmContext.watchCollection(LITEFORGE, OTHER), "watched once");
        Assertions.assertEquals(List.of(OTHER), LitVmContext.watchedCollections(LITEFORGE));
        Assertions.assertEquals(List.of("4441:" + OTHER), Config.get().getLitVmNfts());

        Method getGson = Config.class.getDeclaredMethod("getGson");
        getGson.setAccessible(true);
        Gson gson = (Gson)getGson.invoke(null);
        Config reloaded = gson.fromJson(gson.toJson(Config.get()), Config.class);
        Assertions.assertEquals(List.of("4441:" + OTHER), reloaded.getLitVmNfts());

        //A config file written before collections existed loads with none watched
        Config old = gson.fromJson("{\"litVmTokens\": [\"4441:" + THIRD + "\"]}", Config.class);
        Assertions.assertEquals(List.of(), old.getLitVmNfts());

        Assertions.assertTrue(LitVmContext.unwatchCollection(LITEFORGE, OTHER));
        Assertions.assertEquals(List.of(), LitVmContext.watchedCollections(LITEFORGE));
        Assertions.assertEquals(List.of(), Config.get().getLitVmNfts());
    }

    @Test
    public void aKnownCollectionIsAlwaysListedAndCannotBeUnwatched() {
        Assertions.assertEquals(KnownCollections.on(LITEFORGE), LitVmContext.collections(LITEFORGE));
        Assertions.assertEquals(BADGE, LitVmContext.collections(LITEFORGE).getFirst());
        Assertions.assertFalse(LitVmContext.unwatchCollection(LITEFORGE, BADGE));
        Assertions.assertFalse(LitVmContext.watchCollection(LITEFORGE, BADGE), "a known collection is not written to the config");
        Assertions.assertEquals(List.of(), Config.get().getLitVmNfts());

        LitVmContext.watchCollection(LITEFORGE, OTHER);
        Assertions.assertEquals(List.of(BADGE, OTHER), LitVmContext.collections(LITEFORGE));
        //A hand-edited config naming the badge does not list it twice, and still cannot remove it
        Config.get().setLitVmNfts(List.of("4441:" + BADGE, "4441:" + OTHER));
        Assertions.assertEquals(List.of(BADGE, OTHER), LitVmContext.collections(LITEFORGE));
        Assertions.assertFalse(LitVmContext.unwatchCollection(LITEFORGE, BADGE));
        Assertions.assertEquals(List.of(BADGE, OTHER), LitVmContext.collections(LITEFORGE));
    }

    @Test
    public void entriesForAnotherChainAreIgnored() {
        Config.get().setLitVmNfts(List.of("11155111:" + OTHER, "4441:" + THIRD, "garbage", "4441:0x123", "x:" + OTHER, OTHER.toString()));
        Assertions.assertEquals(List.of(THIRD), LitVmContext.watchedCollections(LITEFORGE));
        Assertions.assertEquals(List.of(OTHER), LitVmContext.watchedCollections(LitVmNetwork.SEPOLIA));
        Assertions.assertEquals(List.of(OTHER), LitVmContext.collections(LitVmNetwork.SEPOLIA), "no known collections there");
        Assertions.assertEquals(List.of(), LitVmContext.watchedCollections(LitVmNetwork.BASE_SEPOLIA));
        //Unwatching on one chain leaves the other chain's entry
        LitVmContext.unwatchCollection(LitVmNetwork.SEPOLIA, OTHER);
        Assertions.assertEquals(List.of(THIRD), LitVmContext.watchedCollections(LITEFORGE));
        Assertions.assertEquals(List.of(), LitVmContext.watchedCollections(LitVmNetwork.SEPOLIA));
    }

    @Test
    public void aTileShowsTheTokenNameOrItsId() {
        Erc721Collection collection = collection(OTHER, "Things", false);
        Assertions.assertEquals("Thing", CollectiblesPresenter.tileLabel(nft(collection, 7, metadata("Thing", "", List.of()))));
        Assertions.assertEquals("#7", CollectiblesPresenter.tileLabel(nft(collection, 7, metadata("", "", List.of()))));
        Assertions.assertEquals("#7", CollectiblesPresenter.tileLabel(nft(collection, 7, null)));
        //A 168-bit id (the badge's form) is cut in the middle on the tile and shown whole in the detail
        BigInteger big = new BigInteger(1, OTHER.getBytes()).shiftLeft(8).or(BigInteger.ONE);
        Nft badge = new Nft(collection, big, Optional.empty());
        String tile = CollectiblesPresenter.tileLabel(badge);
        Assertions.assertTrue(tile.startsWith("#") && tile.contains("...") && tile.length() <= 20, tile);
        Assertions.assertTrue(tile.endsWith(big.toString().substring(big.toString().length() - 6)), tile);
        Assertions.assertEquals(big.toString(), CollectiblesPresenter.detail(badge, false, null).tokenId());
    }

    @Test
    public void aCollectionIsNamedByItsNameThenSymbolThenAddress() {
        Assertions.assertEquals("Things", CollectiblesPresenter.collectionLabel(collection(OTHER, "Things", false)));
        Assertions.assertEquals("SYM", CollectiblesPresenter.collectionLabel(collection(OTHER, "", false)));
        Assertions.assertEquals(LitVmContext.shortAddress(OTHER), CollectiblesPresenter.collectionLabel(new Erc721Collection(OTHER, "", "", false, false)));
    }

    @Test
    public void knownCollectionsComeFirstThenByNameThenAddressThenTokenId() {
        Erc721Collection badge = collection(BADGE, "Zzz Badge", true);
        Erc721Collection apples = collection(OTHER, "apples", false);
        Erc721Collection applesAgain = collection(THIRD, "Apples", false);
        Erc721Collection bananas = collection(EvmAddress.fromString("0x0000000000000000000000000000000000000001"), "Bananas", false);
        List<Nft> tokens = new ArrayList<>(List.of(nft(bananas, 1, null), nft(apples, 10, null), nft(applesAgain, 1, null), nft(apples, 2, null),
                nft(badge, 3, null), nft(badge, 1, null)));
        List<Nft> ordered = CollectiblesPresenter.order(LITEFORGE, tokens);
        //Same name at two addresses: grouped by address (THIRD 0x7099... before OTHER 0xf39f...), never interleaved
        List<String> shown = ordered.stream().map(n -> n.collection().contract().toString().substring(0, 6) + "#" + n.tokenId()).toList();
        Assertions.assertEquals(List.of("0x3eEd#1", "0x3eEd#3", "0x7099#1", "0xf39F#2", "0xf39F#10", "0x0000#1"), shown);
    }

    @Test
    public void theAppsOwnRowsComeFirstAndTheCollectionsTextIsKeptApart() {
        NftMetadata metadata = metadata("Badge", "Cleared the vault.", List.of(new NftMetadata.Attribute("World", "1"), new NftMetadata.Attribute("", "shiny")));
        CollectiblesPresenter.Detail detail = CollectiblesPresenter.detail(nft(collection(BADGE, "Vault Badge", true), 257, metadata), true, null);
        Assertions.assertEquals(List.of("Token ID: 257", "Contract: " + BADGE, "Built in", "Soulbound: cannot be sent"), texts(detail));
        Assertions.assertEquals(List.of(CollectiblesPresenter.RowKind.TOKEN_ID, CollectiblesPresenter.RowKind.CONTRACT, CollectiblesPresenter.RowKind.MARKER,
                CollectiblesPresenter.RowKind.SOULBOUND), detail.appRows().stream().map(CollectiblesPresenter.AppRow::kind).toList());
        Assertions.assertEquals("Badge", detail.title());
        Assertions.assertEquals("Vault Badge", detail.collection());
        Assertions.assertEquals("Cleared the vault.", detail.description());
        Assertions.assertEquals(List.of(new NftMetadata.Attribute("World", "1"), new NftMetadata.Attribute("", "shiny")), detail.attributes());
        Assertions.assertEquals("257", detail.tokenId());
        Assertions.assertEquals(BADGE, detail.contract());

        CollectiblesPresenter.Detail plain = CollectiblesPresenter.detail(nft(collection(OTHER, "Things", false), 7, null), false, null);
        Assertions.assertEquals("#7", plain.title());
        Assertions.assertEquals(List.of("Token ID: 7", "Contract: " + OTHER, "Added by you"), texts(plain));
        Assertions.assertEquals("From the collection (not checked)", CollectiblesPresenter.FROM_COLLECTION);
    }

    @Test
    public void attributesNamedLikeTheAppsRowsStayInTheCollectionsBox() {
        EvmAddress fake = EvmAddress.fromString("0x000000000000000000000000000000000000dEaD");
        List<NftMetadata.Attribute> spoofs = List.of(new NftMetadata.Attribute("Contract", BADGE.toString()), new NftMetadata.Attribute("Soulbound", "cannot be sent"),
                new NftMetadata.Attribute("Token ID", "1"), new NftMetadata.Attribute("", "Built in"));
        CollectiblesPresenter.Detail detail = CollectiblesPresenter.detail(nft(collection(fake, "Super Litecoin Vault Badge", false), 9, metadata("Badge", "", spoofs)),
                false, "Super Litecoin Vault Badge");
        //The app's rows are built from the token id, the contract and the app's own knowledge only
        Assertions.assertEquals(List.of("Token ID: 9", "Contract: " + fake, "Added by you",
                "Not the built-in Super Litecoin Vault Badge: a different contract with a similar name"), texts(detail));
        //The spoofs are kept as name and value pairs for the box, never joined into a row
        Assertions.assertEquals(spoofs, detail.attributes());
    }

    private static List<String> texts(CollectiblesPresenter.Detail detail) {
        return detail.appRows().stream().map(CollectiblesPresenter.AppRow::text).toList();
    }

    @Test
    public void eachFrameDrawsPicturesUpToItsCommandBudget() {
        //At least one picture a frame, however costly; then as many as fit
        Assertions.assertEquals(0, CollectiblesPresenter.picturesThisFrame(List.of(), 60_000));
        Assertions.assertEquals(1, CollectiblesPresenter.picturesThisFrame(List.of(90_000, 5), 60_000));
        Assertions.assertEquals(1, CollectiblesPresenter.picturesThisFrame(List.of(50_000, 50_000), 60_000));
        Assertions.assertEquals(3, CollectiblesPresenter.picturesThisFrame(List.of(20_000, 20_000, 20_000, 1), 60_000));
        Assertions.assertEquals(8, CollectiblesPresenter.picturesThisFrame(java.util.Collections.nCopies(8, 1), 60_000));
        Assertions.assertEquals(60_000, CollectiblesPresenter.FRAME_COMMANDS);
    }

    @Test
    public void aRefreshStopsAfterThreeMinutes() {
        long second = 1_000_000_000L;
        //Each collection has its minute, cut to what is left of the three
        Assertions.assertEquals(60 * second, LitVmContext.collectionBudgetNanos(0, 0));
        Assertions.assertEquals(60 * second, LitVmContext.collectionBudgetNanos(0, 100 * second));
        Assertions.assertEquals(30 * second, LitVmContext.collectionBudgetNanos(0, 150 * second));
        Assertions.assertEquals(0, LitVmContext.collectionBudgetNanos(0, 180 * second));
        Assertions.assertEquals(0, LitVmContext.collectionBudgetNanos(0, 500 * second));
        //A collection not reached is named with the usual line, and the section says why it stopped
        LitVmContext.CollectionResult notReached = LitVmContext.CollectionResult.notReached(OTHER, false);
        Assertions.assertEquals(LitVmContext.CollectionStatus.NOT_REACHED, notReached.status());
        Assertions.assertEquals(List.of("Could not be read"), CollectiblesPresenter.statusLines(notReached));
        Assertions.assertFalse(CollectiblesPresenter.isEmpty(List.of(notReached)));
        Assertions.assertEquals("Stopped after 3 minutes. Refresh to continue.", CollectiblesPresenter.stoppedLine(List.of(notReached)));
        Assertions.assertNull(CollectiblesPresenter.stoppedLine(List.of(new LitVmContext.CollectionResult(BADGE, true, null, LitVmContext.CollectionStatus.FAILED, List.of(), 0))));
    }

    @Test
    public void refreshesWhileHiddenBecomeOneReadWhenShown() {
        RefreshGate gate = new RefreshGate();
        Assertions.assertTrue(gate.show() > 0);
        Assertions.assertTrue(gate.finish(1));
        //Hidden behind another wallet tab, sub-tab or section: balancesChanged keeps asking, nothing reads
        Assertions.assertFalse(gate.hide());
        for(int i = 0; i < 5; i++) {
            Assertions.assertEquals(-1, gate.request());
        }
        //Shown again: one read for all of them
        int read = gate.show();
        Assertions.assertTrue(read > 1);
        Assertions.assertTrue(gate.finish(read));
        Assertions.assertEquals(-1, gate.next());
    }

    @Test
    public void builtInAndAddedCollectionsAreMarked() {
        Assertions.assertEquals("Built in", CollectiblesPresenter.marker(true));
        Assertions.assertEquals("Added by you", CollectiblesPresenter.marker(false));
        Assertions.assertEquals("Not the built-in Super Litecoin Vault Badge: a different contract with a similar name",
                CollectiblesPresenter.lookalikeWarning("Super Litecoin Vault Badge"));
    }

    @Test
    public void atMostNinetySixTilesAreBuilt() {
        Erc721Collection collection = collection(OTHER, "Things", false);
        List<Nft> tokens = new ArrayList<>();
        for(int i = 0; i < 250; i++) {
            tokens.add(nft(collection, i, null));
        }
        CollectiblesPresenter.TileCap cap = CollectiblesPresenter.capTiles(tokens);
        Assertions.assertEquals(96, CollectiblesPresenter.MAX_TILES);
        Assertions.assertEquals(tokens.subList(0, 96), cap.shown());
        Assertions.assertEquals(154, cap.hidden());
        Assertions.assertEquals("154 more not shown", CollectiblesPresenter.moreLine(cap.hidden()));
        Assertions.assertEquals(0, CollectiblesPresenter.capTiles(tokens.subList(0, 5)).hidden());
    }

    @Test
    public void theCopyIsExact() {
        Assertions.assertEquals("3 more not shown", CollectiblesPresenter.moreLine(3));
        Assertions.assertNull(CollectiblesPresenter.moreLine(0));
        Assertions.assertEquals("2 could not be read; Refresh to try again", CollectiblesPresenter.failedLine(2));
        Assertions.assertNull(CollectiblesPresenter.failedLine(0));
        Assertions.assertEquals("No collectibles in this account.", CollectiblesPresenter.EMPTY);
        Assertions.assertEquals("Could not be read", CollectiblesPresenter.COULD_NOT_BE_READ);
        Assertions.assertEquals("This contract is not an NFT collection.", CollectiblesPresenter.NOT_A_COLLECTION);
        Assertions.assertEquals("This collection cannot be listed without an explorer.", CollectiblesPresenter.NEEDS_EXPLORER);
        Assertions.assertEquals("Soulbound: cannot be sent", CollectiblesPresenter.SOULBOUND);
        Assertions.assertEquals("Add collection", CollectiblesPresenter.ADD_COLLECTION);
    }

    @Test
    public void tokensThatCouldNotBeReadAreSaidAndNotEmpty() {
        LitVmContext.CollectionResult partial = new LitVmContext.CollectionResult(BADGE, true, collection(BADGE, "Badge", true),
                LitVmContext.CollectionStatus.LISTED, List.of(), 2, 3, Optional.empty());
        Assertions.assertEquals(List.of("2 more not shown", "3 could not be read; Refresh to try again"), CollectiblesPresenter.statusLines(partial));
        Assertions.assertFalse(CollectiblesPresenter.isEmpty(List.of(partial)));
        LitVmContext.CollectionResult failed = new LitVmContext.CollectionResult(OTHER, false, null, LitVmContext.CollectionStatus.FAILED, List.of(), 0);
        Assertions.assertEquals(List.of("Could not be read"), CollectiblesPresenter.statusLines(failed));
    }

    @Test
    public void theEmptyStateShowsOnlyWhenEveryCollectionWasReadAndHoldsNothing() {
        LitVmContext.CollectionResult listed = new LitVmContext.CollectionResult(BADGE, true, collection(BADGE, "Badge", true),
                LitVmContext.CollectionStatus.LISTED, List.of(), 0);
        LitVmContext.CollectionResult failed = new LitVmContext.CollectionResult(OTHER, false, null, LitVmContext.CollectionStatus.FAILED, List.of(), 0);
        Assertions.assertTrue(CollectiblesPresenter.isEmpty(List.of(listed)));
        Assertions.assertFalse(CollectiblesPresenter.isEmpty(List.of(listed, failed)));
        LitVmContext.CollectionResult holding = new LitVmContext.CollectionResult(BADGE, true, collection(BADGE, "Badge", true),
                LitVmContext.CollectionStatus.LISTED, List.of(nft(collection(BADGE, "Badge", true), 1, null)), 0);
        Assertions.assertFalse(CollectiblesPresenter.isEmpty(List.of(holding)));
    }

    @Test
    public void messagesFromTheNetworkAreCleanedAndCapped() {
        String hostile = "execution reverted: " + (char)0x202E + "evil" + (char)0x2028 + "line" + (char)0x07 + " " + "x".repeat(300);
        String shown = CollectiblesPresenter.cleanMessage(hostile);
        Assertions.assertTrue(shown.startsWith("execution reverted: evilline x"), shown);
        Assertions.assertEquals(120, shown.length());
        Assertions.assertTrue(shown.chars().noneMatch(c -> c == 0x202E || c == 0x2028 || c == 0x07));
        Assertions.assertEquals("unknown error", CollectiblesPresenter.cleanMessage(null));
        //A surrogate pair is never cut in half at the cap
        String pairs = "a" + "😀".repeat(100);
        Assertions.assertFalse(Character.isHighSurrogate(CollectiblesPresenter.cleanMessage(pairs).charAt(CollectiblesPresenter.cleanMessage(pairs).length() - 1)));
    }

    @Test
    public void malformedConfigEntriesAreIgnored() {
        List<String> entries = new ArrayList<>(java.util.Arrays.asList(null, "4441:" + OTHER, "", ":", "4441:"));
        Config.get().setLitVmNfts(entries);
        Assertions.assertEquals(List.of(OTHER), LitVmContext.watchedCollections(LITEFORGE));
        Assertions.assertFalse(LitVmContext.watchCollection(LITEFORGE, OTHER));
        Assertions.assertTrue(LitVmContext.unwatchCollection(LITEFORGE, OTHER));
        Assertions.assertEquals(List.of(), LitVmContext.watchedCollections(LITEFORGE));
    }

    @Test
    public void readsAreCoalescedAndOnlyWhileShown() {
        RefreshGate gate = new RefreshGate();
        //Not shown: nothing starts, the view is stale
        Assertions.assertEquals(-1, gate.request());
        int first = gate.show();
        Assertions.assertTrue(first > 0, "shown while stale reads");
        //A refresh while one runs is remembered once
        Assertions.assertEquals(-1, gate.request());
        Assertions.assertEquals(-1, gate.request());
        Assertions.assertTrue(gate.finish(first));
        int second = gate.next();
        Assertions.assertTrue(second > first);
        Assertions.assertTrue(gate.finish(second));
        Assertions.assertEquals(-1, gate.next());
        //Hidden while reading: the read is abandoned and its result dropped; shown again reads again
        int third = gate.request();
        Assertions.assertTrue(gate.hide());
        Assertions.assertFalse(gate.finish(third));
        Assertions.assertEquals(-1, gate.request());
        int fifth = gate.show();
        Assertions.assertTrue(fifth > third);
        Assertions.assertTrue(gate.finish(fifth));
        Assertions.assertEquals(-1, gate.next());
        //Hidden and shown again with nothing changed: no read
        Assertions.assertFalse(gate.hide());
        Assertions.assertEquals(-1, gate.show());
    }
}
