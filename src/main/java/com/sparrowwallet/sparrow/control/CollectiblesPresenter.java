package com.sparrowwallet.sparrow.control;

import com.sparrowwallet.drongo.evm.Erc721Collection;
import com.sparrowwallet.drongo.evm.EvmAddress;
import com.sparrowwallet.drongo.evm.KnownCollections;
import com.sparrowwallet.drongo.evm.LitVmNetwork;
import com.sparrowwallet.drongo.evm.Nft;
import com.sparrowwallet.drongo.evm.NftMetadata;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * What the Collectibles section says, without JavaFX: tile and detail text, the order of the tiles, and the fixed copy.
 * Names, descriptions and attributes come from contracts anyone can deploy; they are kept as separate values here so
 * the view can show each in its own node, apart from the app's own rows, and never joins them with the app's words.
 */
final class CollectiblesPresenter {
    static final String SECTION = "Collectibles";
    static final String EMPTY = "No collectibles in this account.";
    static final String ADD_COLLECTION = "Add collection";
    static final String NOT_A_COLLECTION = "This contract is not an NFT collection.";
    static final String NEEDS_EXPLORER = "This collection cannot be listed without an explorer.";
    static final String COULD_NOT_BE_READ = "Could not be read";
    static final String SOULBOUND = "Soulbound: cannot be sent";
    static final String BUILT_IN = "Built in";
    static final String ADDED_BY_YOU = "Added by you";
    static final String FROM_COLLECTION = "From the collection (not checked)";
    /** The most tiles built in one refresh, across every collection, in display order. */
    static final int MAX_TILES = 96;
    static final int MAX_MESSAGE = 120;
    /** The path commands drawn in one frame at most (at least one picture a frame, however costly). */
    static final int FRAME_COMMANDS = 60_000;
    static final String STOPPED = "Stopped after 3 minutes. Refresh to continue.";
    /** The status line when the server limited requests (the phone says the same). */
    static final String RATE_LIMITED = "The LitVM server is limiting requests. Refresh later.";
    static final String WRONG_CHAIN = "This server is for another chain.";
    static final String TOO_SLOW = "The LitVM server took too long.";
    static final String UNREACHABLE = "The LitVM server could not be reached.";

    /**
     * The section's words for a failure: one of four fixed sentences, decided by the failure's type (or its causes')
     * only. Nothing a server or a contract wrote (an error message, a revert reason) ever reaches the screen.
     */
    static String failureSentence(Throwable failure) {
        //The kind is read by the rule every LitVM view shares; the four sentences are this section's own
        return switch(LitVmWords.kind(failure)) {
            case WRONG_CHAIN -> WRONG_CHAIN;
            case RATE_LIMITED -> RATE_LIMITED;
            case TOO_SLOW -> TOO_SLOW;
            default -> UNREACHABLE;
        };
    }
    //Longer token ids are cut in the middle on a tile; the detail shows the whole id
    private static final int MAX_TILE_ID_DIGITS = 14;

    private CollectiblesPresenter() {
    }

    /** The token's name, or "#ID" when it has none. */
    static String tileLabel(Nft nft) {
        String name = nft.metadata().map(NftMetadata::name).orElse("");
        if(!name.isBlank()) {
            return name;
        }
        String id = nft.tokenId().toString();
        if(id.length() > MAX_TILE_ID_DIGITS) {
            id = id.substring(0, 6) + "..." + id.substring(id.length() - 6);
        }
        return "#" + id;
    }

    /** The collection's name, else its symbol, else its short address. */
    static String collectionLabel(Erc721Collection collection) {
        if(!collection.name().isBlank()) {
            return collection.name();
        }
        if(!collection.symbol().isBlank()) {
            return collection.symbol();
        }
        return LitVmContext.shortAddress(collection.contract());
    }

    /** The app's mark for where a collection comes from: built into the wallet, or added by the user. */
    static String marker(boolean known) {
        return known ? BUILT_IN : ADDED_BY_YOU;
    }

    /** The warning for an added collection that takes a built-in one's name; knownName is the wallet's own name for it. */
    static String lookalikeWarning(String knownName) {
        return "Not the built-in " + knownName + ": a different contract with a similar name";
    }

    /** Known collections first, then by collection name (ignoring case), then by contract address, then token id. */
    static List<Nft> order(LitVmNetwork network, List<Nft> tokens) {
        List<Nft> ordered = new ArrayList<>(tokens);
        ordered.sort(Comparator.<Nft, Boolean>comparing(nft -> !KnownCollections.isKnown(network, nft.collection().contract()))
                .thenComparing(nft -> collectionLabel(nft.collection()).toLowerCase(Locale.ROOT))
                .thenComparing(nft -> nft.collection().contract().toString().toLowerCase(Locale.ROOT))
                .thenComparing(Nft::tokenId, BigInteger::compareTo));
        return ordered;
    }

    /** The tiles built, in order, and how many more there are. */
    record TileCap(List<Nft> shown, int hidden) {}

    static TileCap capTiles(List<Nft> ordered) {
        if(ordered.size() <= MAX_TILES) {
            return new TileCap(List.copyOf(ordered), 0);
        }
        return new TileCap(List.copyOf(ordered.subList(0, MAX_TILES)), ordered.size() - MAX_TILES);
    }

    enum RowKind { TOKEN_ID, CONTRACT, MARKER, WARNING, SOULBOUND }

