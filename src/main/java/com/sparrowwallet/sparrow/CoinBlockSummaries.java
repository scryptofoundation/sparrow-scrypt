package com.sparrowwallet.sparrow;

import com.sparrowwallet.drongo.Coin;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Recent block summaries and the next-block median fee rate, kept per coin. Block heights from different chains are
 * unrelated, so one shared map showed the previous coin's blocks after a coin switch and pruned one coin's blocks
 * against another coin's tip.
 */
public class CoinBlockSummaries {
    //How many blocks below the tip are kept, as before (the Recent Blocks view shows the last few)
    private static final int KEEP_BELOW_TIP = 5;

    private final Map<Coin, Map<Integer, BlockSummary>> summaries = new ConcurrentHashMap<>();
    private final Map<Coin, Double> nextBlockMedianFeeRates = new ConcurrentHashMap<>();

    public Map<Integer, BlockSummary> get(Coin coin) {
        return summaries.computeIfAbsent(coin, c -> new ConcurrentHashMap<>());
    }

    public Double getNextBlockMedianFeeRate(Coin coin) {
        return nextBlockMedianFeeRates.get(coin);
    }

    public void update(Coin coin, Map<Integer, BlockSummary> blocks, Double nextBlockMedianFeeRate, Integer tipHeight) {
        Map<Integer, BlockSummary> coinBlocks = get(coin);
        coinBlocks.putAll(blocks);
        if(tipHeight != null) {
            coinBlocks.keySet().removeIf(height -> tipHeight - height > KEEP_BELOW_TIP);
        }
        setNextBlockMedianFeeRate(coin, nextBlockMedianFeeRate);
    }

    public void setNextBlockMedianFeeRate(Coin coin, Double nextBlockMedianFeeRate) {
        if(nextBlockMedianFeeRate != null) {
            nextBlockMedianFeeRates.put(coin, nextBlockMedianFeeRate);
        } else {
            nextBlockMedianFeeRates.remove(coin);
        }
    }
}
