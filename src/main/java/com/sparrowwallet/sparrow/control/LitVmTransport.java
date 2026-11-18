package com.sparrowwallet.sparrow.control;

import com.google.common.net.HostAndPort;
import com.sparrowwallet.drongo.Coin;
import com.sparrowwallet.drongo.evm.EvmTransport;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.io.Config;
import com.sparrowwallet.tern.http.client.HttpProxy;
import com.sparrowwallet.tern.http.client.HttpProxyProtocol;
import com.sparrowwallet.tern.http.client.JettyHttpClientService;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.ProxyConfiguration;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.util.BytesContentProvider;
import org.eclipse.jetty.client.util.InputStreamResponseListener;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.util.HttpCookieStore;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.util.thread.ScheduledExecutorScheduler;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.security.KeyStore;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * The one way the LitVM tab reaches a server: every RPC node, explorer and Sourcify request of the EVM clients goes
 * through here, and so follows the wallet's proxy setting.
 *
 * The setting is read at each request, inside the scope of the coin the tab's wallet belongs to (the setting is kept
 * per coin, and the tab's threads have no coin scope of their own). With no proxy and no Tor the request goes
 * directly, as it always did. With a proxy or Tor wanted it goes through a SOCKS5 proxy, which is handed the server's
 * host name, so the name is resolved there and not on this machine. It fails closed: when a proxy is wanted and there
 * is none to use (Tor starting, a settings change), when the setting cannot be read, or when the proxy cannot be
 * reached, the request fails as a server that could not be reached. It never falls back to a direct connection.
 * Plain http is not sent through a proxy unless the server is an onion service: an exit node could read or forge the
 * answers.
 *
 * The proxied client is this transport's own (the JDK client cannot use SOCKS, and the app's shared one follows
 * redirects, keeps cookies, and is stopped and rebuilt by other services): it follows no redirect, keeps and sends no
 * cookie, and is checked to hold exactly the proxy it was built for before each request. It speaks SOCKS5 to the
 * proxy (the shared client speaks SOCKS4a; both hand over the name). It does not log in to the proxy under a name of
 * its own, so on Tor the tab's requests are not given a circuit apart from others that do not log in either.
 */
final class LitVmTransport implements EvmTransport {
    /**
     * A SOCKS5 proxy, made by the rule of the library the app's shared HTTP client comes from: its class is not open to
     * this module by name. SOCKS5 hands the proxy the server's host name.
     */
    private static final class ProxyMaker extends JettyHttpClientService {
        private static final ProxyMaker INSTANCE = new ProxyMaker();

        private ProxyConfiguration.Proxy socks(HostAndPort via) {
            return computeJettyProxy(new HttpProxy(HttpProxyProtocol.SOCKS5, via.getHost(), via.getPort()));
        }
    }

    private static final String SOCKS5_PROXY = "com.sparrowwallet.tern.http.client.socks5.Socks5Proxy";

    private static final Map<Coin, LitVmTransport> APP = new ConcurrentHashMap<>();

    private final BooleanSupplier proxyWanted;
    private final Supplier<HostAndPort> proxy;
    private final boolean plainHttpThroughProxy;
    private final EvmTransport direct;
    private final KeyStore trust;
    //The proxied client and the proxy it was built for
    private HttpClient jetty;
    private HostAndPort jettyProxy;

    /** The transport for every EVM client made for a wallet of this coin. */
    static LitVmTransport forCoin(Coin coin) {
        return APP.computeIfAbsent(coin, scope -> new LitVmTransport(() -> proxyWanted(scope), AppServices::getTorProxy, false, EvmTransport.direct(), null));
    }

    /**
     * Whether a proxy or Tor is wanted for this coin's wallets, as the wallet's other HTTP requests decide it. Read
     * inside the coin's scope, so it can be asked from any thread.
     */
    static boolean proxyWanted(Coin coin) {
        Coin previous = Coin.beginScope(coin);
        try {
            return Config.get().isUseProxy() || AppServices.isTorRunning() || Config.get().requiresInternalTor();
        } finally {
            Coin.endScope(previous);
        }
    }

