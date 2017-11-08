/*
 * Copyright 2010 dorkbox, llc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dorkbox.network;

import static dorkbox.network.dns.resolver.addressProvider.DnsServerAddressStreamProviders.platformDefault;
import static io.netty.util.internal.ObjectUtil.checkNotNull;
import static io.netty.util.internal.ObjectUtil.intValue;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.slf4j.Logger;

import dorkbox.network.connection.EndPoint;
import dorkbox.network.dns.DnsQuestion;
import dorkbox.network.dns.DnsResponse;
import dorkbox.network.dns.constants.DnsRecordType;
import dorkbox.network.dns.constants.DnsResponseCode;
import dorkbox.network.dns.constants.DnsSection;
import dorkbox.network.dns.records.DnsRecord;
import dorkbox.network.dns.resolver.DnsNameResolver;
import dorkbox.network.dns.resolver.DnsQueryLifecycleObserverFactory;
import dorkbox.network.dns.resolver.NoopDnsQueryLifecycleObserverFactory;
import dorkbox.network.dns.resolver.addressProvider.DefaultDnsServerAddressStreamProvider;
import dorkbox.network.dns.resolver.addressProvider.DnsServerAddressStreamProvider;
import dorkbox.network.dns.resolver.addressProvider.SequentialDnsServerAddressStreamProvider;
import dorkbox.network.dns.resolver.cache.DefaultDnsCache;
import dorkbox.network.dns.resolver.cache.DnsCache;
import dorkbox.util.NamedThreadFactory;
import dorkbox.util.OS;
import dorkbox.util.Property;
import io.netty.channel.AddressedEnvelope;
import io.netty.channel.ChannelFactory;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.ReflectiveChannelFactory;
import io.netty.channel.epoll.EpollDatagramChannel;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.oio.OioEventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.InternetProtocolFamily;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.oio.OioDatagramChannel;
import io.netty.resolver.HostsFileEntriesResolver;
import io.netty.resolver.ResolvedAddressTypes;
import io.netty.util.concurrent.Future;
import io.netty.util.internal.PlatformDependent;

/**
 * A DnsClient for resolving DNS name, with reasonably good defaults.
 */
@SuppressWarnings({"unused", "WeakerAccess"})
public
class DnsClient extends EndPoint {

    /*
    http://bugs.java.com/view_bug.do?bug_id=8176361
    Previous JDK releases documented how to configure `java.net.InetAddress` to use the JNDI DNS service provider as the name service. This mechanism, and the system properties to configure it, have been removed in JDK 9

A new mechanism to configure the use of a hosts file has been introduced.

A new system property `jdk.net.hosts.file` has been defined. When this system property is set, the name and address resolution calls of `InetAddress`, i.e `getByXXX`, retrieve the relevant mapping from the specified file. The structure of this file is equivalent to that of the `/etc/hosts` file.

When the system property `jdk.net.hosts.file` is set, and the specified file doesn't exist, the name or address lookup will result in an UnknownHostException. Thus, a non existent hosts file is handled as if the file is empty.


UP UNTIL java 1.8, one can use org/xbill/DNS/spi, ie: sun.net.dns.ResolverConfiguration
     */


    /**
     * This is a list of all of the public DNS servers to query, when submitting DNS queries
     */
    // @formatter:off
    @Property
    public static
    List<InetSocketAddress> DNS_SERVER_LIST = Arrays.asList(
        new InetSocketAddress("8.8.8.8", 53), // Google Public DNS
        new InetSocketAddress("8.8.4.4", 53),
        new InetSocketAddress("208.67.222.222", 53), // OpenDNS
        new InetSocketAddress("208.67.220.220", 53),
        new InetSocketAddress("37.235.1.174", 53), // FreeDNS
        new InetSocketAddress("37.235.1.177", 53)
    );
    // @formatter:on

