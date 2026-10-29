package com.sparrowwallet.sparrow;

import com.sparrowwallet.drongo.Coin;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.Map;

public class CoinBlockSummariesTest {
    private static Map<Integer, BlockSummary> blocks(int... heights) {
        Map<Integer, BlockSummary> map = new java.util.HashMap<>();
        for(int height : heights) {
            map.put(height, new BlockSummary(height, new Date()));
        }
        return map;
    }

    @Test
    public void eachCoinKeepsItsOwnRecentBlocks() {
        CoinBlockSummaries summaries = new CoinBlockSummaries();
        summaries.update(Coin.LITECOIN, blocks(3000000, 3000001), 2.5, 3000001);
        summaries.update(Coin.DOGECOIN, blocks(5900000), 900d, 5900000);

        Assertions.assertEquals(java.util.Set.of(3000000, 3000001), summaries.get(Coin.LITECOIN).keySet());
        Assertions.assertEquals(java.util.Set.of(5900000), summaries.get(Coin.DOGECOIN).keySet());
        Assertions.assertEquals(2.5, summaries.getNextBlockMedianFeeRate(Coin.LITECOIN));
        Assertions.assertEquals(900d, summaries.getNextBlockMedianFeeRate(Coin.DOGECOIN));
        Assertions.assertTrue(summaries.get(Coin.PEPECOIN).isEmpty());
        Assertions.assertNull(summaries.getNextBlockMedianFeeRate(Coin.PEPECOIN));
    }

    @Test
    public void feeRateUpdatesSetOnlyTheirCoinsMedian() {
        CoinBlockSummaries summaries = new CoinBlockSummaries();
        summaries.setNextBlockMedianFeeRate(Coin.LITECOIN, 3d);
        summaries.setNextBlockMedianFeeRate(Coin.DINGOCOIN, 100000d);
        summaries.setNextBlockMedianFeeRate(Coin.LITECOIN, null);
        Assertions.assertNull(summaries.getNextBlockMedianFeeRate(Coin.LITECOIN));
        Assertions.assertEquals(100000d, summaries.getNextBlockMedianFeeRate(Coin.DINGOCOIN));
    }

    @Test
    public void onlyTheUpdatedCoinIsPrunedToItsLastSixBlocks() {
        CoinBlockSummaries summaries = new CoinBlockSummaries();
        summaries.update(Coin.DOGECOIN, blocks(100, 101), null, 101);
        summaries.update(Coin.LITECOIN, blocks(10, 11, 12, 13, 14, 15, 16), null, 16);

        Assertions.assertEquals(java.util.Set.of(11, 12, 13, 14, 15, 16), summaries.get(Coin.LITECOIN).keySet());
        //Another coin's heights are unrelated to this one's tip and must survive
        Assertions.assertEquals(java.util.Set.of(100, 101), summaries.get(Coin.DOGECOIN).keySet());
    }
}
