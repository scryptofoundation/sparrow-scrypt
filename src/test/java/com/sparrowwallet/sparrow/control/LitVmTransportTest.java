package com.sparrowwallet.sparrow.control;

import com.google.common.net.HostAndPort;
import com.sparrowwallet.drongo.Coin;
import com.sparrowwallet.drongo.crypto.ECKey;
import com.sparrowwallet.drongo.evm.BlockscoutClient;
import com.sparrowwallet.drongo.evm.Eip1559Transaction;
import com.sparrowwallet.drongo.evm.EvmAccount;
import com.sparrowwallet.drongo.evm.EvmAddress;
import com.sparrowwallet.drongo.evm.EvmTransport;
import com.sparrowwallet.drongo.evm.LitVmNetwork;
import com.sparrowwallet.drongo.evm.LitVmRpcClient;
import com.sparrowwallet.drongo.evm.SourcifyClient;
import com.sparrowwallet.sparrow.io.Config;
import com.sparrowwallet.sparrow.io.Server;
import com.sparrowwallet.sparrow.net.ServerType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * Every LitVM request follows the wallet's proxy setting. With a proxy wanted it goes to a SOCKS5 proxy, which is given
 * the server's host name (so the name is not resolved here); with the proxy wanted and not there, or down, nothing
 * connects directly; with none wanted it goes as it always did. Neither way follows a redirect or keeps a cookie.
 *
 * A SOCKS stub, a plain server and a TLS server (with a certificate made for this run) stand in for the proxy and for
 * the servers, on this machine only. The host names end in .test or are the real ones, and none is ever resolved or
 * contacted: the stub answers for all of them.
 */
public class LitVmTransportTest {
    private static final LitVmNetwork LITEFORGE = LitVmNetwork.LITEFORGE_TESTNET;
    private static final EvmAddress CONTRACT = EvmAddress.fromString("0x3eEd9a4c9AD8a1Ac42BDb9eEE6079c75C8fd51B3");
    private static final String HASH = "0x" + "ab".repeat(32);
    private static final String TLS_NAME = "rpc.litvm.test";

    private static KeyStore serverKeys;
    private static KeyStore trust;

    private ServerSocket server;
    private ServerSocket tls;
    private ServerSocket elsewhere;
    private ServerSocket socks;
    private int deadPort;
    //Connections the servers took, and one line for each request they were sent
    private final AtomicInteger serverConnections = new AtomicInteger();
    private final AtomicInteger elsewhereConnections = new AtomicInteger();
    private final List<String> serverRequests = Collections.synchronizedList(new ArrayList<>());
    //Whether a request came with a Cookie header
    private final List<Boolean> cookies = Collections.synchronizedList(new ArrayList<>());
    //When set, a broadcast is answered with a redirect to the server elsewhere
    private volatile boolean redirectBroadcasts;
    //What the SOCKS stub was asked to connect to: "name host:port" when given a host name, "ip a.b.c.d:port" when given an address
    private final List<String> socksRequests = Collections.synchronizedList(new ArrayList<>());
    //Requests that went the direct way, through the transport's own direct delegate
    private final List<URI> direct = Collections.synchronizedList(new ArrayList<>());
    private final AtomicBoolean proxyWanted = new AtomicBoolean(true);

    /** A certificate for rpc.litvm.test, made by the JDK's own keytool for this run, and a trust store holding only it. */
    private static synchronized void certificate() throws Exception {
        if(serverKeys != null) {
            return;
        }
        Path store = Files.createTempFile("litvm-transport-test", ".p12");
        Files.delete(store);
        try {
            boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
            Process keytool = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", windows ? "keytool.exe" : "keytool").toString(), "-genkeypair", "-alias", "test",
                    "-keyalg", "RSA", "-keysize", "2048", "-validity", "2", "-dname", "CN=" + TLS_NAME, "-ext", "SAN=dns:" + TLS_NAME, "-storetype", "PKCS12",
                    "-keystore", store.toString(), "-storepass", "changeit", "-keypass", "changeit").redirectErrorStream(true).start();
            String said = new String(keytool.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            Assertions.assertTrue(keytool.waitFor(60, TimeUnit.SECONDS) && keytool.exitValue() == 0, "keytool: " + said);
            KeyStore keys = KeyStore.getInstance("PKCS12");
            try(InputStream in = Files.newInputStream(store)) {
                keys.load(in, "changeit".toCharArray());
            }
            KeyStore trusted = KeyStore.getInstance("PKCS12");
            trusted.load(null, null);
            trusted.setCertificateEntry("test", keys.getCertificate("test"));
            serverKeys = keys;
            trust = trusted;
        } finally {
            Files.deleteIfExists(store);
        }
    }

