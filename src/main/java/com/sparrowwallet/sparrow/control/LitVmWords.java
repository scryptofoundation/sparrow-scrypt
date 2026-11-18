package com.sparrowwallet.sparrow.control;

import com.sparrowwallet.drongo.evm.Erc20Token;
import com.sparrowwallet.drongo.evm.EvmAddress;
import com.sparrowwallet.drongo.evm.LitVmCheckException;
import com.sparrowwallet.drongo.evm.LitVmNetwork;
import com.sparrowwallet.drongo.evm.LitVmRpcClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The words the LitVM tab uses for a failure. A status line, a row or a label is the wallet speaking, so it holds only
 * sentences written here or in the views: a fixed one chosen by the kind of failure (its type, never its text), or one
 * the app itself threw as {@link Said}. Whatever a server, an explorer or a contract wrote (an error message, a revert
 * reason, a token's symbol in an error) goes to the log only.
 */
final class LitVmWords {
    private static final Logger log = LoggerFactory.getLogger(LitVmWords.class);

    static final String SERVER = "The server";
    static final String EXPLORER = "The explorer";
    static final String SOURCIFY = "Sourcify";

    static final String WRONG_CHAIN = "This server is for another chain.";
    static final String CONTRACT_REFUSED = "The contract refused the call.";
    static final String IN_THE_WALLET = "Something went wrong in the wallet.";
    static final String NOTHING_SENT = "Nothing was sent.";
    static final String LOOK_FIRST = "Check the explorer or History before doing anything else.";

    private LitVmWords() {}

    /**
     * What kind of failure this is, as far as its type tells. REMOTE is any other failure of talking to a service (it
     * was not reached, or its answer could not be used); LOCAL is a failure with no such cause, so the wallet's own.
     */
    enum Kind { WRONG_CHAIN, RATE_LIMITED, TOO_SLOW, CONTRACT_REFUSED, REFUSED, UNREACHABLE, REMOTE, LOCAL }

    /**
     * A failure the app found itself and worded itself (not enough balance, a step still pending). Its message is shown
     * as it is, so it must never be built from a server's or a contract's text.
     */
    static class Said extends IllegalStateException {
        Said(String sentence) {
            super(sentence);
        }
    }

    /**
     * A send whose fate is not known: it carries the network and the transaction's own hash, so the view can keep a
     * notice of it ({@link LitVmNotices}) that outlasts the status line.
     */
    static class Uncertain extends Said {
        private final LitVmNetwork network;
        private final String hash;
        private final LitVmNotices.State state;

        Uncertain(LitVmNetwork network, String hash, LitVmNotices.State state, String sentence) {
            super(sentence);
            this.network = network;
            this.hash = hash;
            this.state = state;
        }

        LitVmNetwork network() {
            return network;
        }

        String hash() {
            return hash;
        }

        LitVmNotices.State state() {
            return state;
        }
    }

    /** A broadcast that failed after the server may have taken the transaction: it is not known to be unsent. */
    static final class MaybeSent extends Uncertain {
        MaybeSent(LitVmNetwork network, String hash, String sentence) {
            super(network, hash, LitVmNotices.State.MAYBE_SENT, sentence);
        }
    }

    /** A transaction that was sent and has no receipt yet: it went out, and it is not a failure to try again. */
    static final class StillPending extends Uncertain {
        StillPending(LitVmNetwork network, String hash, String sentence) {
            super(network, hash, LitVmNotices.State.PENDING, sentence);
        }
    }

    /** A transaction the server answered with an error: not proof that it is not out. */
    static final class NotAccepted extends Uncertain {
        NotAccepted(LitVmNetwork network, String hash, String sentence) {
            super(network, hash, LitVmNotices.State.NOT_ACCEPTED, sentence);
        }
    }

    /** A send of which it is certain that nothing left this process: it was not sent, and there is nothing to look for. */
    static final class NeverSent extends Said {
        NeverSent(String sentence, Throwable cause) {
            super(sentence);
            if(cause != null) {
                initCause(cause);
            }
        }
    }

    /** The uncertain send in a failure or its causes, or null. */
    static Uncertain uncertain(Throwable failure) {
        return find(failure, Uncertain.class);
    }

    /** The kind of a failure, from its type or its causes' types only. A message is never read. */
    static Kind kind(Throwable failure) {
        for(Throwable t = failure; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if(t instanceof LitVmContext.WrongChainException) {
                return Kind.WRONG_CHAIN;
            }
            if(t instanceof LitVmRpcClient.RateLimitedException) {
                return Kind.RATE_LIMITED;
            }
            if(t instanceof LitVmRpcClient.DeadlineException || t instanceof java.net.http.HttpTimeoutException || t instanceof java.net.SocketTimeoutException) {
                return Kind.TOO_SLOW;
            }
        }
        boolean remote = false;
        for(Throwable t = failure; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if(t instanceof LitVmRpcClient.LitVmRpcException answered) {
                return answered.isRevert() ? Kind.CONTRACT_REFUSED : Kind.REFUSED;
            }
            if(t instanceof java.net.ConnectException || t instanceof java.net.UnknownHostException || t instanceof java.net.NoRouteToHostException
                    || t instanceof java.nio.channels.UnresolvedAddressException) {
                return Kind.UNREACHABLE;
            }
            remote |= t instanceof IOException;
        }
        return remote ? Kind.REMOTE : Kind.LOCAL;
    }

    /** The first throwable of a type in a failure and its causes, or null. */
    private static <T extends Throwable> T find(Throwable failure, Class<T> type) {
        for(Throwable t = failure; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if(type.isInstance(t)) {
                return type.cast(t);
            }
        }
        return null;
    }

    /**
     * The sentence for a check the library made itself and that failed: by its reason only. beforeSending is the call
     * site's own knowledge that nothing was signed or sent when the check ran; only then does a bridge or venue check
     * add that nothing was sent.
     */
    static String checkSentence(LitVmCheckException.Reason reason, boolean beforeSending) {
        String sentence = checkSentence(reason);
        boolean stopsASend = reason == LitVmCheckException.Reason.PORTAL_PAUSED || reason == LitVmCheckException.Reason.BRIDGE_MISMATCH
                || reason == LitVmCheckException.Reason.VENUE_MISMATCH;
        return beforeSending && stopsASend ? sentence + " " + NOTHING_SENT : sentence;
    }

    private static String checkSentence(LitVmCheckException.Reason reason) {
        return switch(reason) {
            case CREATION_OVER_GAS_CAP -> "This token needs more gas than one transaction can use on this network. Choose fewer features, or another network.";
            case NO_POOL -> "No pool can fill this trade.";
            case NO_CONTRACT -> "There is no contract at that address.";
            case NOT_A_TOKEN -> "The contract at that address does not answer as a token.";
            case TOKEN_DECIMALS -> "The token uses more decimal places than the wallet supports.";
            case PORTAL_PAUSED -> "The Base portal is paused.";
            case BRIDGE_MISMATCH -> "The bridge contracts do not match what the wallet expects.";
            case VENUE_MISMATCH -> "The swap contracts do not match what the wallet expects.";
        };
    }

    /** The sentence for a failure talking to the network's server. */
    static String sentence(Throwable failure) {
        return sentence(failure, SERVER);
    }

    /**
     * The sentence for a failure: the app's own ({@link Said}) when it threw one, the one for a check the library made
     * itself, or else one fixed sentence for the failure's kind. who names the service that was asked ({@link #SERVER},
     * {@link #EXPLORER}, {@link #SOURCIFY}).
     */
    static String sentence(Throwable failure, String who) {
        return sentence(failure, who, false);
    }

    /** beforeSending: the call site knows nothing was signed or sent when this failed, so the sentence may say so. */
    private static String sentence(Throwable failure, String who, boolean beforeSending) {
        Said said = find(failure, Said.class);
        if(said != null) {
            return said.getMessage();
        }
        LitVmCheckException check = find(failure, LitVmCheckException.class);
        if(check != null) {
            return checkSentence(check.getReason(), beforeSending);
        }
        if(beforeSending && kind(failure) == Kind.LOCAL) {
            return IN_THE_WALLET + " " + NOTHING_SENT;
        }
        return switch(kind(failure)) {
            case WRONG_CHAIN -> WRONG_CHAIN;
            case RATE_LIMITED -> who + " is limiting requests. Try again later.";
            case TOO_SLOW -> who + " took too long.";
            case CONTRACT_REFUSED -> CONTRACT_REFUSED;
            case REFUSED -> who + " refused the request.";
            case UNREACHABLE -> who + " could not be reached.";
            case REMOTE -> who + " could not be reached, or its answer could not be used.";
            case LOCAL -> IN_THE_WALLET;
        };
    }

    /** A status line for a failure: the view's own lead, then the sentence. The failure's own text goes to the log. */
    static String failure(String lead, Throwable failure) {
        return failure(lead, failure, SERVER);
    }

    static String failure(String lead, Throwable failure, String who) {
        log.warn("LitVM: " + lead, failure);
        return lead + " " + sentence(failure, who);
    }

    /**
     * As {@link #failure(String, Throwable)} where the failure came before anything was signed or sent: a fault of the
     * wallet's own then also says that nothing was sent.
     */
    static String failureBeforeSending(String lead, Throwable failure) {
        log.warn("LitVM: " + lead, failure);
        return lead + " " + sentence(failure, SERVER, true);
    }

    /** The status line when preparing a transaction failed: nothing was signed or sent yet. */
    static String prepareFailed(Throwable failure) {
        return failureBeforeSending("Could not prepare the transaction.", failure);
    }

    /**
     * The status line when broadcasting stopped. A transaction that may have gone out is "not confirmed"; one that went
     * out and has no receipt yet, and one the server did not accept, are told in their own words with no lead. None of
     * them is ever "not completed", and the user must look before doing anything else.
     */
    static String broadcastFailed(Throwable failure) {
        Uncertain uncertain = uncertain(failure);
        if(uncertain instanceof StillPending || uncertain instanceof NotAccepted) {
            log.warn("LitVM: " + uncertain.getMessage(), failure);
            return uncertain.getMessage();
        }
        NeverSent neverSent = find(failure, NeverSent.class);
        if(neverSent != null) {
            log.warn("LitVM: " + neverSent.getMessage(), failure);
            return neverSent.getMessage();
        }
        return failure(uncertain != null ? "Not confirmed." : "Not completed.", failure);
    }

    /** A transaction that was sent and has no receipt after the wait, as the failure a flow or Send stops on. */
    static StillPending pending(LitVmNetwork network, String name, String localHash, boolean nextNotSent) {
        return new StillPending(network, localHash, stillPending(name, localHash, nextNotSent));
    }

    /**
     * A transaction that was sent and has no receipt after the wait: the same words whether a view reports it as its
     * result (Send) or stops a flow on it. nextNotSent: a later step of the flow was held back.
     */
    static String stillPending(String name, String localHash, boolean nextNotSent) {
        return name + " was sent and is still pending (" + localHash + ")." + (nextNotSent ? " The next step was not sent." : "") + " " + LOOK_FIRST;
    }

    /** The status line when a network's balances could not be read. */
    static String balancesUnread(LitVmNetwork network, Throwable failure) {
        return failure("Could not read balances on " + network.getDisplayName() + ".", failure);
    }

    /** The status line naming tracked tokens that could not be read, by their own addresses only. */
    static String tokensUnread(List<EvmAddress> contracts) {
        return "Could not read " + (contracts.size() == 1 ? "a tracked token" : contracts.size() + " tracked tokens") + ": "
                + contracts.stream().map(LitVmContext::shortAddress).collect(Collectors.joining(", ")) + ".";
    }

    /** Why a typed amount of a token was not taken, without the token's own name for itself. */
    static String tokenAmount(Erc20Token token) {
        return "Enter an amount with digits only and at most " + token.decimals() + " decimal places.";
    }

    /** What a step is called when it fails: its place in the flow, since its description can hold a token's symbol. */
    static String stepName(int index, int count) {
        return count == 1 ? "The transaction" : "Step " + (index + 1) + " of " + count;
    }

    /**
     * A transaction the server answered with an error. That is not proof it is not out: the server may know it already
     * (sent before, or a nonce already used), or may not be telling the truth, and the answer's own text is not read.
     * So the words claim only what happened here, name the transaction by its own hash, and say to look before sending
     * again; the view treats it as it does a transaction that may have gone out.
     */
    static NotAccepted refused(LitVmNetwork network, String name, String localHash, Throwable failure) {
        log.warn("LitVM: " + name + " was not accepted as " + localHash, failure);
        return new NotAccepted(network, localHash, name + " was not accepted by the server (" + localHash
                + "). It may already be known to the network. Check History or the explorer before sending it again.");
    }

    /**
     * A transaction whose broadcast failed some other way: the server may have taken it, so it is named by its own hash.
     * These words never say to try again, whatever the failure: sending it twice could pay twice.
     */
    static MaybeSent maybeSent(LitVmNetwork network, String name, String localHash, Throwable failure) {
        log.warn("LitVM: " + name + " may have been broadcast as " + localHash, failure);
        String why = switch(kind(failure)) {
            case RATE_LIMITED -> "The server was limiting requests, so its answer is not known.";
            case TOO_SLOW -> "The server took too long to answer.";
            default -> "The server's answer was lost or could not be used.";
        };
        return new MaybeSent(network, localHash, name + " may have been broadcast as " + localHash + ". " + why + " " + LOOK_FIRST);
    }

    /**
     * A transaction of which nothing left this process (the route to the server was refused, the one connection was
     * never made, the chain check before it failed, or its time was spent first): said plainly, with why.
     */
    static NeverSent notSent(String name, boolean laterSteps, Throwable failure) {
        log.warn("LitVM: " + name + " was not sent", failure);
        return new NeverSent(name + " was not sent." + (laterSteps ? " Nor were the later steps." : "") + " " + sentence(failure), failure);
    }

    /**
     * As {@link #notSent} when a notice of the same transaction already stood before this try: an earlier try signed
     * the same bytes (signing gives the same hash for the same nonce), and what became of that one is still not known.
     * Only this try is said not to have been sent.
     */
    static NeverSent notSentThisTime(String name, boolean laterSteps, String localHash, Throwable failure) {
        log.warn("LitVM: " + name + " was not sent this time; an earlier try may be out as " + localHash, failure);
        return new NeverSent(name + " was not sent this time." + (laterSteps ? " Nor were the later steps." : "") + " " + sentence(failure)
                + " An earlier try with the same hash (" + localHash + ") may still go through. " + LOOK_FIRST, failure);
    }

    /** A pre-signed step whose simulation failed once the step before it was mined. */
    static Said wouldFail(String name, Throwable failure) {
        log.warn("LitVM: " + name + " would fail", failure);
        return new Said(name + " would fail, so it was not sent. " + sentence(failure));
    }

    /** Keeps a failure's own text in the log when the screen shows none of it. */
    static void logOnly(String what, Throwable failure) {
        log.warn("LitVM: " + what, failure);
    }
}
