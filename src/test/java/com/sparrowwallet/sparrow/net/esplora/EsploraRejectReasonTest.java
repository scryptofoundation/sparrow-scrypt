package com.sparrowwallet.sparrow.net.esplora;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * The reject reason must survive the wrapping each Esplora instance puts around it, because the send flow
 * identifies a failure by the reason's leading text. The bodies here are the real responses these
 * instances returned to a broadcast of an invalid transaction.
 */
public class EsploraRejectReasonTest {
    private final EsploraClient client = new EsploraClient("https://peppool.space/api");

    @Test
    public void mempoolSpaceForkWrapsTheRpcErrorTwice() {
        //peppool.space: the node's RPC error JSON arrives embedded in the message of the instance's own JSON
        String body = "{\"code\":400,\"error\":\"broadcast_failed\",\"message\":\"HTTP request returned status code 500:\\n" +
                "{\\\"result\\\":null,\\\"error\\\":{\\\"code\\\":-22,\\\"message\\\":\\\"TX decode failed\\\"},\\\"id\\\":\\\"6a64254384d7f\\\"}\\n\\n\"}";
        Assertions.assertEquals("TX decode failed", client.extractRejectReason(body));
    }

    @Test
    public void esploraElectrsWrapsTheRpcErrorOnce() {
        //luckyscan.org
        String body = "{\"error\":\"sendrawtransaction RPC error: {\\\"code\\\":-4,\\\"message\\\":\\\"Invalid transaction (could not parse)\\\"}\"}";
        Assertions.assertEquals("Invalid transaction (could not parse)", client.extractRejectReason(body));
    }

    @Test
    public void plainTextErrorIsPassedThrough() {
        //api.nintondo.io returns the Rust debug form, which is not JSON and is already the reason
        String body = "RPC error response: RpcError { code: -22, message: \"TX decode failed. Make sure the tx has at least one input.\", data: None }";
        Assertions.assertEquals(body, client.extractRejectReason(body));
    }

    /**
     * The case the unwrapping exists for: the send flow tests the reason with startsWith, so a fee that is
     * too low has to arrive as the node phrased it, not as JSON.
     */
    @Test
    public void feeAndDustReasonsSurviveForTheSendFlow() {
        String feeBody = "{\"code\":400,\"error\":\"broadcast_failed\",\"message\":\"HTTP request returned status code 500:\\n" +
                "{\\\"result\\\":null,\\\"error\\\":{\\\"code\\\":-26,\\\"message\\\":\\\"min relay fee not met, 250 < 25000\\\"},\\\"id\\\":\\\"1\\\"}\\n\"}";
        Assertions.assertTrue(client.extractRejectReason(feeBody).startsWith("min relay fee not met"),
                "fee reason must lead with the node's own wording");

        String dustBody = "{\"error\":\"sendrawtransaction RPC error: {\\\"code\\\":-26,\\\"message\\\":\\\"dust\\\"}\"}";
        Assertions.assertTrue(client.extractRejectReason(dustBody).startsWith("dust"));
    }

    @Test
    public void emptyBodyStillYieldsAReason() {
        Assertions.assertEquals("Broadcast rejected", client.extractRejectReason(""));
        Assertions.assertEquals("Broadcast rejected", client.extractRejectReason(null));
    }
}