    /**
     * This is a list of all of the BOX default DNS servers to query, when submitting DNS queries.
     */
    public final static List<InetSocketAddress> DEFAULT_DNS_SERVER_LIST = DefaultDnsServerAddressStreamProvider.defaultAddressList();

    private static final String ptrSuffix = ".in-addr.arpa";
    public static final InetAddress[] INET_ADDRESSES = new InetAddress[0];

    /**
     * Gets the version number.
     */
    public static
    String getVersion() {
        return "2.4";
    }

    /**
     * Retrieve the public facing IP address of this system using DNS.
     * <p/>
     * Same command as
     * <p/>
     * dig +short myip.opendns.com @resolver1.opendns.com
     *
     * @return the public IP address if found, or null if it didn't find it
     */
    public static
    InetAddress getPublicIp() {
        final InetSocketAddress dnsServer = new InetSocketAddress("208.67.222.222", 53);  // openDNS

        DnsClient dnsClient = new DnsClient(dnsServer);
        List<InetAddress> resolved = null;
        try {
            resolved = dnsClient.resolve("myip.opendns.com");
        } catch (Throwable ignored) {
        }

        dnsClient.stop();

        if (resolved != null && resolved.size() > 0) {
            return resolved.get(0);
        }

        return null;
    }

    private final Logger logger = org.slf4j.LoggerFactory.getLogger(getClass());
    private Class<? extends DatagramChannel> channelType;

    private DnsNameResolver resolver;

    private ThreadGroup threadGroup;
    private static final String THREAD_NAME = "DnsClient";

    private EventLoopGroup eventLoopGroup;

    private ChannelFactory<? extends DatagramChannel> channelFactory;

    private DnsCache resolveCache;
    private DnsCache authoritativeDnsServerCache;

    private Integer minTtl;
    private Integer maxTtl;
    private Integer negativeTtl;
    private long queryTimeoutMillis = 5000;

    private ResolvedAddressTypes resolvedAddressTypes = DnsNameResolver.DEFAULT_RESOLVE_ADDRESS_TYPES;
    private boolean recursionDesired = true;
    private int maxQueriesPerResolve = 16;

    private boolean traceEnabled;
    private int maxPayloadSize = 4096;

    private HostsFileEntriesResolver hostsFileEntriesResolver = HostsFileEntriesResolver.DEFAULT;
    private DnsServerAddressStreamProvider dnsServerAddressStreamProvider = platformDefault();
    private DnsQueryLifecycleObserverFactory dnsQueryLifecycleObserverFactory = NoopDnsQueryLifecycleObserverFactory.INSTANCE;

    private String[] searchDomains;
    private int ndots = -1;
    private boolean decodeIdn = true;

    /**
     * Creates a new DNS client, with default name server addresses.
     */
    public
    DnsClient() {
        this(DnsClient.DNS_SERVER_LIST);
    }

    /**
     * Creates a new DNS client, using the provided server (default port 53) for DNS query resolution, with a cache that will obey the TTL of the response
     *
     * @param nameServerAddresses the server to receive your DNS questions.
     */
    public
    DnsClient(final String nameServerAddresses) {
        this(nameServerAddresses, 53);
    }

    /**
     * Creates a new DNS client, using the provided server and por tfor DNS query resolution, with a cache that will obey the TTL of the response
     *
     * @param nameServerAddresses the server to receive your DNS questions.
     */
    public
    DnsClient(final String nameServerAddresses, int port) {
        this(Collections.singletonList(new InetSocketAddress(nameServerAddresses, port)));
    }

    /**
     * Creates a new DNS client, using the provided server for DNS query resolution, with a cache that will obey the TTL of the response
     *
     * @param nameServerAddresses the server to receive your DNS questions.
     */
    public
    DnsClient(final InetSocketAddress nameServerAddresses) {
        this(Collections.singletonList(nameServerAddresses));
    }

