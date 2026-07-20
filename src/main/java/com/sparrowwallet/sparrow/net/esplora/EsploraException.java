package com.sparrowwallet.sparrow.net.esplora;

public class EsploraException extends Exception {
    public EsploraException(String message) {
        super(message);
    }

    public EsploraException(String message, Throwable cause) {
        super(message, cause);
    }
}
