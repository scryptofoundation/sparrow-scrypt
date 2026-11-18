package com.sparrowwallet.sparrow.net.token;

/** A transfer inscription holding token amount; exactly one of inscriptionId and outpoint may be null. */
public record TransferInscription(String tick, String amount, String inscriptionId, String outpoint) {
}