    /**
     * Creates a new DNS client.
     *
     * The default TTL value is {@code 0} and {@link Integer#MAX_VALUE}, which practically tells this resolver to
     * respect the TTL from the DNS server.
     *
     * @param nameServerAddresses the list of servers to receive your DNS questions, until it succeeds
     */
    public
    DnsClient(Collection<InetSocketAddress> nameServerAddresses) {
        super(DnsClient.class);

        if (PlatformDependent.isAndroid()) {
            // android ONLY supports OIO (not NIO)
            eventLoopGroup = new OioEventLoopGroup(1, new NamedThreadFactory(THREAD_NAME + "-DNS", threadGroup));
            channelType = OioDatagramChannel.class;
        }
        else if (OS.isLinux()) {
            // JNI network stack is MUCH faster (but only on linux)
            eventLoopGroup = new EpollEventLoopGroup(1, new NamedThreadFactory(THREAD_NAME + "-DNS", threadGroup));
            channelType = EpollDatagramChannel.class;
        }
        else {
            eventLoopGroup = new NioEventLoopGroup(1, new NamedThreadFactory(THREAD_NAME + "-DNS", threadGroup));
            channelType = NioDatagramChannel.class;
        }

        manageForShutdown(eventLoopGroup);

        if (nameServerAddresses != null) {
            this.dnsServerAddressStreamProvider = new SequentialDnsServerAddressStreamProvider(nameServerAddresses);
        }
    }

    /**
     * Sets the cache for resolution results.
     *
     * @param resolveCache the DNS resolution results cache
     *
     * @return {@code this}
     */
    public
    DnsClient resolveCache(DnsCache resolveCache) {
        this.resolveCache = resolveCache;
        return this;
    }

    /**
     * Set the factory used to generate objects which can observe individual DNS queries.
     *
     * @param lifecycleObserverFactory the factory used to generate objects which can observe individual DNS queries.
     *
     * @return {@code this}
     */
    public
    DnsClient dnsQueryLifecycleObserverFactory(DnsQueryLifecycleObserverFactory lifecycleObserverFactory) {
        this.dnsQueryLifecycleObserverFactory = checkNotNull(lifecycleObserverFactory, "lifecycleObserverFactory");
        return this;
    }

    /**
     * Sets the cache for authoritative NS servers
     *
     * @param authoritativeDnsServerCache the authoritative NS servers cache
     *
     * @return {@code this}
     */
    public
    DnsClient authoritativeDnsServerCache(DnsCache authoritativeDnsServerCache) {
        this.authoritativeDnsServerCache = authoritativeDnsServerCache;
        return this;
    }

    /**
     * Sets the minimum and maximum TTL of the cached DNS resource records (in seconds). If the TTL of the DNS
     * resource record returned by the DNS server is less than the minimum TTL or greater than the maximum TTL,
     * this resolver will ignore the TTL from the DNS server and use the minimum TTL or the maximum TTL instead
     * respectively.
     * The default value is {@code 0} and {@link Integer#MAX_VALUE}, which practically tells this resolver to
     * respect the TTL from the DNS server.
     *
     * @param minTtl the minimum TTL
     * @param maxTtl the maximum TTL
     *
     * @return {@code this}
     */
    public
    DnsClient ttl(int minTtl, int maxTtl) {
        this.maxTtl = maxTtl;
        this.minTtl = minTtl;
        return this;
    }

    /**
     * Sets the TTL of the cache for the failed DNS queries (in seconds).
     *
     * @param negativeTtl the TTL for failed cached queries
     *
     * @return {@code this}
     */
    public
    DnsClient negativeTtl(int negativeTtl) {
        this.negativeTtl = negativeTtl;
        return this;
    }

    /**
     * Sets the timeout of each DNS query performed by this resolver (in milliseconds).
     *
     * @param queryTimeoutMillis the query timeout
     *
     * @return {@code this}
     */
    public
    DnsClient queryTimeoutMillis(long queryTimeoutMillis) {
        this.queryTimeoutMillis = queryTimeoutMillis;
        return this;
    }

