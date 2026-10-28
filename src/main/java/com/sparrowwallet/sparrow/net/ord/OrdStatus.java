package com.sparrowwallet.sparrow.net.ord;

/** The ord server's status; minimumRune is the shortest rune name that can be etched in the next block (null if not given). */
public record OrdStatus(String chain, boolean runeIndex, long height, String minimumRune) {
}