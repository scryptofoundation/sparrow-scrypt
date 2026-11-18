package com.sparrowwallet.sparrow.net.esplora;

import com.sparrowwallet.drongo.ordinals.track.BlockFee;
import com.sparrowwallet.drongo.protocol.Sha256Hash;
import com.sparrowwallet.sparrow.net.ElectrumServerRpc;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class EsploraBlockFeesTest {
    private static String resource(String name) throws Exception {
        try(InputStream in = EsploraBlockFeesTest.class.getResourceAsStream(name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String txid(int n) {
        return String.format("%064x", n);
    }

    private static EsploraClient.EsploraTransaction tx(int n, Long fee) {
        return new EsploraClient.EsploraTransaction(txid(n), null, fee, List.of());
    }

    /** Pages of 25 for a block of count transactions, transaction n paying fee n * 10 (the coinbase, 0, pays 0). */
    private static EsploraServerRpc.BlockPages pages(int count) {
        return start -> {
            List<EsploraClient.EsploraTransaction> page = new ArrayList<>();
            for(int n = start; n < Math.min(count, start + 25); n++) {
                page.add(tx(n, n * 10L));
            }
            return page;
        };
    }

    @Test
    public void pagesUntilTheCountSkippingTheCoinbase() throws Exception {
        List<BlockFee> fees = EsploraServerRpc.blockFees(30, pages(30));
        Assertions.assertEquals(29, fees.size());
        Assertions.assertEquals(new BlockFee(Sha256Hash.wrap(txid(1)), 10), fees.getFirst());
        Assertions.assertEquals(new BlockFee(Sha256Hash.wrap(txid(29)), 290), fees.getLast());
    }

    @Test
    public void exactlyOnePage() throws Exception {
        Assertions.assertEquals(24, EsploraServerRpc.blockFees(25, pages(25)).size());
        Assertions.assertEquals(0, EsploraServerRpc.blockFees(1, pages(1)).size());
    }

    @Test
    public void shortPageRefused() {
        Assertions.assertThrows(EsploraException.class, () -> EsploraServerRpc.blockFees(30, pages(28)));
    }

    @Test
    public void missingFeeRefused() {
        EsploraServerRpc.BlockPages noFee = start -> List.of(tx(0, 0L), tx(1, null));
        Assertions.assertThrows(EsploraException.class, () -> EsploraServerRpc.blockFees(2, noFee));
    }

    /** A Junkcoin mainnet block of two transactions from junk-api.s3na.xyz (height 1,206,511), read 2026-11-05. */
    @Test
    public void parsesARealBlock() throws Exception {
        Assertions.assertEquals(2, EsploraClient.parseTxCount(resource("block.json")));
        List<EsploraClient.EsploraTransaction> page = EsploraClient.parseTransactions(resource("block-txs-0.json"));
        Assertions.assertEquals(2, page.size());
        Assertions.assertEquals("05cb0ae76253cfddd09a122d674ec17f6aa5418a232be4c8a76556b2290b6ffa", page.getFirst().txid());
        List<BlockFee> fees = EsploraServerRpc.blockFees(2, start -> page);
        Assertions.assertEquals(List.of(new BlockFee(Sha256Hash.wrap("d5c87f86319e793e34e58d1187978d8013920e91a0629388524907b87b3da96a"), 0)), fees);
    }

    @Test
    public void electrumEndOfBlockMessages() {
        Assertions.assertTrue(ElectrumServerRpc.isPastBlockEnd("no tx at position 3 in block at height 1,000"));
        Assertions.assertTrue(ElectrumServerRpc.isPastBlockEnd("No transaction at position 3 for height 1000"));
        Assertions.assertFalse(ElectrumServerRpc.isPastBlockEnd("daemon error: connection refused"));
        Assertions.assertFalse(ElectrumServerRpc.isPastBlockEnd(null));
    }
}
