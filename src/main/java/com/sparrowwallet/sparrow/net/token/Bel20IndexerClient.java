package com.sparrowwallet.sparrow.net.token;

import com.fasterxml.jackson.databind.JsonNode;
import com.sparrowwallet.sparrow.net.ord.OrdClient;
import com.sparrowwallet.sparrow.net.ord.Transport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.sparrowwallet.sparrow.net.token.TokenParsing.*;

/**
 * DRC-20, PRC-20 and BEL-20 balances from Nintondo's bel-20-indexer, one host per coin and network
 * (https://doge-mainnet-tokens.nintondo.io and so on). The indexer needs no key and answers plain JSON. Its "balance"
 * is the available amount, and a transfer is an outpoint only: the indexer does not name the inscription. Lists are
 * paged by the last item of the previous page, so every list is read to its end, and a list shorter than the total the
 * indexer states is an error. Every answer is parsed into a tree and each row validated; anything else is dropped and
 * counted.
 */
public class Bel20IndexerClient implements TokenClient {
    private static final Logger log = LoggerFactory.getLogger(Bel20IndexerClient.class);
    static final int TOKEN_PAGE = 100;
    static final int TRANSFER_PAGE = 300;
    //Bounds a server that never ends its list; 100 pages is 10,000 tokens or 30,000 transfers at one address
    static final int MAX_PAGES = 100;

    private final Transport transport;
    private final String baseUrl;
    private final RateLimiter limiter;

    public Bel20IndexerClient(Transport transport, String baseUrl, RateLimiter limiter) {
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
        String encoded = encode(requireAddress(address));
        List<TokenBalance> balances = new ArrayList<>();
        int skipped = 0;
        String offset = null;
        for(int page = 0; ; page++) {
            if(page >= MAX_PAGES) {
                throw new TokenServerException("The token server's list of tokens does not end");
            }
            JsonNode list = parse(get(transport, limiter, baseUrl + "/address/" + encoded + "?limit=" + TOKEN_PAGE
                    + (offset == null ? "" : "&offset=" + encode(offset))));
            int count = 0;
            String last = null;
            for(JsonNode row : rows(list)) {
                count++;
                last = row.path("tick").asText("");
                String tick = tick(row.path("tick"));
                String available = amount(row.path("balance"));
                String transferable = amount(row.path("transferable_balance"));
                if(tick == null || available == null || transferable == null) {
                    skipped++;
                    continue;
                }
                balances.add(new TokenBalance(tick, available, transferable));
            }
            if(count < TOKEN_PAGE) {
                break;
            }
            if(last.isEmpty() || last.equals(offset)) {
                throw new TokenServerException("The token server's list of tokens does not advance");
            }
            offset = last;
        }
        logSkipped("balances", skipped);
        return balances;
    }

    @Override
    public List<TransferInscription> transfers(String address, String tick) throws IOException {
        String wanted = requireTick(tick);
        String path = baseUrl + "/address/" + encode(requireAddress(address)) + "/" + encode(wanted) + "/balance?limit=" + TRANSFER_PAGE;
        List<TransferInscription> transfers = new ArrayList<>();
        int skipped = 0;
        int seen = 0;
        String offset = null;
        for(int page = 0; ; page++) {
            if(page >= MAX_PAGES) {
                throw new TokenServerException("The token server's list of transfers does not end");
            }
            JsonNode answer = parse(get(transport, limiter, path + (offset == null ? "" : "&offset=" + encode(offset))));
            if(!answer.isObject() || !wanted.equals(tick(answer.path("tick")))) {
                throw new IOException("Unexpected answer from the token server");
            }
            JsonNode list = answer.path("transfers");
            int count = 0;
            String last = null;
            if(!list.isMissingNode()) {
                for(JsonNode row : rows(list)) {
                    count++;
                    last = row.path("outpoint").asText("");
                    String amount = amount(row.path("amount"));
                    if(!isOutpoint(last) || amount == null) {
                        skipped++;
                        continue;
                    }
                    transfers.add(new TransferInscription(wanted, amount, null, last));
                }
            }
            seen += count;
            JsonNode total = answer.path("transfers_count");
            if(count < TRANSFER_PAGE) {
                if(total.isIntegralNumber() && total.asLong() > seen) {
                    throw new TokenServerException("The token server returned " + seen + " of " + total.asLong() + " transfers");
                }
                break;
            }
            if(last.isEmpty() || last.equals(offset)) {
                throw new TokenServerException("The token server's list of transfers does not advance");
            }
            offset = last;
        }
        logSkipped("transfers", skipped);
        return transfers;
    }

    @Override
    public Optional<TokenInfo> token(String tick) throws IOException {
        String wanted = requireTick(tick);
        JsonNode info;
        try {
            info = parse(get(transport, limiter, baseUrl + "/token?tick=" + encode(wanted)));
        } catch(OrdClient.HttpStatusException e) {
            if(e.getStatus() == 404) {
                return Optional.empty();
            }
            throw e;
        }
        if(!info.isObject() || !wanted.equals(tick(info.path("tick")))) {
            throw new IOException("Unexpected answer from the token server");
        }
        JsonNode dec = info.path("dec");
        Integer decimals = dec.isIntegralNumber() && dec.canConvertToInt() && dec.asInt() >= 0 && dec.asInt() <= 18 ? dec.asInt() : null;
        //The indexer's "supply" is what has been minted so far
        return Optional.of(new TokenInfo(wanted, amount(info.path("max")), amount(info.path("lim")), decimals, amount(info.path("supply"))));
    }

    private void logSkipped(String what, int skipped) {
        if(skipped > 0) {
            log.warn("Token indexer {}: skipped {} malformed rows", what, skipped);
        }
    }
}
