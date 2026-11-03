package com.sparrowwallet.sparrow.net.ord;

import com.sparrowwallet.drongo.Coin;
import com.sparrowwallet.drongo.Network;

/**
 * Genesis block hashes (display order, lowercase hex) of the coins whose ord 0.20 servers name only the network, so the
 * genesis block tells the coin. Each value is the hashGenesisBlock assert in that chain's chainparams.cpp:
 * Dogecoin dogecoin/dogecoin master lines 160, 320, 450; Pepecoin pepecoinppc/pepecoin master lines 160, 291, 400;
 * Bellscoin BellsChain/bellscoin (Bells Core v3) src/kernel/chainparams.cpp lines 170, 281, 522; Junkcoin Junkcoin-Foundation/junkcoin-core
 * @0e5279a lines 175, 364.
 * Where mainnet and testnet share a genesis (Bellscoin, Junkcoin) the genesis names the coin and the status names the
 * network. Dingocoin shares Dogecoin's genesis, so a Dingocoin server would pass for Dogecoin; Dingocoin has no
 * inscriptions (its NFT links pay platform-held addresses), so no such server exists, and it is not listed here.
 */
final class ChainGenesis {
    private ChainGenesis() {
    }

    /** The coin's genesis hash on the network, or null when it is not known (the server is then refused). */
    static String hash(Coin coin, Network network) {
        return switch(coin) {
            case DOGECOIN -> switch(network) {
                case MAINNET -> "1a91e3dace36e2be3bf030a65679fe821aa1d6ef92e7c9902eb318182c355691";
                case TESTNET -> "bb0a78264637406b6360aad926284d544d7049f45189db5664f3c4d07350559e";
                case REGTEST -> "3d2160a3b5dc4a9d62e7e66a295f70313ac808440ef7400d6c0772171ce973a5";
                default -> null;
            };
            case PEPECOIN -> switch(network) {
                case MAINNET -> "37981c0c48b8d48965376c8a42ece9a0838daadb93ff975cb091f57f8c2a5faa";
                case TESTNET -> "f9f4ea4ae7f6ea4c55040ede2019ba0a53e262f46ec9bce3dcda2cb11f96fc52";
                case REGTEST -> "770975b0f98319520694563de107ff94fd501c0d1c16f3a405868faf36b51c28";
                default -> null;
            };
            case BELLSCOIN -> switch(network) {
                case MAINNET, TESTNET -> "e5be24df57c43a82d15c2f06bda961296948f8f8eb48501bed1efb929afe0698";
                case REGTEST -> "f97be01b640a39ac10c75da8d749bed0b065f25d9b28f51fe8070a6cdf976e1a";
                default -> null;
            };
            //Junkcoin's regtest has no genesis assert in chainparams.cpp, and the app offers Junkcoin on mainnet and testnet only
            case JUNKCOIN -> switch(network) {
                case MAINNET, TESTNET -> "a2effa738145e377e08a61d76179c21703e13e48910b30a2a87f0dfe794b64c6";
                default -> null;
            };
            default -> null;
        };
    }
}
