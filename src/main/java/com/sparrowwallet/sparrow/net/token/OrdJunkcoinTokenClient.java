package com.sparrowwallet.sparrow.net.token;

import com.fasterxml.jackson.databind.JsonNode;
import com.sparrowwallet.sparrow.net.ord.OrdClient;
import com.sparrowwallet.sparrow.net.ord.Transport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import static com.sparrowwallet.sparrow.net.token.TokenParsing.*;

/**
 * junk-20 balances and junes from the user's own ord-junkcoin server (Junkcoin has no public index). junk-20 answers
 * come as decimal strings; a transfer inscription is listed with its inscription id and counts only when the indexer
 * marks it valid, meaning unused and within the available balance when it was inscribed. Junes follow the Dunes rules:
 * /junes_on_outputs answers a flat list without saying which output a row belongs to, so one output is asked at a time.
 * A june's id comes from /june/&lt;name&gt;?json=true, which also names its etching transaction. June amounts are the
 * raw integers of the indexer, to be scaled by the divisibility. dunesOnOutputs takes at most MAX_OUTPOINTS_PER_CALL
 * outputs and looks up at most MAX_JUNE_LOOKUPS_PER_CALL distinct junes; the caller chunks. Every answer is untrusted and each row validated.
 */
public class OrdJunkcoinTokenClient implements TokenClient {
    private static final Logger log = LoggerFactory.getLogger(OrdJunkcoinTokenClient.class);
    private static final Pattern JUNE_ID = Pattern.compile("\\d{1,10}:\\d{1,10}");
    private static final Pattern TXID = Pattern.compile("[0-9a-f]{64}");
    /** Most outpoints one dunesOnOutputs call takes; a longer list is refused and the caller chunks it. */
    public static final int MAX_OUTPOINTS_PER_CALL = 200;
    /** Most distinct /june lookups one dunesOnOutputs call makes; junes past it get a null id, so they cannot be sent. */
    public static final int MAX_JUNE_LOOKUPS_PER_CALL = 100;
    private static final int MAX_DECIMALS = 18;

    private final Transport transport;
    private final String baseUrl;
    private final RateLimiter limiter;

