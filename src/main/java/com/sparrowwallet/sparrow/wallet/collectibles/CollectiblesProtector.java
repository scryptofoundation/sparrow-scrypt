package com.sparrowwallet.sparrow.wallet.collectibles;

import com.sparrowwallet.drongo.wallet.BlockTransactionHashIndex;
import com.sparrowwallet.drongo.wallet.Status;
import com.sparrowwallet.sparrow.net.ord.OrdOutput;

import java.util.*;

/** Marks unspent outputs that carry an inscription or runes as PROTECTED, and writes the label that says what they carry. */
public final class CollectiblesProtector {
    private static final int MAX_LABEL_LENGTH = 255;

    private CollectiblesProtector() {
    }

    /**
     * Sets PROTECTED on each unspent output classified as carrying whose status is none or FROZEN, returning those
     * changed. A RELEASED output was let go by the user and stays as it is.
     */
    public static List<BlockTransactionHashIndex> apply(Collection<BlockTransactionHashIndex> utxos, Map<String, CollectibleState> states) {
        List<BlockTransactionHashIndex> changed = new ArrayList<>();
        for(BlockTransactionHashIndex utxo : utxos) {
            if(!utxo.isSpent() && states.get(utxo.toString()) == CollectibleState.CARRIES
                    && (utxo.getStatus() == null || utxo.getStatus() == Status.FROZEN)) {
                utxo.setStatus(Status.PROTECTED);
                changed.add(utxo);
            }
        }
        return changed;
    }

    public static String labelPrefix(OrdOutput output) {
        List<String> parts = new ArrayList<>();
        if(output.inscriptions().size() == 1) {
            String id = output.inscriptions().getFirst();
            parts.add("Inscription " + id.substring(0, 8) + "…" + id.substring(id.lastIndexOf('i')));
        } else if(output.inscriptions().size() > 1) {
            parts.add(output.inscriptions().size() + " inscriptions");
        }
        if(!output.runes().isEmpty()) {
            List<String> names = new ArrayList<>(output.runes().keySet());
            Collections.sort(names);
            parts.add("Runes " + names.getFirst() + (names.size() > 1 ? " +" + (names.size() - 1) : ""));
        }
        if(parts.isEmpty()) {
            parts.add("Inscription or runes");
        }
        return String.join(", ", parts);
    }

    /** The prefix in front of whatever the user wrote, cut to the label column's length. */
    public static String newLabel(String userLabel, OrdOutput output) {
        String label = labelPrefix(output) + (userLabel == null || userLabel.isEmpty() ? "" : " " + userLabel);
        return label.length() > MAX_LABEL_LENGTH ? label.substring(0, MAX_LABEL_LENGTH) : label;
    }
}
