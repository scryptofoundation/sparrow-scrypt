package com.sparrowwallet.sparrow.wallet.collectibles;

import com.sparrowwallet.drongo.ordinals.RuneName;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class EtchRuleTest {
    private static final RuneName MINIMUM = RuneName.parse("ZYXJUIPLDCNC");

    @Test
    public void nameBelowTheMinimumRefused() {
        String problem = RuneMinting.etchProblem(RuneName.parse("SHORTNAME"), MINIMUM, false).orElseThrow();
        Assertions.assertTrue(problem.contains("ZYXJUIPLDCNC"), problem);
        Assertions.assertTrue(RuneMinting.etchProblem(RuneName.parse("SPARROWSCRYPTS"), MINIMUM, false).isEmpty());
        Assertions.assertTrue(RuneMinting.etchProblem(RuneName.parse("AAAAAAAAAAAAA"), MINIMUM, false).isEmpty());
    }

    @Test
    public void alreadyEtchedRefused() {
        String problem = RuneMinting.etchProblem(RuneName.parse("SPARROWSCRYPTS"), MINIMUM, true).orElseThrow();
        Assertions.assertTrue(problem.contains("already"), problem);
    }

    @Test
    public void reservedNamesRefused() {
        String problem = RuneMinting.etchProblem(RuneName.parse("AAAAAAAAAAAAAAAAAAAAAAAAAAA"), MINIMUM, false).orElseThrow();
        Assertions.assertTrue(problem.contains("reserved"), problem);
        Assertions.assertTrue(RuneMinting.etchProblem(RuneName.parse("ZZZZZZZZZZZZZZZZZZZZZZZZZZ"), MINIMUM, false).isEmpty());
    }

    @Test
    public void frontRunningWarningIsPresent() {
        Assertions.assertTrue(RuneMinting.FRONT_RUNNING_WARNING.contains("someone else can etch"));
        Assertions.assertTrue(RuneMinting.FRONT_RUNNING_WARNING.contains("6"));
    }
}
