package com.sparrowwallet.sparrow.wallet.collectibles;

import com.sparrowwallet.drongo.ordinals.RuneId;
import com.sparrowwallet.sparrow.net.ord.OrdClient;
import com.sparrowwallet.sparrow.net.ord.OrdRune;
import com.sparrowwallet.sparrow.net.ord.OrdStatus;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;

public class RuneMintTest {
    private static String fixture(String name) throws IOException {
        try(InputStream in = RuneMintTest.class.getResourceAsStream("/com/sparrowwallet/sparrow/net/ord/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    public void parsesOpenMintTerms() throws IOException {
        OrdRune rune = OrdClient.parseRune(fixture("rune-mintable-regtest.json"));
        Assertions.assertEquals(new RuneId(134, 1), rune.id());
        Assertions.assertEquals("MINTABLE•RUNE•TEST", rune.spacedName());
        Assertions.assertTrue(rune.mintable());
        Assertions.assertEquals(BigInteger.valueOf(100), rune.terms().amount());
        Assertions.assertEquals(BigInteger.TEN, rune.terms().cap());
        Assertions.assertNull(rune.terms().heightStart());
        Assertions.assertEquals(BigInteger.TEN, rune.remainingMints());

        OrdRune closed = OrdClient.parseRune(fixture("rune-regtest.json"));
        Assertions.assertFalse(closed.mintable());
        Assertions.assertNull(closed.terms());
        Assertions.assertEquals(BigInteger.ZERO, closed.remainingMints());
    }

    @Test
    public void parsesMinimumRuneForNextBlock() throws IOException {
        OrdStatus status = OrdClient.parseStatus(fixture("status-regtest.json"));
        Assertions.assertEquals("ZYXJUIPLDCNC", status.minimumRune());
    }

    @Test
    public void unmintableRuneRefused() throws IOException {
        OrdRune closed = OrdClient.parseRune(fixture("rune-regtest.json"));
        String problem = RuneMinting.mintProblem(closed).orElseThrow();
        Assertions.assertTrue(problem.contains("cannot be minted"), problem);
        Assertions.assertTrue(RuneMinting.mintProblem(OrdClient.parseRune(fixture("rune-mintable-regtest.json"))).isEmpty());
    }
}