    public OrdJunkcoinTokenClient(Transport transport, String baseUrl, RateLimiter limiter) {
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
        JsonNode list = junk20(get(transport, limiter, baseUrl + "/junk20/balance/" + encode(requireAddress(address)) + "?show_utxos=false"));
        List<TokenBalance> balances = new ArrayList<>();
        int skipped = 0;
        for(JsonNode row : rows(list)) {
            String tick = tick(row.path("tick"));
            String available = amount(row.path("available"));
            String transferable = amount(row.path("transferable"));
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
        //No tick filter in the request: the server compares it with the ticker as inscribed, which may differ in case
        JsonNode list = junk20(get(transport, limiter, baseUrl + "/junk20/balance/" + encode(requireAddress(address)) + "?show_utxos=true"));
        List<TransferInscription> transfers = new ArrayList<>();
        int skipped = 0;
        for(JsonNode entry : rows(list)) {
            if(!wanted.equals(tick(entry.path("tick")))) {
                continue;
            }
            JsonNode utxos = entry.path("utxos");
            if(utxos.isNull() || utxos.isMissingNode()) {
                continue;
            }
            for(JsonNode utxo : rows(utxos)) {
                JsonNode junk20 = utxo.path("junk20");
                if(!"transfer".equals(junk20.path("operation").asText(""))) {
                    continue;
                }
                //A transfer the indexer does not list as unused is not spendable token value, and is left out
                if(!junk20.path("valid").isBoolean() || !junk20.path("valid").asBoolean()) {
                    continue;
                }
                String amount = amount(junk20.path("balance"));
                String id = utxo.path("inscription_id").asText("");
                if(amount == null || !OrdClient.isInscriptionId(id)) {
                    skipped++;
                    continue;
                }
                transfers.add(new TransferInscription(wanted, amount, id, null));
            }
        }
        logSkipped("transfers", skipped);
        return transfers;
    }

    @Override
    public Optional<TokenInfo> token(String tick) throws IOException {
        String wanted = requireTick(tick);
        JsonNode info;
        try {
            info = parse(get(transport, limiter, baseUrl + "/junk20/tick/" + encode(wanted)));
        } catch(OrdClient.HttpStatusException e) {
            //An unknown tick is a 400 "No token info found"
            if(e.getStatus() == 400 || e.getStatus() == 404) {
                return Optional.empty();
            }
            throw e;
        }
        if(!info.isObject() || !wanted.equals(tick(info.path("tick")))) {
            throw new IOException("Unexpected answer from the token server");
        }
        JsonNode dec = info.path("decimal");
        if(!dec.isIntegralNumber() || !dec.canConvertToInt() || dec.asInt() < 0 || dec.asInt() > MAX_DECIMALS) {
            return Optional.of(new TokenInfo(wanted, null, null, null, null));
        }
        //The indexer keeps supply, mint limit and minted as integers scaled by 10^decimal
        int decimals = dec.asInt();
        return Optional.of(new TokenInfo(wanted, scaled(info.path("supply"), decimals), scaled(info.path("limit_per_mint"), decimals),
                decimals, scaled(info.path("minted"), decimals)));
    }

    @Override
    public List<DuneBalance> dunesOnOutputs(List<String> outpoints) throws IOException {
        if(outpoints.size() > MAX_OUTPOINTS_PER_CALL) {
            throw new IllegalArgumentException("At most " + MAX_OUTPOINTS_PER_CALL + " outputs per call");
        }
        for(String outpoint : outpoints) {
            if(!isOutpoint(outpoint)) {
                throw new IllegalArgumentException("Invalid outpoint " + outpoint);
            }
        }
        List<DuneBalance> balances = new ArrayList<>();
        //null marks a june whose id the server did not give
        Map<String, String> ids = new HashMap<>();
        int skipped = 0;
        int lookups = 0;
        boolean capped = false;
        for(String outpoint : outpoints) {
            for(JsonNode row : rows(parse(get(transport, limiter, baseUrl + "/junes_on_outputs?outputs=" + encode(outpoint))))) {
                String name = row.path("june").asText("");
                JsonNode pile = row.path("balances");
                String amount = rawAmount(pile.path("amount"));
                JsonNode divisibility = pile.path("divisibility");
                if(!isSpacedName(name) || amount == null || !divisibility.isIntegralNumber() || !divisibility.canConvertToInt()
                        || divisibility.asInt() < 0 || divisibility.asInt() > MAX_DIVISIBILITY) {
                    skipped++;
                    continue;
                }
                if(!ids.containsKey(name)) {
                    if(lookups < MAX_JUNE_LOOKUPS_PER_CALL) {
                        lookups++;
                        ids.put(name, juneId(name));
                    } else {
                        capped = true;
                        ids.put(name, null);
                    }
                }
                balances.add(new DuneBalance(name, ids.get(name), amount, divisibility.asInt(), duneSymbol(pile.path("symbol")), outpoint));
            }
        }
        logSkipped("junes", skipped);
        if(capped) {
            log.warn("ord-junkcoin junes: more than {} distinct junes, the rest have no id", MAX_JUNE_LOOKUPS_PER_CALL);
        }
        return balances;
    }

    @Override
    public Optional<String> duneEtchingTxid(String duneIdOrName) throws IOException {
        boolean byId = duneIdOrName != null && JUNE_ID.matcher(duneIdOrName).matches();
        if(!byId && !isSpacedName(duneIdOrName)) {
            throw new IllegalArgumentException("Invalid june " + duneIdOrName);
        }
        JsonNode june;
        try {
            june = parse(get(transport, limiter, juneUrl(duneIdOrName)));
        } catch(OrdClient.HttpStatusException e) {
            if(e.getStatus() == 404) {
                return Optional.empty();
            }
            throw e;
        }
        JsonNode entry = june.path("entry");
        String etching = entry.path("etching").asText("");
        if(!june.isObject() || !entry.isObject() || !TXID.matcher(etching).matches()) {
            throw new IOException("Unexpected answer from the token server");
        }
        //The answer must be about the june that was asked for
        boolean matches = byId ? duneIdOrName.equals(june.path("id").asText("")) : unspaced(duneIdOrName).equals(unspaced(entry.path("june").asText("")));
        if(!matches) {
            throw new TokenServerException("The token server answered for another june");
        }
        return Optional.of(etching);
    }

    /** The id of a june from its etching lookup, or null when the server does not give a valid one for that name. */
    private String juneId(String spacedName) throws IOException {
        JsonNode june;
        try {
            june = parse(get(transport, limiter, juneUrl(spacedName)));
        } catch(IOException e) {
            //Any failure of the lookup leaves this june without an id; the rest of the read goes on
            return null;
        }
        String id = june.path("id").asText("");
        if(!JUNE_ID.matcher(id).matches() || !unspaced(spacedName).equals(unspaced(june.path("entry").path("june").asText("")))) {
            return null;
        }
        return id;
    }

    private String juneUrl(String idOrName) {
        return baseUrl + "/june/" + encode(idOrName) + "?json=true";
    }

    private static JsonNode junk20(String body) throws IOException {
        JsonNode root = parse(body);
        if(!root.isObject()) {
            throw new IOException("Unexpected answer from the token server");
        }
        return root.path("junk20");
    }

    private static String scaled(JsonNode node, int decimals) {
        if(rawAmount(node) == null) {
            return null;
        }
        BigInteger raw = node.bigIntegerValue();
        return new BigDecimal(raw).movePointLeft(decimals).stripTrailingZeros().toPlainString();
    }

    private void logSkipped(String what, int skipped) {
        if(skipped > 0) {
            log.warn("ord-junkcoin {}: skipped {} malformed rows", what, skipped);
        }
    }
}