    @BeforeEach
    void start() throws Exception {
        certificate();
        server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        accept("server", server, serverConnections, true);
        KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(serverKeys, "changeit".toCharArray());
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keyManagers.getKeyManagers(), null, null);
        tls = context.getServerSocketFactory().createServerSocket(0, 50, InetAddress.getLoopbackAddress());
        accept("tls server", tls, serverConnections, true);
        elsewhere = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        accept("server elsewhere", elsewhere, elsewhereConnections, false);
        socks = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        daemon("socks", () -> {
            while(true) {
                Socket socket = socks.accept();
                daemon("socks connection", () -> proxy(socket));
            }
        });
        try(ServerSocket closed = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            deadPort = closed.getLocalPort();
        }
    }

    @AfterEach
    void stop() throws Exception {
        for(ServerSocket socket : List.of(server, tls, elsewhere, socks)) {
            socket.close();
        }
    }

    private interface Work {
        void run() throws Exception;
    }

    private static void daemon(String name, Work work) {
        Thread thread = new Thread(() -> {
            try {
                work.run();
            } catch(Exception e) {
                //a closed socket ends its thread
            }
        }, "LitVmTransportTest " + name);
        thread.setDaemon(true);
        thread.start();
    }

    private void accept(String name, ServerSocket socket, AtomicInteger connections, boolean answer) {
        daemon(name, () -> {
            while(true) {
                Socket taken = socket.accept();
                connections.incrementAndGet();
                daemon(name + " connection", () -> serve(taken, answer));
            }
        });
    }

    /** One HTTP exchange: a JSON-RPC result for a POST, an explorer-like object for a GET, each with a cookie to keep. */
    private void serve(Socket socket, boolean answers) throws IOException {
        try(socket) {
            InputStream in = socket.getInputStream();
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            int matched = 0;
            while(matched < 4) {
                int b = in.read();
                if(b < 0) {
                    return;
                }
                head.write(b);
                matched = (b == '\r' && matched % 2 == 0) || (b == '\n' && matched % 2 == 1) ? matched + 1 : b == '\r' ? 1 : 0;
            }
            String headers = head.toString(StandardCharsets.ISO_8859_1);
            List<String> lines = List.of(headers.split("\r\n"));
            int length = 0;
            for(String line : lines) {
                if(line.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                    length = Integer.parseInt(line.substring(15).trim());
                }
            }
            String body = new String(in.readNBytes(length), StandardCharsets.UTF_8);
            if(!answers) {
                return;
            }
            String first = lines.getFirst();
            String host = lines.stream().filter(line -> line.toLowerCase(Locale.ROOT).startsWith("host:")).map(line -> line.substring(5).trim()).findFirst().orElse("");
            String method = body.contains("\"method\":\"") ? body.replaceAll("(?s).*\"method\":\"([A-Za-z_]+)\".*", "$1") : "";
            serverRequests.add(first + " host=" + host + (method.isEmpty() ? "" : " " + method));
            cookies.add(lines.stream().anyMatch(line -> line.toLowerCase(Locale.ROOT).startsWith("cookie:")));
            OutputStream out = socket.getOutputStream();
            if(redirectBroadcasts && method.equals("eth_sendRawTransaction")) {
                out.write(("HTTP/1.1 307 Temporary Redirect\r\nLocation: http://127.0.0.1:" + elsewhere.getLocalPort() + "/\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                        .getBytes(StandardCharsets.ISO_8859_1));
                out.flush();
                return;
            }
            String answer = first.startsWith("POST") ? "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":" + (method.equals("eth_getTransactionReceipt") ? "null" : "\"0x1159\"") + "}"
                    : "{\"is_verified\":true,\"match\":\"exact_match\",\"items\":[]}";
            byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nSet-Cookie: id=42; Path=/\r\nContent-Length: " + bytes.length + "\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.ISO_8859_1));
            out.write(bytes);
            out.flush();
        }
    }

    /**
     * A SOCKS proxy that notes what it is asked to connect to (the name as given, or the address), and then connects
     * to the plain or the TLS server here by the port asked for, whatever the host: nothing is resolved, nothing leaves
     * this machine. Any other port is refused once noted.
     */
    private void proxy(Socket client) throws IOException {
        try(client) {
            DataInputStream in = new DataInputStream(client.getInputStream());
            OutputStream out = client.getOutputStream();
            int version = in.readUnsignedByte();
            String target;
            int port;
            boolean five = version == 5;
            if(five) {
                byte[] methods = in.readNBytes(in.readUnsignedByte());
                boolean none = false;
                for(byte method : methods) {
                    none |= method == 0;
                }
                out.write(new byte[] {5, (byte)(none ? 0 : 2)});
                if(!none) {
                    //Username and password, as Tor takes for circuit isolation: any are accepted
                    in.readUnsignedByte();
                    in.readNBytes(in.readUnsignedByte());
                    in.readNBytes(in.readUnsignedByte());
                    out.write(new byte[] {1, 0});
                }
                in.readUnsignedByte();
                in.readUnsignedByte();
                in.readUnsignedByte();
                int type = in.readUnsignedByte();
                if(type == 3) {
                    target = "name " + new String(in.readNBytes(in.readUnsignedByte()), StandardCharsets.US_ASCII);
                } else {
                    target = "ip " + InetAddress.getByAddress(in.readNBytes(type == 1 ? 4 : 16)).getHostAddress();
                }
                port = in.readUnsignedShort();
            } else {
                //SOCKS4, or 4a with the name after the user id
                in.readUnsignedByte();
                port = in.readUnsignedShort();
                byte[] address = in.readNBytes(4);
                while(in.readUnsignedByte() != 0) {
                    //the user id
                }
                if(address[0] == 0 && address[1] == 0 && address[2] == 0 && address[3] != 0) {
                    ByteArrayOutputStream name = new ByteArrayOutputStream();
                    for(int b = in.readUnsignedByte(); b != 0; b = in.readUnsignedByte()) {
                        name.write(b);
                    }
                    target = "name " + name.toString(StandardCharsets.US_ASCII);
                } else {
                    target = "ip " + InetAddress.getByAddress(address).getHostAddress();
                }
            }
            socksRequests.add(target + ":" + port);
            if(port != server.getLocalPort() && port != tls.getLocalPort()) {
                out.write(five ? new byte[] {5, 5, 0, 1, 0, 0, 0, 0, 0, 0} : new byte[] {0, 91, 0, 0, 0, 0, 0, 0});
                return;
            }
            out.write(five ? new byte[] {5, 0, 0, 1, 0, 0, 0, 0, 0, 0} : new byte[] {0, 90, 0, 0, 0, 0, 0, 0});
            out.flush();
            try(Socket upstream = new Socket(InetAddress.getLoopbackAddress(), port)) {
                daemon("socks relay", () -> upstream.getInputStream().transferTo(out));
                client.getInputStream().transferTo(upstream.getOutputStream());
            }
        }
    }

    private EvmTransport noting() {
        return (uri, headers, body, timeout, maxBytes) -> {
            direct.add(uri);
            return EvmTransport.direct().send(uri, headers, body, timeout, maxBytes);
        };
    }

    /** A transport whose proxy, when one is wanted, is the SOCKS stub on this port (null for none to use). */
    private LitVmTransport transport(Integer socksPort) {
        return new LitVmTransport(proxyWanted::get, () -> socksPort == null ? null : HostAndPort.fromParts("127.0.0.1", socksPort), true, noting(), trust);
    }

    /** A server that is only a name: nothing here could reach it but through the proxy. */
    private String namedServer() {
        return "http://" + TLS_NAME + ":" + server.getLocalPort() + "/";
    }

    private String localServer() {
        return "http://127.0.0.1:" + server.getLocalPort() + "/";
    }

    private static LitVmNotices notices() {
        return new LitVmNotices(new LitVmNotices.Store() {
            @Override
            public List<String> load() {
                return List.of();
            }

            @Override
            public boolean save(List<String> entries) {
                return true;
            }
        }, notice -> {}, Runnable::run);
    }

    private static Eip1559Transaction.Signed signedTransfer() {
        Eip1559Transaction tx = new Eip1559Transaction(LITEFORGE.getChainId(), BigInteger.ZERO, BigInteger.ONE, BigInteger.TEN, BigInteger.valueOf(21000), CONTRACT, BigInteger.ONE, null);
        return EvmAccount.fromPrivateKey(new ECKey()).sign(tx);
    }

    @Test
    public void withAProxyEveryRequestGoesThroughItByName() throws Exception {
        LitVmTransport transport = transport(socks.getLocalPort());

        //The RPC node: asked through the proxy, which is given the name; the answer comes back through it
        LitVmRpcClient rpc = new LitVmRpcClient(LITEFORGE, namedServer(), transport);
        Assertions.assertEquals(LITEFORGE.getChainId(), rpc.chainId());
        Assertions.assertEquals(List.of("name " + TLS_NAME + ":" + server.getLocalPort()), socksRequests);
        Assertions.assertEquals(1, serverRequests.size());
        Assertions.assertTrue(serverRequests.getFirst().startsWith("POST / HTTP/1.1 host=" + TLS_NAME), serverRequests.getFirst());
        Assertions.assertTrue(serverRequests.getFirst().endsWith(" eth_chainId"), serverRequests.getFirst());

        //The receipt watch asks the same way
        LitVmNotices notices = notices();
        LitVmNotices.Notice notice = new LitVmNotices.Notice(LITEFORGE, CONTRACT, HASH, LitVmNotices.State.PENDING, null);
        notices.add(notice);
        notices.watch(notice, (network, hash) -> rpc.getReceipt(hash));
        notices.tick();
        Assertions.assertEquals(2, serverRequests.size());
        Assertions.assertTrue(serverRequests.get(1).endsWith(" eth_getTransactionReceipt"), serverRequests.get(1));
        Assertions.assertEquals(List.of(notice), notices.of(CONTRACT));

        //The explorer and Sourcify, at their real names: the proxy is asked for the name, port 443, and nothing else is tried
        socksRequests.clear();
        Assertions.assertThrows(IOException.class, () -> new BlockscoutClient(LITEFORGE, transport).history(CONTRACT));
        Assertions.assertThrows(IOException.class, () -> new BlockscoutClient(LITEFORGE, transport).isVerified(CONTRACT));
        Assertions.assertThrows(IOException.class, () -> new SourcifyClient(LitVmNetwork.SEPOLIA, transport).isVerified(CONTRACT));
        Assertions.assertThrows(IOException.class, () -> new LitVmRpcClient(LITEFORGE, transport).chainId());
        String explorer = URI.create(LITEFORGE.getExplorerUrl()).getHost();
        String node = URI.create(LITEFORGE.getRpcUrl()).getHost();
        Assertions.assertFalse(socksRequests.isEmpty());
        for(String request : socksRequests) {
            Assertions.assertTrue(request.startsWith("name "), "the proxy was given an address, so the name was resolved here: " + request);
        }
        Assertions.assertTrue(socksRequests.contains("name " + explorer + ":443"), socksRequests.toString());
        Assertions.assertTrue(socksRequests.contains("name sourcify.dev:443"), socksRequests.toString());
        Assertions.assertTrue(socksRequests.contains("name " + node + ":443"), socksRequests.toString());

        //Nothing went the direct way, and the server saw only what the proxy brought it
        Assertions.assertEquals(List.of(), direct);
        Assertions.assertEquals(2, serverConnections.get());
    }

    @Test
    public void tlsGoesThroughTheProxyAndTheServersNameIsChecked() throws Exception {
        LitVmTransport transport = transport(socks.getLocalPort());
        //A whole exchange over TLS through the proxy, with a certificate for the name asked
        LitVmRpcClient rpc = new LitVmRpcClient(LITEFORGE, "https://" + TLS_NAME + ":" + tls.getLocalPort() + "/", transport);
        Assertions.assertEquals(LITEFORGE.getChainId(), rpc.chainId());
        rpc.verifyChain();
        Assertions.assertEquals(Optional.empty(), rpc.getReceipt(HASH));
        Assertions.assertEquals(3, serverRequests.size());
        Assertions.assertTrue(serverRequests.getFirst().startsWith("POST / HTTP/1.1 host=" + TLS_NAME), serverRequests.getFirst());
        for(String request : socksRequests) {
            Assertions.assertEquals("name " + TLS_NAME + ":" + tls.getLocalPort(), request);
        }
        //The explorer's and Sourcify's kind of request, a GET, the same way
        EvmTransport.Response got = transport.send(URI.create("https://" + TLS_NAME + ":" + tls.getLocalPort() + "/api"), Map.of("Accept", "application/json"), null, Duration.ofSeconds(20), 10_000);
        Assertions.assertEquals(200, got.status());
        Assertions.assertTrue(got.body().contains("is_verified"), got.body());

        //The same server under another name: its certificate is not for that name, and nothing is sent to it
        int before = serverRequests.size();
        LitVmRpcClient wrongName = new LitVmRpcClient(LITEFORGE, "https://other.litvm.test:" + tls.getLocalPort() + "/", transport);
        IOException refused = Assertions.assertThrows(IOException.class, wrongName::chainId);
        Assertions.assertEquals(before, serverRequests.size(), "a request reached a server whose certificate is for another name");
        Assertions.assertTrue(socksRequests.contains("name other.litvm.test:" + tls.getLocalPort()), socksRequests.toString());
        Assertions.assertNotEquals(LitVmWords.Kind.LOCAL, LitVmWords.kind(refused));
        //A certificate nobody vouches for is refused as well: the same server, trusted by the JVM's authorities only
        LitVmTransport strangers = new LitVmTransport(proxyWanted::get, () -> HostAndPort.fromParts("127.0.0.1", socks.getLocalPort()), true, noting(), null);
        Assertions.assertThrows(IOException.class, () -> new LitVmRpcClient(LITEFORGE, "https://" + TLS_NAME + ":" + tls.getLocalPort() + "/", strangers).chainId());
        Assertions.assertEquals(before, serverRequests.size());
        Assertions.assertEquals(List.of(), direct);
    }

    @Test
    public void withAProxyThatIsDownNothingConnectsDirectly() throws Exception {
        Eip1559Transaction.Signed signed = signedTransfer();
        //The proxy is configured, and nothing listens there; and the proxy is wanted, and there is none to use
        for(Integer port : new Integer[] {deadPort, null}) {
            LitVmTransport transport = transport(port);
            //A server that is right here and would answer: it is not asked
            LitVmRpcClient rpc = new LitVmRpcClient(LITEFORGE, localServer(), transport);
            IOException failure = Assertions.assertThrows(IOException.class, rpc::chainId);
            Assertions.assertEquals(LitVmWords.Kind.UNREACHABLE, LitVmWords.kind(failure));
            //Nothing left this process, and the failure says so by its type
            Assertions.assertInstanceOf(EvmTransport.NotSentException.class, failure);
            Assertions.assertEquals("Could not read balances on " + LITEFORGE.getDisplayName() + ". The server could not be reached.", LitVmWords.balancesUnread(LITEFORGE, failure));
            Assertions.assertEquals("The server could not be reached.", LitVmWords.sentence(Assertions.assertThrows(IOException.class, () -> new LitVmRpcClient(LITEFORGE, transport).chainId())));

            //The explorer: its history, a lookup, and the upload of a source
            BlockscoutClient explorer = new BlockscoutClient(LITEFORGE, transport);
            Assertions.assertEquals("History unavailable. The explorer could not be reached.", LitVmHistoryView.historyFailed(Assertions.assertThrows(IOException.class, () -> explorer.history(CONTRACT))));
            Assertions.assertEquals(LitVmWords.Kind.UNREACHABLE, LitVmWords.kind(Assertions.assertThrows(IOException.class, () -> explorer.isVerified(CONTRACT))));
            Assertions.assertEquals(LitVmWords.Kind.UNREACHABLE,
                    LitVmWords.kind(Assertions.assertThrows(IOException.class, () -> explorer.verifyStandardInput(CONTRACT, "v0.8.30", "Token.sol:Token", "{}", "mit"))));
            //Sourcify: a lookup, a submission and a job
            SourcifyClient sourcify = new SourcifyClient(LitVmNetwork.SEPOLIA, transport);
            Assertions.assertEquals("Sourcify could not be reached.", LitVmWords.sentence(Assertions.assertThrows(IOException.class, () -> sourcify.isVerified(CONTRACT)), LitVmWords.SOURCIFY));
            Assertions.assertEquals(LitVmWords.Kind.UNREACHABLE, LitVmWords.kind(Assertions.assertThrows(IOException.class, () -> sourcify.submit(CONTRACT, "{}", "v0.8.30", "Token.sol:Token"))));
            Assertions.assertEquals(LitVmWords.Kind.UNREACHABLE, LitVmWords.kind(Assertions.assertThrows(IOException.class, () -> sourcify.job("0123456789abcdef"))));

            //The receipt watch: no answer, the notice stays, and nothing is asked directly
            LitVmNotices notices = notices();
            LitVmNotices.Notice notice = new LitVmNotices.Notice(LITEFORGE, CONTRACT, HASH, LitVmNotices.State.PENDING, null);
            notices.add(notice);
            notices.watch(notice, (network, hash) -> rpc.getReceipt(hash));
            notices.tick();
            Assertions.assertEquals(List.of(notice), notices.of(CONTRACT));
        }
        Assertions.assertEquals(0, serverConnections.get(), "a server was contacted directly: " + serverRequests);
        Assertions.assertEquals(List.of(), direct);

        //A broadcast: the chain was verified while no proxy was wanted; then the proxy is wanted and down. The signed
        //transaction is not sent to the server that is right here, and the failure says that nothing was sent
        proxyWanted.set(false);
        LitVmTransport transport = transport(deadPort);
        LitVmRpcClient rpc = new LitVmRpcClient(LITEFORGE, localServer(), transport);
        rpc.verifyChain();
        int verified = serverConnections.get();
        proxyWanted.set(true);
        IOException failure = Assertions.assertThrows(IOException.class, () -> rpc.sendRawTransaction(signed));
        Assertions.assertInstanceOf(EvmTransport.NotSentException.class, failure);
        Assertions.assertEquals(verified, serverConnections.get(), "the transaction was sent directly");
        Assertions.assertTrue(serverRequests.stream().noneMatch(request -> request.contains("eth_sendRawTransaction")), serverRequests.toString());
        LitVmWords.NeverSent said = Assertions.assertThrows(LitVmWords.NeverSent.class, () -> LitVmFlowPane.send(rpc, signed, "The transfer"));
        Assertions.assertEquals("The transfer was not sent. The server could not be reached.", said.getMessage());
        Assertions.assertEquals(List.of(), socksRequests);
    }

    @Test
    public void theAppNeverSendsPlainHttpThroughAProxy() {
        //As the app builds it: a server over plain http is not asked through the proxy, and not directly either
        LitVmTransport transport = new LitVmTransport(proxyWanted::get, () -> HostAndPort.fromParts("127.0.0.1", socks.getLocalPort()), false, noting(), trust);
        IOException failure = Assertions.assertThrows(IOException.class, () -> new LitVmRpcClient(LITEFORGE, namedServer(), transport).chainId());
        Assertions.assertInstanceOf(EvmTransport.NotSentException.class, failure);
        Assertions.assertEquals(LitVmWords.Kind.UNREACHABLE, LitVmWords.kind(failure));
        Assertions.assertEquals(List.of(), socksRequests);
        Assertions.assertEquals(0, serverConnections.get());
        Assertions.assertEquals(List.of(), direct);
        //Nor when the setting cannot even be read: not knowing is not "no proxy"
        LitVmTransport unknown = new LitVmTransport(() -> {
            throw new IllegalStateException("No coin scope on this thread");
        }, () -> null, true, noting(), trust);
        IOException unread = Assertions.assertThrows(IOException.class, () -> new LitVmRpcClient(LITEFORGE, localServer(), unknown).chainId());
        Assertions.assertInstanceOf(EvmTransport.NotSentException.class, unread);
        Assertions.assertEquals("The server could not be reached.", LitVmWords.sentence(unread));
        Assertions.assertEquals(0, serverConnections.get());
        Assertions.assertEquals(List.of(), direct);
    }

    @Test
    public void aClientThatWouldNotGoThroughTheProxyIsRefused() throws Exception {
        HostAndPort via = HostAndPort.fromParts("127.0.0.1", socks.getLocalPort());
        //As built for the proxy: it holds that proxy and no other way out, follows no redirect and has no cookies
        org.eclipse.jetty.client.HttpClient built = LitVmTransport.build(via, null);
        LitVmTransport.requireProxied(built, via);
        Assertions.assertFalse(built.isFollowRedirects());
        Assertions.assertTrue(built.getCookieStore().getCookies().isEmpty());
        built.getCookieStore().add(URI.create("https://" + TLS_NAME + "/"), new java.net.HttpCookie("id", "42"));
        Assertions.assertTrue(built.getCookieStore().getCookies().isEmpty(), "the client keeps cookies");
        //Built for another proxy than is configured now
        Assertions.assertThrows(IllegalStateException.class, () -> LitVmTransport.requireProxied(built, HostAndPort.fromParts("127.0.0.1", deadPort)));
        Assertions.assertThrows(IllegalStateException.class, () -> LitVmTransport.requireProxied(built, HostAndPort.fromParts("localhost", socks.getLocalPort())));
        //Its proxy taken away, as a shared client's can be while it is rebuilt: it would connect directly, so it is refused
        built.getProxyConfiguration().getProxies().clear();
        Assertions.assertThrows(IllegalStateException.class, () -> LitVmTransport.requireProxied(built, via));
        Assertions.assertThrows(IllegalStateException.class, () -> LitVmTransport.requireProxied(new org.eclipse.jetty.client.HttpClient(), via));
    }

    @Test
    public void theProxyAsTheSettingGivesIt() throws Exception {
        //No port: the app's default one, as its other proxied connections take it
        HostAndPort bare = LitVmTransport.normalise(HostAndPort.fromString("127.0.0.1"));
        Assertions.assertEquals(com.sparrowwallet.sparrow.net.ProxyTcpOverTlsTransport.DEFAULT_PROXY_PORT, bare.getPort());
        LitVmTransport.requireProxied(LitVmTransport.build(bare, null), bare);
        Assertions.assertNull(LitVmTransport.normalise(null));
        //An IPv6 address: the client it is built for is taken as going through it
        HostAndPort six = LitVmTransport.normalise(HostAndPort.fromString("[::1]:1080"));
        LitVmTransport.requireProxied(LitVmTransport.build(six, null), six);
        Assertions.assertThrows(IllegalStateException.class, () -> LitVmTransport.requireProxied(LitVmTransport.build(six, null), HostAndPort.fromString("[::2]:1080")));
        //A setting without a port does not break the request: it fails as a proxy that is not there, and nothing is sent
        LitVmTransport transport = new LitVmTransport(proxyWanted::get, () -> HostAndPort.fromString("proxy.litvm.invalid"), true, noting(), trust);
        IOException failure = Assertions.assertThrows(IOException.class, () -> new LitVmRpcClient(LITEFORGE, localServer(), transport).chainId());
        //The proxy's own name does not resolve: before any byte to it, so nothing was sent
        Assertions.assertInstanceOf(EvmTransport.NotSentException.class, failure);
        Assertions.assertEquals("The server could not be reached.", LitVmWords.sentence(failure));
        Assertions.assertEquals(0, serverConnections.get());
        Assertions.assertEquals(List.of(), direct);

        //Which failures are certain to have come before any byte reached the proxy
        Assertions.assertTrue(LitVmTransport.neverConnected(new java.util.concurrent.ExecutionException(new java.net.ConnectException("Connection refused"))));
        Assertions.assertTrue(LitVmTransport.neverConnected(new java.util.concurrent.ExecutionException(new java.net.SocketTimeoutException("Connect Timeout"))));
        Assertions.assertTrue(LitVmTransport.neverConnected(new java.util.concurrent.ExecutionException(new java.net.UnknownHostException("proxy.litvm.invalid"))));
        Assertions.assertTrue(LitVmTransport.neverConnected(new IOException(new java.nio.channels.UnresolvedAddressException())));
        //And which are not: a read that timed out, the whole request's time, an answer cut short, a refusal by the proxy
        Assertions.assertFalse(LitVmTransport.neverConnected(new java.util.concurrent.ExecutionException(new java.net.SocketTimeoutException("Read timed out"))));
        Assertions.assertFalse(LitVmTransport.neverConnected(new java.util.concurrent.TimeoutException("Total timeout 2000 ms elapsed")));
        Assertions.assertFalse(LitVmTransport.neverConnected(new java.util.concurrent.ExecutionException(new java.io.EOFException("closed"))));
        Assertions.assertFalse(LitVmTransport.neverConnected(new java.util.concurrent.ExecutionException(new IOException("SOCKS5 failure"))));
        Assertions.assertFalse(LitVmTransport.neverConnected(null));
    }

    @Test
    public void withNoProxyRequestsGoAsTheyAlwaysDid() throws Exception {
        proxyWanted.set(false);
        LitVmTransport transport = transport(socks.getLocalPort());
        LitVmRpcClient rpc = new LitVmRpcClient(LITEFORGE, localServer(), transport);
        Assertions.assertEquals(LITEFORGE.getChainId(), rpc.chainId());
        Assertions.assertEquals(Optional.empty(), rpc.getReceipt(HASH));
        Assertions.assertEquals(List.of(URI.create(localServer()), URI.create(localServer())), direct);
        Assertions.assertEquals(2, serverConnections.get());
        Assertions.assertTrue(serverRequests.getFirst().startsWith("POST / HTTP/1.1 host=127.0.0.1"), serverRequests.getFirst());
        Assertions.assertEquals(List.of(), socksRequests);

        //The explorer and Sourcify take the direct way too: here it only notes where they would go
        List<URI> asked = new ArrayList<>();
        LitVmTransport noting = new LitVmTransport(proxyWanted::get, () -> null, false, (uri, headers, body, timeout, maxBytes) -> {
            asked.add(uri);
            return new EvmTransport.Response(200, "{\"is_verified\":true,\"match\":\"exact_match\",\"items\":[]}");
        }, null);
        Assertions.assertTrue(new BlockscoutClient(LITEFORGE, noting).isVerified(CONTRACT));
        Assertions.assertTrue(new SourcifyClient(LitVmNetwork.SEPOLIA, noting).isVerified(CONTRACT));
        Assertions.assertEquals(List.of(URI.create(LITEFORGE.getExplorerUrl() + "/api/v2/smart-contracts/" + CONTRACT),
                URI.create("https://sourcify.dev/server/v2/contract/" + LitVmNetwork.SEPOLIA.getChainId() + "/" + CONTRACT)), asked);

        //The setting is read at each request: turned on, the same transport's next request goes through the proxy
        proxyWanted.set(true);
        LitVmRpcClient named = new LitVmRpcClient(LITEFORGE, namedServer(), transport);
        Assertions.assertEquals(LITEFORGE.getChainId(), named.chainId());
        Assertions.assertEquals(List.of("name " + TLS_NAME + ":" + server.getLocalPort()), socksRequests);
        Assertions.assertEquals(2, direct.size());
        //And turned off again, directly
        proxyWanted.set(false);
        Assertions.assertEquals(LITEFORGE.getChainId(), rpc.chainId());
        Assertions.assertEquals(3, direct.size());
        Assertions.assertEquals(1, socksRequests.size());
    }

    @Test
    public void noRedirectIsFollowedAndNoCookieIsKeptOnEitherWay() throws Exception {
        Eip1559Transaction.Signed signed = signedTransfer();
        for(boolean proxied : new boolean[] {false, true}) {
            proxyWanted.set(proxied);
            serverRequests.clear();
            cookies.clear();
            redirectBroadcasts = false;
            LitVmTransport transport = transport(socks.getLocalPort());
            LitVmRpcClient rpc = new LitVmRpcClient(LITEFORGE, proxied ? namedServer() : localServer(), transport);
            //Every answer sets a cookie; no later request brings one back
            rpc.verifyChain();
            rpc.chainId();
            rpc.getBalance(CONTRACT);
            Assertions.assertEquals(List.of(false, false, false), cookies, proxied ? "through the proxy" : "directly");

            //A broadcast answered with a redirect: the signed transaction is not sent on to the other server
            redirectBroadcasts = true;
            IOException failure = Assertions.assertThrows(IOException.class, () -> rpc.sendRawTransaction(signed));
            Assertions.assertFalse(failure instanceof EvmTransport.NotSentException, "the bytes did leave: " + failure);
            Assertions.assertEquals(0, elsewhereConnections.get(), "the redirect was followed " + (proxied ? "through the proxy" : "directly"));
            Assertions.assertEquals(1, serverRequests.stream().filter(request -> request.endsWith(" eth_sendRawTransaction")).count());
            //It did reach a server, so it may be out
            Assertions.assertThrows(LitVmWords.MaybeSent.class, () -> LitVmFlowPane.send(rpc, signed, "The transfer"));
            Assertions.assertEquals(0, elsewhereConnections.get());
        }
    }

    @Test
    public void aRequestKeepsItsTimeAndItsCapOnEitherWay() throws Exception {
        for(boolean proxied : new boolean[] {false, true}) {
            proxyWanted.set(proxied);
            LitVmTransport transport = transport(socks.getLocalPort());
            URI uri = URI.create(proxied ? namedServer() : localServer());
            //The cap: an answer larger than the caller allows is refused
            Assertions.assertThrows(IOException.class, () -> transport.send(uri, Map.of("Content-Type", "application/json"), "{}".getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(20), 5));
            EvmTransport.Response response = transport.send(uri, Map.of("Accept", "application/json"), null, Duration.ofSeconds(20), 1000);
            Assertions.assertEquals(200, response.status());
            Assertions.assertTrue(response.body().contains("is_verified"), response.body());
        }
        //The time covers the whole exchange. A server that sends its headers and then never the body, asked directly:
        try(ServerSocket trickle = new ServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
            daemon("trickle", () -> {
                while(true) {
                    Socket socket = trickle.accept();
                    daemon("trickle connection", () -> {
                        socket.getInputStream().read();
                        socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 1000\r\n\r\n{".getBytes(StandardCharsets.ISO_8859_1));
                        socket.getOutputStream().flush();
                        Thread.sleep(60_000);
                    });
                }
            });
            proxyWanted.set(false);
            long start = System.nanoTime();
            IOException failure = Assertions.assertThrows(IOException.class,
                    () -> new LitVmRpcClient(LITEFORGE, "http://127.0.0.1:" + trickle.getLocalPort() + "/", transport(null)).withDeadline(Duration.ofSeconds(2)).chainId());
            Assertions.assertTrue(System.nanoTime() - start < 20_000_000_000L, "the request outlived its time");
            Assertions.assertEquals(LitVmWords.Kind.TOO_SLOW, LitVmWords.kind(failure), String.valueOf(failure));
        }
        //A proxy that takes the connection and never answers
        try(ServerSocket silent = new ServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
            proxyWanted.set(true);
            long start = System.nanoTime();
            IOException failure = Assertions.assertThrows(IOException.class, () -> new LitVmRpcClient(LITEFORGE, namedServer(), transport(silent.getLocalPort())).withDeadline(Duration.ofSeconds(2)).chainId());
            Assertions.assertTrue(System.nanoTime() - start < 20_000_000_000L, "the request outlived its time");
            Assertions.assertTrue(List.of(LitVmWords.Kind.TOO_SLOW, LitVmWords.Kind.UNREACHABLE).contains(LitVmWords.kind(failure)), String.valueOf(failure));
        }
    }

    /** What a thread with no coin scope gets, under the unified build's strict scoping. */
    private static <T> T unscoped(java.util.concurrent.Callable<T> work) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        Thread thread = new Thread(() -> {
            try {
                Assertions.assertNull(Coin.getScoped());
                result.complete(work.call());
            } catch(Throwable t) {
                result.completeExceptionally(t);
            }
        }, "LitVM");
        thread.setDaemon(true);
        thread.start();
        try {
            return result.get(60, TimeUnit.SECONDS);
        } catch(java.util.concurrent.ExecutionException e) {
            if(e.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw e;
        }
    }

    @Test
    public void theRealSettingIsReadOnAThreadWithNoCoinScope() throws Exception {
        //The app's own transport and its own condition, as the LitVM tab's threads use them in the unified build: strict
        //coin scoping on, and no scope on the thread. The config is the test run's own, and is put back.
        Config config = Config.get();
        Coin previousScope = Coin.beginScope(Coin.LITECOIN);
        boolean useProxy = config.isUseProxy();
        String proxyServer = config.getProxyServer();
        ServerType serverType = config.getServerType();
        Server electrumServer = config.getElectrumServer();
        AtomicReference<Thread> foreground = new AtomicReference<>(Thread.currentThread());
        try {
            Coin.setForegroundThread(foreground.get());
            Coin.setStrictScoping(true);
            LitVmTransport transport = LitVmTransport.forCoin(Coin.LITECOIN);
            Assertions.assertSame(transport, LitVmTransport.forCoin(Coin.LITECOIN));
            Assertions.assertSame(transport, LitVmContext.transportFor(null));

            //No proxy, and a server that is no onion service: the bare condition throws on such a thread (this is what
            //broke every request), the transport's own reads it in the wallet's coin scope and goes directly
            config.setUseProxy(false);
            config.setServerType(ServerType.ELECTRUM_SERVER);
            config.setElectrumServer(new Server("ssl://electrum.litvm.test:50002"));
            Assertions.assertThrows(IllegalStateException.class, () -> unscoped(() -> Config.get().requiresInternalTor()));
            Assertions.assertFalse(unscoped(() -> LitVmTransport.proxyWanted(Coin.LITECOIN)));
            Assertions.assertEquals(LITEFORGE.getChainId(), (long)unscoped(() -> new LitVmRpcClient(LITEFORGE, localServer(), transport).chainId()));
            Assertions.assertEquals(1, serverConnections.get());
            Assertions.assertEquals(List.of(), socksRequests);
            //The scope is given back: the thread is as unscoped after as before
            Assertions.assertNull(unscoped(() -> {
                LitVmTransport.proxyWanted(Coin.LITECOIN);
                return Coin.getScoped();
            }));

            //A proxy: the request goes to it, by name, and not directly
            config.setUseProxy(true);
            config.setProxyServer("127.0.0.1:" + socks.getLocalPort());
            Assertions.assertTrue(unscoped(() -> LitVmTransport.proxyWanted(Coin.LITECOIN)));
            Assertions.assertThrows(IOException.class, () -> unscoped(() -> new LitVmRpcClient(LITEFORGE, "https://" + TLS_NAME + ":9/", transport).chainId()));
            Assertions.assertEquals(List.of("name " + TLS_NAME + ":9"), socksRequests);
            //As the app builds it, plain http is not sent through the proxy, and not directly
            IOException plain = Assertions.assertThrows(IOException.class, () -> unscoped(() -> new LitVmRpcClient(LITEFORGE, localServer(), transport).chainId()));
            Assertions.assertInstanceOf(EvmTransport.NotSentException.class, plain);
            Assertions.assertEquals(1, serverConnections.get());

            //A proxy that is down: nothing connects directly
            config.setProxyServer("127.0.0.1:" + deadPort);
            IOException down = Assertions.assertThrows(IOException.class, () -> unscoped(() -> new LitVmRpcClient(LITEFORGE, "https://" + TLS_NAME + ":" + tls.getLocalPort() + "/", transport).chainId()));
            Assertions.assertEquals("The server could not be reached.", LitVmWords.sentence(down));
            Assertions.assertEquals(1, serverConnections.get());

            //No proxy, but the wallet's server is an onion service, so Tor is wanted; it is not running here, and the
            //request is refused rather than sent directly
            config.setUseProxy(false);
            config.setElectrumServer(new Server("tcp://" + "abcdefgh".repeat(7) + ".onion:50001"));
            Assertions.assertTrue(config.requiresInternalTor());
            Assertions.assertTrue(unscoped(() -> LitVmTransport.proxyWanted(Coin.LITECOIN)));
            IOException noTor = Assertions.assertThrows(IOException.class, () -> unscoped(() -> new LitVmRpcClient(LITEFORGE, localServer(), transport).chainId()));
            Assertions.assertInstanceOf(EvmTransport.NotSentException.class, noTor);
            Assertions.assertEquals("The server could not be reached.", LitVmWords.sentence(noTor));
            Assertions.assertEquals(1, serverConnections.get(), "a server was contacted directly while Tor was wanted");
        } finally {
            Coin.setStrictScoping(false);
            Coin.setForegroundThread(null);
            config.setUseProxy(useProxy);
            config.setProxyServer(proxyServer);
            config.setElectrumServer(electrumServer);
            config.setServerType(serverType);
            Coin.endScope(previousScope);
        }
    }

    @Test
    public void theAppMakesNoClientThatGoesAroundItsTransport() throws IOException {
        //Every client the app makes is given the tab's transport; the direct one is named in the transport alone
        Path sources = Path.of("src", "main", "java", "com", "sparrowwallet", "sparrow");
        Assertions.assertTrue(Files.isDirectory(sources), "run from the project folder: " + sources.toAbsolutePath());
        List<String> problems = new ArrayList<>();
        try(Stream<Path> files = Files.walk(sources)) {
            for(Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String text = Files.readString(file);
                String name = file.getFileName().toString();
                if(text.contains("EvmTransport.direct(") && !name.equals("LitVmTransport.java")) {
                    problems.add(name + " names the direct transport");
                }
                java.util.regex.Matcher made = java.util.regex.Pattern.compile("new (LitVmRpcClient|BlockscoutClient|SourcifyClient)\\(([^;]*);").matcher(text);
                while(made.find()) {
                    if(!made.group(2).contains("transport()") && !made.group(2).contains("LitVmContext.transportFor(")) {
                        problems.add(name + " makes a " + made.group(1) + " without the tab's transport");
                    }
                }
                if(text.contains("LitVmRpcClient::new") || text.contains("BlockscoutClient::new") || text.contains("SourcifyClient::new")) {
                    problems.add(name + " makes a client by a constructor reference");
                }
                if(name.startsWith("LitVm") && !name.equals("LitVmTransport.java") && (text.contains("getHttpClientService") || text.contains("java.net.http.HttpClient") || text.contains("openConnection("))) {
                    problems.add(name + " has a way of its own to a server");
                }
            }
        }
        Assertions.assertEquals(List.of(), problems);
    }
}
