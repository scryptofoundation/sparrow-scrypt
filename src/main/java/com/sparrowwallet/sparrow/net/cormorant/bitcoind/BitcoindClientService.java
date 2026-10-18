package com.sparrowwallet.sparrow.net.cormorant.bitcoind;

import com.github.arteam.simplejsonrpc.client.JsonRpcParams;
import com.github.arteam.simplejsonrpc.client.ParamsType;
import com.github.arteam.simplejsonrpc.core.annotation.JsonRpcMethod;
import com.github.arteam.simplejsonrpc.core.annotation.JsonRpcOptional;
import com.github.arteam.simplejsonrpc.core.annotation.JsonRpcParam;
import com.github.arteam.simplejsonrpc.core.annotation.JsonRpcService;
import com.sparrowwallet.drongo.protocol.Sha256Hash;
import com.sparrowwallet.sparrow.net.BlockStats;

import java.util.List;
import java.util.Map;
import java.util.Set;

@JsonRpcService
@JsonRpcParams(ParamsType.ARRAY)
public interface BitcoindClientService {
    @JsonRpcMethod("uptime")
    long uptime();

    @JsonRpcMethod("getnetworkinfo")
    NetworkInfo getNetworkInfo();

    @JsonRpcMethod("estimatesmartfee")
    FeeInfo estimateSmartFee(@JsonRpcParam("conf_target") int blocks);

    @JsonRpcMethod("getrawmempool")
    Set<Sha256Hash> getRawMempool();

    @JsonRpcMethod("getrawmempool")
    Map<Sha256Hash, MempoolEntry> getRawMempool(@JsonRpcParam("verbose") boolean verbose);

    @JsonRpcMethod("getmempoolinfo")
    MempoolInfo getMempoolInfo();

    @JsonRpcMethod("getblockchaininfo")
    BlockchainInfo getBlockchainInfo();

    @JsonRpcMethod("getwalletinfo")
    WalletInfo getWalletInfo();

    @JsonRpcMethod("getblockhash")
    String getBlockHash(@JsonRpcParam("height") int height);

    @JsonRpcMethod("getblockheader")
    String getBlockHeader(@JsonRpcParam("blockhash") String blockhash, @JsonRpcParam("verbose") boolean verbose);

    @JsonRpcMethod("getblockheader")
    VerboseBlockHeader getBlockHeader(@JsonRpcParam("blockhash") String blockhash);

    @JsonRpcMethod("getblockstats")
    BlockStats getBlockStats(@JsonRpcParam("blockhash") int hash_or_height);

    @JsonRpcMethod("getrawtransaction")
    Object getRawTransaction(@JsonRpcParam("txid") String txid, @JsonRpcParam("verbose") boolean verbose);

    @JsonRpcMethod("gettransaction")
    Map<String, Object> getTransaction(@JsonRpcParam("txid") String txid, @JsonRpcParam("include_watchonly") boolean includeWatchOnly, @JsonRpcParam("verbose") boolean verbose);

    @JsonRpcMethod("getmempoolentry")
    MempoolEntry getMempoolEntry(@JsonRpcParam("txid") String txid);

    @JsonRpcMethod("listsinceblock")
    ListSinceBlock listSinceBlock(@JsonRpcParam("blockhash") @JsonRpcOptional String blockhash, @JsonRpcParam("target_confirmations") int targetConfirmations,
                                  @JsonRpcParam("include_watchonly") boolean includeWatchOnly, @JsonRpcParam("include_removed") boolean includeRemoved,
                                  @JsonRpcParam("include_change") boolean includeChange);

    @JsonRpcMethod("listwalletdir")
    ListWalletDirResult listWalletDir();

    @JsonRpcMethod("listwallets")
    List<String> listWallets();

    @JsonRpcMethod("createwallet")
    CreateLoadWalletResult createWallet(@JsonRpcParam("wallet_name") String name, @JsonRpcParam("disable_private_keys") boolean disablePrivateKeys, @JsonRpcParam("blank") boolean blank,
                                        @JsonRpcParam("passphrase") String passphrase, @JsonRpcParam("avoid_reuse") boolean avoidReuse, @JsonRpcParam("descriptors") boolean descriptors,
                                        @JsonRpcParam("load_on_startup") boolean loadOnStartup, @JsonRpcParam("external_signer") boolean externalSigner);

    @JsonRpcMethod("loadwallet")
    CreateLoadWalletResult loadWallet(@JsonRpcParam("filename") String name);

    @JsonRpcMethod("loadwallet")
    CreateLoadWalletResult loadWallet(@JsonRpcParam("filename") String name, @JsonRpcParam("load_on_startup") boolean loadOnStartup);

    @JsonRpcMethod("unloadwallet")
    CreateLoadWalletResult unloadWallet(@JsonRpcParam("wallet_name") String name, @JsonRpcParam("load_on_startup") boolean loadOnStartup);

    @JsonRpcMethod("listdescriptors")
    ListDescriptorsResult listDescriptors(@JsonRpcParam("private") boolean listPrivate);

    @JsonRpcMethod("importdescriptors")
    List<ImportDescriptorResult> importDescriptors(@JsonRpcParam("requests") List<ImportDescriptor> importDescriptors);

    @JsonRpcMethod("sendrawtransaction")
    String sendRawTransaction(@JsonRpcParam("hexstring") String rawTx, @JsonRpcParam("maxfeerate") Double maxFeeRate);

    /*
     * Legacy (Bitcoin 0.13/0.14-era) equivalents, for the Scrypto chains whose nodes predate descriptor
     * wallets. Two things differ beyond the missing methods: importmulti takes concrete scriptPubKeys
     * rather than ranged descriptors, and several calls accept fewer positional parameters than modern
     * Core - these nodes reject a request outright when handed extras, so the shorter forms are declared
     * separately rather than passing nulls.
     */

    @JsonRpcMethod("importmulti")
    List<ImportMultiResult> importMulti(@JsonRpcParam("requests") List<ImportMultiRequest> requests, @JsonRpcParam("options") Map<String, Boolean> options);

    //Modern Core adds include_removed (0.18) and include_change (v23); these nodes accept the first three only
    @JsonRpcMethod("listsinceblock")
    ListSinceBlock listSinceBlockLegacy(@JsonRpcParam("blockhash") @JsonRpcOptional String blockhash, @JsonRpcParam("target_confirmations") int targetConfirmations,
                                        @JsonRpcParam("include_watchonly") boolean includeWatchOnly);

    //The verbose parameter arrived in v20
    @JsonRpcMethod("gettransaction")
    Map<String, Object> getTransactionLegacy(@JsonRpcParam("txid") String txid, @JsonRpcParam("include_watchonly") boolean includeWatchOnly);

    //maxfeerate replaced the allowhighfees boolean in v0.19; passing a fee rate to these nodes is a type error
    @JsonRpcMethod("sendrawtransaction")
    String sendRawTransactionLegacy(@JsonRpcParam("hexstring") String rawTx);
}