    /**
     * For tests: plainHttpThroughProxy allows a local server behind the proxy (the app never does), direct is the way
     * used when no proxy is wanted, and trust, when given, is what the proxied client trusts in place of the JVM's
     * certificate authorities.
     */
    LitVmTransport(BooleanSupplier proxyWanted, Supplier<HostAndPort> proxy, boolean plainHttpThroughProxy, EvmTransport direct, KeyStore trust) {
        this.proxyWanted = proxyWanted;
        this.proxy = proxy;
        this.plainHttpThroughProxy = plainHttpThroughProxy;
        this.direct = direct;
        this.trust = trust;
    }

    /** A refusal before anything was tried: nothing left this process, and the server counts as not reached. */
    private static NotSentException notSent(String why, Throwable cause) {
        ConnectException unreachable = new ConnectException(why);
        if(cause != null) {
            unreachable.initCause(cause);
        }
        return new NotSentException(why, unreachable);
    }

    @Override
    public EvmTransport.Response send(URI uri, Map<String, String> headers, byte[] body, Duration timeout, int maxBytes) throws IOException {
        boolean wanted;
        HostAndPort via;
        try {
            wanted = proxyWanted.getAsBoolean();
            via = wanted ? normalise(proxy.get()) : null;
        } catch(RuntimeException e) {
            //Not knowing is not "no proxy": nothing is sent
            throw notSent("The proxy setting could not be read; not connecting to " + uri.getHost(), e);
        }
        if(!wanted) {
            return direct.send(uri, headers, body, timeout, maxBytes);
        }
        //From here on nothing connects directly, whatever goes wrong
        if(via == null) {
            throw notSent("A proxy or Tor is wanted but not available; not connecting directly to " + uri.getHost(), null);
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if(!"https".equalsIgnoreCase(uri.getScheme()) && !host.endsWith(".onion") && !plainHttpThroughProxy) {
            throw notSent("Plain http is not sent through a proxy or Tor; not connecting to " + uri.getHost(), null);
        }
        HttpClient client;
        try {
            client = client(via);
            requireProxied(client, via);
        } catch(Exception e) {
            throw notSent("The proxied client could not be made ready; not connecting to " + uri.getHost(), e);
        }
        long millis = Math.max(1, timeout.toMillis());
        int status;
        byte[] data;
        try {
            Request request = client.newRequest(uri).method(body == null ? HttpMethod.GET : HttpMethod.POST).timeout(millis, TimeUnit.MILLISECONDS).followRedirects(false);
            String contentType = null;
            for(Map.Entry<String, String> header : headers.entrySet()) {
                if(header.getKey().equalsIgnoreCase("Content-Type")) {
                    contentType = header.getValue();
                } else {
                    request.header(header.getKey(), header.getValue());
                }
            }
            if(body != null) {
                request.content(new BytesContentProvider(contentType == null ? "application/octet-stream" : contentType, body));
            }
            InputStreamResponseListener listener = new InputStreamResponseListener();
            request.send(listener);
            org.eclipse.jetty.client.api.Response response = listener.get(millis, TimeUnit.MILLISECONDS);
            status = response.getStatus();
            try(InputStream in = listener.getInputStream()) {
                data = in.readNBytes(maxBytes < Integer.MAX_VALUE ? maxBytes + 1 : maxBytes);
            }
        } catch(InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted waiting for " + uri.getHost(), e);
        } catch(Exception e) {
            if(neverConnected(e)) {
                throw notSent("The proxy could not be reached; not connecting directly to " + uri.getHost(), e);
            }
            for(Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
                if(t instanceof TimeoutException) {
                    HttpTimeoutException timedOut = new HttpTimeoutException("No answer from " + uri.getHost() + " through the proxy in time");
                    timedOut.initCause(e);
                    throw timedOut;
                }
            }
            //The proxy took the connection and no answer came back through it: the request may have gone out
            ConnectException unreachable = new ConnectException("Could not reach " + uri.getHost() + " through the proxy");
            unreachable.initCause(e);
            throw unreachable;
        }
        //The same cap as on the direct way: an answer was had, and it is too large
        if(data.length > maxBytes) {
            throw new IOException("The response is larger than " + maxBytes + " bytes");
        }
        return new EvmTransport.Response(status, new String(data, java.nio.charset.StandardCharsets.UTF_8));
    }

    /** The proxy with its port: the app's default where the setting names none, as the app's other proxied connections take it. */
    static HostAndPort normalise(HostAndPort via) {
        return via == null ? null : HostAndPort.fromParts(via.getHost(), via.getPortOrDefault(com.sparrowwallet.sparrow.net.ProxyTcpOverTlsTransport.DEFAULT_PROXY_PORT));
    }

    /**
     * Whether a failure of the proxied client is certain to have come before any byte reached the proxy. The only
     * connection this client makes itself is the one to the proxy (the server's name is never resolved here), so that
     * is so when the proxy's own name did not resolve, when the connection to it was refused, and when it timed out
     * while being made (Jetty's own "Connect Timeout", told apart from a read that timed out). Anything else is not.
     */
    static boolean neverConnected(Throwable failure) {
        for(Throwable t = failure; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if(t instanceof ConnectException || t instanceof java.net.UnknownHostException || t instanceof java.nio.channels.UnresolvedAddressException
                    || (t instanceof java.net.SocketTimeoutException && "Connect Timeout".equals(t.getMessage()))) {
                return true;
            }
        }
        return false;
    }

    /** This transport's own client for this proxy, built anew when the proxy has changed. */
    private synchronized HttpClient client(HostAndPort via) throws Exception {
        if(jetty != null && via.equals(jettyProxy) && jetty.isRunning()) {
            return jetty;
        }
        if(jetty != null) {
            HttpClient old = jetty;
            jetty = null;
            jettyProxy = null;
            try {
                old.stop();
            } catch(Exception e) {
                //It is not used again either way
            }
        }
        HttpClient client = build(via, trust);
        boolean started = false;
        try {
            client.start();
            started = true;
        } finally {
            if(!started) {
                //Partly started: its threads are not left running
                try {
                    client.stop();
                } catch(Exception e) {
                    //It is not used either way
                }
            }
        }
        jetty = client;
        jettyProxy = via;
        return client;
    }

    /** A client that can only go through this SOCKS5 proxy, follows no redirect, and keeps and sends no cookie. Not started. */
    static HttpClient build(HostAndPort via, KeyStore trust) {
        //The client side checks the server's name against its certificate
        SslContextFactory.Client ssl = new SslContextFactory.Client();
        if(trust != null) {
            ssl.setTrustStore(trust);
        }
        HttpClient client = new HttpClient(ssl);
        client.setFollowRedirects(false);
        client.setCookieStore(new HttpCookieStore.Empty());
        client.setConnectTimeout(EvmTransport.CONNECT_TIMEOUT.toMillis());
        client.setUserAgentField(null);
        QueuedThreadPool threads = new QueuedThreadPool();
        threads.setName("LitVM http");
        threads.setDaemon(true);
        client.setExecutor(threads);
        client.setScheduler(new ScheduledExecutorScheduler("LitVM http scheduler", true));
        ProxyConfiguration.Proxy socks = ProxyMaker.INSTANCE.socks(via);
        //SOCKS5 and nothing else: SOCKS4 would have this machine resolve the server's name
        if(socks == null || !socks.getClass().getName().equals(SOCKS5_PROXY)) {
            throw new IllegalStateException("No SOCKS5 proxy could be made");
        }
        client.getProxyConfiguration().getProxies().add(socks);
        return client;
    }

    /**
     * Refuses a client that would not go through this proxy: one with no proxy at all (it would connect directly), with
     * more than one, or with another one than is configured now.
     */
    static void requireProxied(HttpClient client, HostAndPort via) {
        List<ProxyConfiguration.Proxy> proxies = client.getProxyConfiguration().getProxies();
        //An IPv6 address is kept in brackets by the client and without them by the setting: compared without
        if(proxies.size() != 1 || !proxies.getFirst().getClass().getName().equals(SOCKS5_PROXY)
                || !via.getHost().equalsIgnoreCase(proxies.getFirst().getAddress().getHost().replace("[", "").replace("]", ""))
                || via.getPort() != proxies.getFirst().getAddress().getPort()) {
            throw new IllegalStateException("The client is not set to go through the configured proxy");
        }
    }
}
