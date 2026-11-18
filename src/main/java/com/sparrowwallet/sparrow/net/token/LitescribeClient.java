package com.sparrowwallet.sparrow.net.token;

import com.fasterxml.jackson.databind.JsonNode;
import com.sparrowwallet.sparrow.net.ord.OrdClient;
import com.sparrowwallet.sparrow.net.ord.Transport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.sparrowwallet.sparrow.net.token.TokenParsing.*;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * LTC-20 balances and transfer inscriptions from Litescribe (Litecoin mainnet only; it has no testnet). Requests go
 * through the same Transport as inscriptions, so the app's proxy and Tor rules and size caps apply. Litescribe answers
 * errors as HTTP 200 with status 0, sends zero amounts as the number 0, wants lowercase tickers and refuses bursts
 * with an HTML 429, so every answer is parsed into a tree and each row validated; anything else is dropped and counted.
 */
public class LitescribeClient implements TokenClient {
    private static final Logger log = LoggerFactory.getLogger(LitescribeClient.class);
    //Litescribe gives deploy figures only with an address; any valid one works, so a fixed public one is sent and no wallet address leaks
    private static final String INFO_ADDRESS = "ltc1qrlu7t8r2pptwv049kyqensq2e64hkfw4g4tkcq";

    private final Transport transport;
    private final String baseUrl;
    private final RateLimiter limiter;

    public LitescribeClient(Transport transport, String baseUrl, RateLimiter limiter) {
        this.transport = transport;
        this.baseUrl = stripSlash(baseUrl);
        this.limiter = limiter;
    }

    @Override
    public String host() {
        return URI.create(baseUrl).getHost();
    }

    @Override
    public List<TokenBalance> balances(String address) throws IOException {
        JsonNode answer = result(get(transport, limiter, baseUrl + "/brc20/tokens?address=" + encode(requireAddress(address))));
        JsonNode list = answer.path("list");
        requireComplete(answer, list);
        List<TokenBalance> balances = new ArrayList<>();
        int skipped = 0;
        for(JsonNode row : rows(list)) {
            String tick = tick(row.path("ticker"));
            String available = amount(row.path("availableBalance"));
            String transferable = amount(row.path("transferableBalance"));
            if(tick == null || available == null || transferable == null) {
                skipped++;
                continue;
            }
            balances.add(new TokenBalance(tick, available, transferable));
        }
        logSkipped("balances", skipped);
        return balances;
    }

    @Override
    public List<TransferInscription> transfers(String address, String tick) throws IOException {
        String wanted = requireTick(tick);
        JsonNode answer = result(get(transport, limiter, baseUrl + "/brc20/transferable-list?address=" + encode(requireAddress(address)) + "&ticker=" + encode(wanted)));
        JsonNode list = answer.path("list");
        requireComplete(answer, list);
        List<TransferInscription> transfers = new ArrayList<>();
        int skipped = 0;
        for(JsonNode row : rows(list)) {
            String rowTick = tick(row.path("ticker"));
            String amount = amount(row.path("amount"));
            String id = row.path("inscriptionId").asText("");
            if(rowTick == null || !rowTick.equals(wanted) || amount == null || !OrdClient.isInscriptionId(id)) {
                skipped++;
                continue;
            }
            transfers.add(new TransferInscription(wanted, amount, id, null));
        }
        logSkipped("transfers", skipped);
        return transfers;
    }

    @Override
    public Optional<TokenInfo> token(String tick) throws IOException {
        String wanted = requireTick(tick);
        JsonNode answer;
        try {
            answer = result(get(transport, limiter, baseUrl + "/brc20/token-summary?address=" + INFO_ADDRESS + "&ticker=" + encode(wanted)));
        } catch(TokenServerException e) {
            if(e.getMessage() != null && e.getMessage().contains("ticker invalid")) {
                return Optional.empty();
            }
            throw e;
        }
        JsonNode info = answer.path("tokenInfo");
        if(!info.isObject()) {
            return Optional.empty();
        }
        //Litescribe's JSON gives neither the mint limit nor the decimals
        return Optional.of(new TokenInfo(wanted, amount(info.path("totalSupply")), null, null, amount(info.path("totalMinted"))));
    }

    /** The result object of a status 1 answer; status 0 or anything unreadable is an error, never an empty answer. */
    private static JsonNode result(String body) throws IOException {
        JsonNode root = parse(body);
        if(root == null || !root.isObject() || !root.path("status").isIntegralNumber()) {
            throw new IOException("Unexpected answer from the token server");
        }
        if(root.path("status").asInt() != 1) {
            String message = root.path("message").asText("");
            throw new TokenServerException(message.isBlank() ? "The token server reported an error" : cleanMessage(message));
        }
        JsonNode result = root.path("result");
        if(!result.isObject()) {
            throw new IOException("Unexpected answer from the token server");
        }
        return result;
    }

    /** Litescribe has no paging parameters, so a list shorter than its own total means the answer is cut short: an error, not a short list. */
    private static void requireComplete(JsonNode answer, JsonNode list) throws IOException {
        JsonNode total = answer.path("total");
        if(list.isArray() && total.isIntegralNumber() && total.asLong() > list.size()) {
            throw new TokenServerException("The token server returned " + list.size() + " of " + total.asLong() + " entries");
        }
    }

    private void logSkipped(String what, int skipped) {
        if(skipped > 0) {
            log.warn("Litescribe {}: skipped {} malformed rows", what, skipped);
        }
    }
}
