package com.sparrowwallet.sparrow.net.esplora;

import com.google.common.net.HostAndPort;
import com.sparrowwallet.sparrow.net.HttpClientService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Esplora through a proxy: with a SOCKS proxy configured, every request must go through it, and the server's
 * hostname must be resolved by the proxy (sent as a name, never as a locally resolved IP - a local DNS lookup would
 * leak which explorer, and so which coin, the user talks to). The test runs its own minimal SOCKS4a/5 proxy on
 * localhost that records each destination it is asked for and relays to the real Pepecoin testnet Esplora server.
 */
public class EsploraProxyLiveTest {
    private static final String SERVER = "https://pepe-testnet-api.nintondo.io";
    private static final String HOST = "pepe-testnet-api.nintondo.io";

    @Test
    public void requestsGoThroughTheProxyWithRemoteDns() throws Exception {
        List<String> destinations = new CopyOnWriteArrayList<>();
        try(ServerSocket proxy = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            Thread acceptor = new Thread(() -> acceptLoop(proxy, destinations), "TestSocksProxy");
            acceptor.setDaemon(true);
            acceptor.start();

            HttpClientService proxied = new HttpClientService(HostAndPort.fromParts("127.0.0.1", proxy.getLocalPort()));
            try {
                EsploraClient client = new EsploraClient(SERVER, proxied);
                Assertions.assertTrue(client.isProxied());

                int tip;
                try {
                    tip = client.getTipHeight();
                } catch(EsploraException e) {
                    Assumptions.abort(SERVER + " unavailable through the test proxy: " + e.getMessage());
                    return;
                }
                Assertions.assertTrue(tip > 1_000_000, "Pepecoin testnet tip " + tip);
                //A text body that is not JSON must come back raw, as the direct client returns it
                String genesis = client.getBlockHash(0);
                Assertions.assertEquals("f9f4ea4ae7f6ea4c55040ede2019ba0a53e262f46ec9bce3dcda2cb11f96fc52", genesis);
                //An error status must surface as an EsploraException with the server's message, not be swallowed
                Assertions.assertThrows(EsploraException.class, () -> client.getTransactionHex("00".repeat(32)));
                //A broadcast through the proxy must reach the node and come back with its reject reason
                EsploraException rejected = Assertions.assertThrows(EsploraException.class, () -> client.broadcast("00"));
                System.out.println("tip " + tip + "; broadcast rejected as expected: " + rejected.getMessage());
            } finally {
                proxied.stop();
            }
        }

        System.out.println("proxy destinations: " + destinations);
        Assertions.assertFalse(destinations.isEmpty(), "no request went through the proxy");
        for(String destination : destinations) {
            Assertions.assertEquals(HOST + ":443", destination, "the proxy must receive the hostname, never a locally resolved IP");
        }
    }

    private static void acceptLoop(ServerSocket proxy, List<String> destinations) {
        while(!proxy.isClosed()) {
            try {
                Socket client = proxy.accept();
                Thread handler = new Thread(() -> handle(client, destinations), "TestSocksProxyConn");
                handler.setDaemon(true);
                handler.start();
            } catch(IOException e) {
                return;
            }
        }
    }

    /** Minimal SOCKS4/4a and SOCKS5 (no auth, CONNECT only). Records "host:port" exactly as the client sent it. */
    private static void handle(Socket client, List<String> destinations) {
        try(client) {
            DataInputStream in = new DataInputStream(client.getInputStream());
            OutputStream out = client.getOutputStream();
            int version = in.readUnsignedByte();
            String host;
            int port;
            if(version == 4) {
                in.readUnsignedByte(); //command
                port = in.readUnsignedShort();
                byte[] ip = in.readNBytes(4);
                readNullTerminated(in); //user id
                boolean socks4a = ip[0] == 0 && ip[1] == 0 && ip[2] == 0 && ip[3] != 0;
                host = socks4a ? readNullTerminated(in) : InetAddress.getByAddress(ip).getHostAddress();
                out.write(new byte[]{0, 0x5A, 0, 0, 0, 0, 0, 0});
            } else if(version == 5) {
                int methods = in.readUnsignedByte();
                in.readNBytes(methods);
                out.write(new byte[]{5, 0});
                in.readUnsignedByte(); //version
                in.readUnsignedByte(); //command
                in.readUnsignedByte(); //reserved
                int addressType = in.readUnsignedByte();
                if(addressType == 3) {
                    host = new String(in.readNBytes(in.readUnsignedByte()), StandardCharsets.US_ASCII);
                } else {
                    host = InetAddress.getByAddress(in.readNBytes(addressType == 4 ? 16 : 4)).getHostAddress();
                }
                port = in.readUnsignedShort();
                out.write(new byte[]{5, 0, 0, 1, 0, 0, 0, 0, 0, 0});
            } else {
                return;
            }
            out.flush();
            destinations.add(host + ":" + port);

            try(Socket upstream = new Socket(host, port)) {
                Thread up = new Thread(() -> pipe(in, upstream), "TestSocksProxyUp");
                up.setDaemon(true);
                up.start();
                pipe(upstream.getInputStream(), client);
            }
        } catch(IOException e) {
            //connection closed
        }
    }

    private static void pipe(InputStream from, Socket to) {
        try {
            from.transferTo(to.getOutputStream());
        } catch(IOException e) {
            //closed
        }
    }

    private static String readNullTerminated(DataInputStream in) throws IOException {
        StringBuilder value = new StringBuilder();
        for(int b = in.readUnsignedByte(); b != 0; b = in.readUnsignedByte()) {
            value.append((char)b);
        }
        return value.toString();
    }
}
