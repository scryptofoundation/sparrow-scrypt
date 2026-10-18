package com.sparrowwallet.sparrow.net.cormorant.bitcoind;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.github.arteam.simplejsonrpc.core.domain.ErrorMessage;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ImportMultiResult(boolean success, ErrorMessage error) {
}