    /** One of the app's own rows: an optional label and its value, both the app's text or values it checked. */
    record AppRow(RowKind kind, String label, String value) {
        String text() {
            return label == null ? value : label + " " + value;
        }
    }

    /**
     * The selected token's detail. The app's own rows (token id, contract, where the collection comes from, a lookalike
     * warning, soulbound) are built from the token id, the contract address and the wallet's own knowledge only; the
     * collection's text (name, collection name, description, attributes) is kept apart, for a box of its own.
     *
     * @param lookalike the wallet's name for the built-in collection this one imitates, or null
     */
    record Detail(String tokenId, EvmAddress contract, boolean known, String lookalike, boolean soulbound,
                  String title, String collection, String description, List<NftMetadata.Attribute> attributes) {
        /** The app's own rows, in the order the view shows them, first: the view builds its rows from these. */
        List<AppRow> appRows() {
            List<AppRow> rows = new ArrayList<>();
            rows.add(new AppRow(RowKind.TOKEN_ID, "Token ID:", tokenId));
            rows.add(new AppRow(RowKind.CONTRACT, "Contract:", contract.toString()));
            rows.add(new AppRow(RowKind.MARKER, null, marker(known)));
            if(lookalike != null) {
                rows.add(new AppRow(RowKind.WARNING, null, lookalikeWarning(lookalike)));
            }
            if(soulbound) {
                rows.add(new AppRow(RowKind.SOULBOUND, null, SOULBOUND));
            }
            return rows;
        }
    }

    static Detail detail(Nft nft, boolean known, String lookalike) {
        NftMetadata metadata = nft.metadata().orElse(null);
        String title = metadata == null || metadata.name().isBlank() ? "#" + nft.tokenId() : metadata.name();
        return new Detail(nft.tokenId().toString(), nft.collection().contract(), known, known ? null : lookalike, nft.collection().soulbound(),
                title, collectionLabel(nft.collection()), metadata == null ? "" : metadata.description(), metadata == null ? List.of() : metadata.attributes());
    }

    /** The line for tokens beyond those listed or shown, or null when there are none. */
    static String moreLine(int more) {
        return more > 0 ? more + " more not shown" : null;
    }

    /** The line for tokens the network did not answer about, or null when there are none. */
    static String failedLine(int failed) {
        return failed > 0 ? failed + " could not be read; Refresh to try again" : null;
    }

    /** What a collection says about itself beside its tiles, if anything. */
    static List<String> statusLines(LitVmContext.CollectionResult result) {
        List<String> lines = new ArrayList<>();
        switch(result.status()) {
            case FAILED, NOT_REACHED, RATE_LIMITED -> lines.add(COULD_NOT_BE_READ);
            case NEEDS_EXPLORER -> lines.add(NEEDS_EXPLORER);
            case NOT_A_COLLECTION -> lines.add(NOT_A_COLLECTION);
            case LISTED -> {
                if(moreLine(result.more()) != null) {
                    lines.add(moreLine(result.more()));
                }
                if(failedLine(result.failed()) != null) {
                    lines.add(failedLine(result.failed()));
                }
            }
        }
        return lines;
    }

    /** The status line when the server limited requests, else when the refresh ran out of time before reaching every collection, or null. */
    static String stoppedLine(List<LitVmContext.CollectionResult> results) {
        if(results.stream().anyMatch(LitVmContext.CollectionResult::rateLimited)) {
            return RATE_LIMITED;
        }
        return results.stream().anyMatch(r -> r.status() == LitVmContext.CollectionStatus.NOT_REACHED) ? STOPPED : null;
    }

    /**
     * How many of the waiting pictures to draw this frame, given each one's cost in path commands: as many as fit the
     * budget, and always at least one when any wait, so a costly picture still gets its own frame.
     */
    static int picturesThisFrame(List<Integer> costs, int budget) {
        int count = 0;
        long spent = 0;
        for(int cost : costs) {
            if(count > 0 && spent + cost > budget) {
                break;
            }
            spent += cost;
            count++;
        }
        return count;
    }

    /** The empty state shows only when every collection was read in full and none holds a token. */
    static boolean isEmpty(List<LitVmContext.CollectionResult> results) {
        return results.stream().allMatch(r -> r.status() == LitVmContext.CollectionStatus.LISTED && r.tokens().isEmpty() && r.more() == 0 && r.failed() == 0);
    }

    /**
     * Text from the network or a contract (an error message, a revert reason) as the status line may show it: control,
     * format (bidirectional overrides, zero-width), line and paragraph separator characters and unpaired surrogates
     * removed, trimmed, and capped at 120 characters without splitting a pair.
     */
    static String cleanMessage(String text) {
        if(text == null) {
            return "unknown error";
        }
        StringBuilder kept = new StringBuilder();
        text.codePoints().filter(c -> {
            int type = Character.getType(c);
            return !Character.isISOControl(c) && type != Character.FORMAT && type != Character.LINE_SEPARATOR && type != Character.PARAGRAPH_SEPARATOR
                    && type != Character.SURROGATE;
        }).forEach(kept::appendCodePoint);
        String clean = kept.toString().strip();
        if(clean.isEmpty()) {
            return "unknown error";
        }
        if(clean.length() <= MAX_MESSAGE) {
            return clean;
        }
        int end = Character.isHighSurrogate(clean.charAt(MAX_MESSAGE - 1)) ? MAX_MESSAGE - 1 : MAX_MESSAGE;
        return clean.substring(0, end);
    }
}
