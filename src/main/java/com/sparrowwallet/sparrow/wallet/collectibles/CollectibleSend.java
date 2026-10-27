package com.sparrowwallet.sparrow.wallet.collectibles;

import com.sparrowwallet.drongo.address.Address;
import com.sparrowwallet.drongo.address.InvalidAddressException;
import com.sparrowwallet.drongo.ordinals.CollectibleTransactions;
import com.sparrowwallet.drongo.protocol.ScriptType;
import com.sparrowwallet.drongo.wallet.BlockTransactionHashIndex;
import com.sparrowwallet.drongo.wallet.CoinbaseTxoFilter;
import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.drongo.wallet.WalletNode;
import com.sparrowwallet.sparrow.net.ord.OrdOutput;
import com.sparrowwallet.sparrow.net.ord.RuneBalance;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;

/**
 * The choices behind sending an inscription or runes, kept apart from the dialogs so they can be tested: which outputs
 * may pay the fee, which output holds the inscription, which outputs supply the runes, and whether a recipient can
 * safely receive them.
 */
public final class CollectibleSend {
    //An inscription deeper than this into a larger output would take the whole output's LTC with it
    static final long LARGE_OUTPUT = 20_000;

    private CollectibleSend() {}

    /**
     * Outputs that may pay the fee: shown clean by the last check, with no status of any kind (not protected, released or
     * frozen) and not an immature coinbase. None at all when checks are off or have not run, since an unchecked output
     * could carry an inscription that the fee or change would take.
     */
    public static List<CollectibleTransactions.Pinned> feeCandidates(Wallet wallet, CollectiblesManager.Snapshot snapshot) {
        if(snapshot == null || !snapshot.enabled()) {
            return List.of();
        }
        CoinbaseTxoFilter coinbase = new CoinbaseTxoFilter(wallet);
        List<CollectibleTransactions.Pinned> candidates = new ArrayList<>();
        for(Map.Entry<BlockTransactionHashIndex, WalletNode> entry : wallet.getWalletUtxos().entrySet()) {
            BlockTransactionHashIndex utxo = entry.getKey();
            if(utxo.getStatus() == null && snapshot.states().get(utxo.toString()) == CollectibleState.CLEAN && coinbase.isEligible(utxo)) {
                candidates.add(new CollectibleTransactions.Pinned(utxo, entry.getValue()));
            }
        }
        return candidates;
    }

    /** The wallet output at an inscription's satpoint (txid:vout:offset). */
    public static CollectibleTransactions.Pinned inscribedInput(Wallet wallet, String satpoint) {
        String[] parts = satpoint == null ? new String[0] : satpoint.split(":");
        if(parts.length != 3 || !parts[0].matches("[0-9a-f]{64}") || !parts[1].matches("\\d{1,10}") || !parts[2].matches("\\d{1,19}")) {
            throw new IllegalArgumentException("Unexpected inscription location " + satpoint);
        }
        long offset = Long.parseLong(parts[2]);
        for(Map.Entry<BlockTransactionHashIndex, WalletNode> entry : wallet.getWalletUtxos().entrySet()) {
            BlockTransactionHashIndex utxo = entry.getKey();
            if(utxo.getHash().toString().equals(parts[0]) && utxo.getIndex() == Long.parseLong(parts[1])) {
                if(offset > 0 && utxo.getValue() > LARGE_OUTPUT) {
                    throw new IllegalArgumentException("The inscription sits " + offset + " litoshis into an output of " + utxo.getValue()
                            + " litoshis. Sending it would send the whole output; splitting it off first is not supported yet.");
                }
                return new CollectibleTransactions.Pinned(utxo, entry.getValue());
            }
        }
        throw new IllegalArgumentException("The inscription is not in an unspent output of this wallet");
    }

    /** Outputs holding enough of the rune, largest first. Outputs that also hold an inscription are never used. */
    public static List<CollectibleTransactions.Pinned> runeInputs(Wallet wallet, CollectiblesManager.Snapshot snapshot, String runeName, BigInteger amount) {
        if(amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Enter an amount above zero");
        }
        record Holding(CollectibleTransactions.Pinned pinned, BigInteger amount) {}
        List<Holding> holdings = new ArrayList<>();
        for(Map.Entry<BlockTransactionHashIndex, WalletNode> entry : wallet.getWalletUtxos().entrySet()) {
            OrdOutput answer = snapshot == null ? null : snapshot.answers().get(entry.getKey().toString());
            RuneBalance balance = answer == null ? null : answer.runes().get(runeName);
            if(balance != null && answer.inscriptions().isEmpty() && !answer.unlisted()) {
                holdings.add(new Holding(new CollectibleTransactions.Pinned(entry.getKey(), entry.getValue()), balance.amount()));
            }
        }
        holdings.sort(Comparator.comparing(Holding::amount).reversed());
        List<CollectibleTransactions.Pinned> picked = new ArrayList<>();
        BigInteger total = BigInteger.ZERO;
        for(Holding holding : holdings) {
            if(total.compareTo(amount) >= 0) {
                break;
            }
            picked.add(holding.pinned());
            total = total.add(holding.amount());
        }
        if(total.compareTo(amount) < 0) {
            throw new IllegalArgumentException("This wallet holds less of " + runeName + " than that in outputs without inscriptions");
        }
        return picked;
    }

    /** Why the address cannot receive a collectible from this wallet, or empty. */
    public static Optional<String> recipientProblem(String text) {
        Address address;
        try {
            address = Address.fromString(text == null ? "" : text.trim());
        } catch(InvalidAddressException | RuntimeException e) {
            return Optional.of("Not a Litecoin address");
        }
        if(address.getScriptType() == ScriptType.MWEB) {
            return Optional.of("Inscriptions and runes cannot go to an MWEB address: ord does not follow coins into MWEB, so they would be lost");
        }
        return Optional.empty();
    }

    /** A caution for a recipient that may not be an inscription-aware wallet, or empty. */
    public static Optional<String> recipientWarning(Address address) {
        if(address.getScriptType() != ScriptType.P2TR) {
            return Optional.of("This is not a taproot address. Make sure the receiving wallet keeps track of inscriptions and runes, or it may spend them as plain LTC.");
        }
        return Optional.empty();
    }

    /** A rune amount as typed (whole units with up to divisibility decimals) in base units. */
    public static BigInteger toBaseUnits(String text, int divisibility) {
        String t = text == null ? "" : text.trim().replace(",", "");
        if(!t.matches("\\d{1,40}(\\.\\d{1,38})?")) {
            throw new IllegalArgumentException("Not an amount: " + t);
        }
        BigDecimal value = new BigDecimal(t).movePointRight(divisibility);
        if(value.stripTrailingZeros().scale() > 0) {
            throw new IllegalArgumentException("This rune has at most " + divisibility + " decimal places");
        }
        return value.toBigIntegerExact();
    }
}