    /**
     * Sets the list of the protocol families of the address resolved.
     * You can use {@link DnsClient#computeResolvedAddressTypes(InternetProtocolFamily...)}
     * to get a {@link ResolvedAddressTypes} out of some {@link InternetProtocolFamily}s.
     *
     * @param resolvedAddressTypes the address types
     *
     * @return {@code this}
     */
    public
    DnsClient resolvedAddressTypes(ResolvedAddressTypes resolvedAddressTypes) {
        this.resolvedAddressTypes = resolvedAddressTypes;
        return this;
    }

    /**
     * Sets if this resolver has to send a DNS query with the RD (recursion desired) flag set.
     *
     * @param recursionDesired true if recursion is desired
     *
     * @return {@code this}
     */
    public
    DnsClient recursionDesired(boolean recursionDesired) {
        this.recursionDesired = recursionDesired;
        return this;
    }

    /**
     * Sets the maximum allowed number of DNS queries to send when resolving a host name.
     *
     * @param maxQueriesPerResolve the max number of queries
     *
     * @return {@code this}
     */
    public
    DnsClient maxQueriesPerResolve(int maxQueriesPerResolve) {
        this.maxQueriesPerResolve = maxQueriesPerResolve;
        return this;
    }

    /**
     * Sets if this resolver should generate the detailed trace information in an exception message so that
     * it is easier to understand the cause of resolution failure.
     *
     * @param traceEnabled true if trace is enabled
     *
     * @return {@code this}
     */
    public
    DnsClient traceEnabled(boolean traceEnabled) {
        this.traceEnabled = traceEnabled;
        return this;
    }

    /**
     * Sets the capacity of the datagram packet buffer (in bytes).  The default value is {@code 4096} bytes.
     *
     * @param maxPayloadSize the capacity of the datagram packet buffer
     *
     * @return {@code this}
     */
    public
    DnsClient maxPayloadSize(int maxPayloadSize) {
        this.maxPayloadSize = maxPayloadSize;
        return this;
    }

    /**
     * @param hostsFileEntriesResolver the {@link HostsFileEntriesResolver} used to first check
     *         if the hostname is locally aliased.
     *
     * @return {@code this}
     */
    public
    DnsClient hostsFileEntriesResolver(HostsFileEntriesResolver hostsFileEntriesResolver) {
        this.hostsFileEntriesResolver = hostsFileEntriesResolver;
        return this;
    }

    /**
     * Set the {@link DnsServerAddressStreamProvider} which is used to determine which DNS server is used to resolve
     * each hostname.
     *
     * @return {@code this}
     */
    public
    DnsClient nameServerProvider(DnsServerAddressStreamProvider dnsServerAddressStreamProvider) {
        this.dnsServerAddressStreamProvider = checkNotNull(dnsServerAddressStreamProvider, "dnsServerAddressStreamProvider");
        return this;
    }

    /**
     * Set the list of search domains of the resolver.
     *
     * @param searchDomains the search domains
     *
     * @return {@code this}
     */
    public
    DnsClient searchDomains(Iterable<String> searchDomains) {
        checkNotNull(searchDomains, "searchDomains");

        final List<String> list = new ArrayList<String>(4);

        for (String f : searchDomains) {
            if (f == null) {
                break;
            }

            // Avoid duplicate entries.
            if (list.contains(f)) {
                continue;
            }

            list.add(f);
        }

        this.searchDomains = list.toArray(new String[list.size()]);
        return this;
    }

    /**
     * Set the number of dots which must appear in a name before an initial absolute query is made.
     * The default value is {@code 1}.
     *
     * @param ndots the ndots value
     *
     * @return {@code this}
     */
    public
    DnsClient ndots(int ndots) {
        this.ndots = ndots;
        return this;
    }

    private
    DnsCache newCache() {
        return new DefaultDnsCache(intValue(minTtl, 0), intValue(maxTtl, Integer.MAX_VALUE), intValue(negativeTtl, 0));
    }

