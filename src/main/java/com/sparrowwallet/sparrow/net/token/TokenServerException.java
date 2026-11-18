package com.sparrowwallet.sparrow.net.token;

import java.io.IOException;

/** The token server answered, but with an error. */
public class TokenServerException extends IOException {
    public TokenServerException(String message) {
        super(message);
    }
}
