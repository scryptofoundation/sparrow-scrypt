package com.sparrowwallet.sparrow.net.esplora;

public class EsploraException extends Exception {
    //The HTTP status the server answered with, or -1 when the request did not get that far
    private final int status;

    public EsploraException(String message) {
        this(message, -1);
    }

    public EsploraException(String message, int status) {
        super(message);
        this.status = status;
    }

    public EsploraException(String message, Throwable cause) {
        super(message, cause);
        this.status = -1;
    }

    public int getStatus() {
        return status;
    }
}