    /**
     * Set if domain / host names should be decoded to unicode when received.
     * See <a href="https://tools.ietf.org/html/rfc3492">rfc3492</a>.
     *
     * @param decodeIdn if should get decoded
     *
     * @return {@code this}
     */
    public
    DnsClient decodeToUnicode(boolean decodeIdn) {
        this.decodeIdn = decodeIdn;
        return this;
    }


    /**
     * Compute a {@link ResolvedAddressTypes} from some {@link InternetProtocolFamily}s.
     * An empty input will return the default value, based on "java.net" System properties.
     * Valid inputs are (), (IPv4), (IPv6), (Ipv4, IPv6) and (IPv6, IPv4).
     *
     * @param internetProtocolFamilies a valid sequence of {@link InternetProtocolFamily}s
     *
     * @return a {@link ResolvedAddressTypes}
     */
    public static
    ResolvedAddressTypes computeResolvedAddressTypes(InternetProtocolFamily... internetProtocolFamilies) {
        if (internetProtocolFamilies == null || internetProtocolFamilies.length == 0) {
            return DnsNameResolver.DEFAULT_RESOLVE_ADDRESS_TYPES;
        }
        if (internetProtocolFamilies.length > 2) {
            throw new IllegalArgumentException("No more than 2 InternetProtocolFamilies");
        }

        switch (internetProtocolFamilies[0]) {
            case IPv4:
                return (internetProtocolFamilies.length >= 2 && internetProtocolFamilies[1] == InternetProtocolFamily.IPv6)
                       ? ResolvedAddressTypes.IPV4_PREFERRED
                       : ResolvedAddressTypes.IPV4_ONLY;
            case IPv6:
                return (internetProtocolFamilies.length >= 2 && internetProtocolFamilies[1] == InternetProtocolFamily.IPv4)
                       ? ResolvedAddressTypes.IPV6_PREFERRED
                       : ResolvedAddressTypes.IPV6_ONLY;
            default:
                throw new IllegalArgumentException("Couldn't resolve ResolvedAddressTypes from InternetProtocolFamily array");
        }
    }


    /**
     * Starts the DNS Name Resolver for the client, which will resolve DNS queries.
     */
    public
    DnsClient start() {
        ReflectiveChannelFactory<DatagramChannel> channelFactory = new ReflectiveChannelFactory<DatagramChannel>(channelType);

        // default support is IPV4
        if (this.resolvedAddressTypes == null) {
            this.resolvedAddressTypes = ResolvedAddressTypes.IPV4_ONLY;
        }

        if (resolveCache != null && (minTtl != null || maxTtl != null || negativeTtl != null)) {
            throw new IllegalStateException("resolveCache and TTLs are mutually exclusive");
        }

        if (authoritativeDnsServerCache != null && (minTtl != null || maxTtl != null || negativeTtl != null)) {
            throw new IllegalStateException("authoritativeDnsServerCache and TTLs are mutually exclusive");
        }

        DnsCache resolveCache = this.resolveCache != null ? this.resolveCache : newCache();
        DnsCache authoritativeDnsServerCache = this.authoritativeDnsServerCache != null ? this.authoritativeDnsServerCache : newCache();

        resolver = new DnsNameResolver(eventLoopGroup.next(),
                                       channelFactory,
                                       resolveCache,
                                       authoritativeDnsServerCache,
                                       dnsQueryLifecycleObserverFactory,
                                       queryTimeoutMillis,
                                       resolvedAddressTypes,
                                       recursionDesired,
                                       maxQueriesPerResolve,
                                       traceEnabled,
                                       maxPayloadSize,
                                       hostsFileEntriesResolver,
                                       dnsServerAddressStreamProvider,
                                       searchDomains,
                                       ndots,
                                       decodeIdn);

        return this;
    }

    /**
     * Clears the DNS resolver cache
     */
    public
    void reset() {
        if (resolver == null) {
            start();
        }

        clearResolver();
    }

