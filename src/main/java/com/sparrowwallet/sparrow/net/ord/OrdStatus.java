package com.sparrowwallet.sparrow.net.ord;

/** The ord server's status; chain is "unknown" for an ord 0.5 server (it does not name its chain); minimumRune is the shortest rune name that can be etched in the next block (null if not given). */
public record OrdStatus(String chain, boolean runeIndex, long height, String minimumRune) {
}