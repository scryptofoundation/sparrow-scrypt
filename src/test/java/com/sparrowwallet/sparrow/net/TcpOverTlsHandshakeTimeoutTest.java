package com.sparrowwallet.sparrow.net;

import com.google.common.net.HostAndPort;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * A server that accepts the TCP connection and never answers the TLS handshake (electrum-ltc.bysh.me did this on
 * 2026-11-04) left connect() blocked forever, because the handshake ran before any socket timeout was set. Closing
 * that socket from the switch to another coin then waited on the handshake while holding ElectrumServer's class
 * lock, which kept every other coin from connecting.
 */
public class TcpOverTlsHandshakeTimeoutTest {
    @Test
    public void silentServerFailsTheHandshakeInsteadOfHangingForever() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        try(ServerSocket serverSocket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Thread server = new Thread(() -> {
                try(Socket accepted = serverSocket.accept()) {
                    release.await();
                } catch(Exception e) {
                    //Expected once the test tears down
                }
            });
            server.setDaemon(true);
            server.start();

            TcpOverTlsTransport transport = new TcpOverTlsTransport(HostAndPort.fromParts("127.0.0.1", serverSocket.getLocalPort()));
            transport.handshakeTimeoutMillis = 1000;
            try {
                //If this hangs until the limit instead of throwing, the handshake is unbounded again
                assertTimeoutPreemptively(Duration.ofSeconds(10), () -> assertThrows(ServerException.class, transport::connect));
            } finally {
                release.countDown();
                try {
                    transport.close();
                } catch(IOException e) {
                    //Nothing to close when the handshake failed
                }
            }
        }
    }
}