    private
    void clearResolver() {
        resolver.resolveCache()
                .clear();
    }

    @Override
    protected
    void stopExtraActions() {
        if (resolver != null) {
            clearResolver();

            resolver.close(); // also closes the UDP channel that DNS client uses
        }
    }


    /**
     * Resolves a specific hostname A/AAAA record.
     *
     * @param hostname the hostname, ie: google.com, that you want to resolve
     * @return the list of resolved InetAddress or null
     * @throws UnknownHostException if the hostname cannot be resolved
     */
    public
    List<InetAddress> resolve(String hostname) throws UnknownHostException {
        if (resolver == null) {
            start();
        }

        // use "resolve", since it handles A/AAAA records + redirects correctly
        final Future<List<InetAddress>> resolve = resolver.resolveAll(hostname);
        final Future<List<InetAddress>> result = resolve.awaitUninterruptibly();

        // now return whatever value we had
        if (result.isSuccess() && result.isDone()) {
            try {
                List<InetAddress> now = result.getNow();
                return now;
            } catch (Exception e) {
                logger.error("Could not ask question to DNS server", e);
                return null;
            }
        }

        logger.error("Could not ask question to DNS server for A/AAAA record: {}", hostname);

        UnknownHostException cause = (UnknownHostException) result.cause();
        if (cause != null) {
            throw cause;
        }

        return null;
    }

    /**
     * Resolves a specific hostname record, of the specified type (PTR, MX, TXT, etc)
     * <p/>
     * <p/>
     * Note: PTR queries absolutely MUST end in '.in-addr.arpa' in order for the DNS server to understand it.
     * -- because of this, we will automatically fix this in case that clients are unaware of this requirement
     * <p/>
     * <p/>
     * Note: A/AAAA queries absolutely MUST end in a '.' -- because of this we will automatically fix this in case that clients are
     * unaware of this requirement
     *
     * @param hostname the hostname, ie: google.com, that you want to resolve
     * @param type     the DnsRecordType you want to resolve (PTR, MX, TXT, etc)
     * @return the DnsRecords or null if there was an error resolving the hostname
     *
     * @throws @throws UnknownHostException if the hostname cannot be resolved
     */
    @SuppressWarnings({"unchecked", "Duplicates"})
    public
    DnsRecord[] query(String hostname, final int type) throws UnknownHostException {
        if (resolver == null) {
            start();
        }

        if (type == DnsRecordType.PTR && !hostname.endsWith(ptrSuffix)) {
            // PTR absolutely MUST end in '.in-addr.arpa' in order for the DNS server to understand it.
            // in this case, hostname is an ip address
            hostname += ptrSuffix;
        }

        // we use our own resolvers
        DnsQuestion dnsMessage = DnsQuestion.newQuery(hostname, type, recursionDesired);

        final Future<AddressedEnvelope<DnsResponse, InetSocketAddress>> query = resolver.query(dnsMessage);
        final Future<AddressedEnvelope<DnsResponse, InetSocketAddress>> result = query.awaitUninterruptibly();

        // now return whatever value we had
        if (result.isSuccess() && result.isDone()) {
            AddressedEnvelope<DnsResponse, InetSocketAddress> envelope = result.getNow();
            DnsResponse response = envelope.content();
            try {
                final int code = response.getHeader().getRcode();
                if (code == DnsResponseCode.NOERROR) {
                    return response.getSectionArray(DnsSection.ANSWER);
                }

                logger.error("Could not ask question to DNS server: Error code {} for type: {} - {}",
                             code, type, DnsRecordType.string(type));

                logger.error("Could not ask question to DNS server: Error code {}", code);
                return null;
            } finally {
                response.release();
            }
        }

        logger.error("Could not ask question to DNS server for type: {}", DnsRecordType.string(type));

        UnknownHostException cause = (UnknownHostException) result.cause();
        if (cause != null) {
            throw cause;
        }

        return null;
    }
}

