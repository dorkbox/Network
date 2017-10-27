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

import static io.netty.resolver.dns.DnsServerAddressStreamProviders.platformDefault;
import static io.netty.util.internal.ObjectUtil.checkNotNull;
import static io.netty.util.internal.ObjectUtil.intValue;

import java.net.IDN;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;

import dorkbox.network.connection.EndPoint;
import dorkbox.network.dns.decoder.DomainDecoder;
import dorkbox.network.dns.decoder.MailExchangerDecoder;
import dorkbox.network.dns.decoder.RecordDecoder;
import dorkbox.network.dns.decoder.ServiceDecoder;
import dorkbox.network.dns.decoder.StartOfAuthorityDecoder;
import dorkbox.network.dns.decoder.TextDecoder;
import dorkbox.network.dns.record.MailExchangerRecord;
import dorkbox.network.dns.record.ServiceRecord;
import dorkbox.network.dns.record.StartOfAuthorityRecord;
import dorkbox.util.NamedThreadFactory;
import dorkbox.util.OS;
import dorkbox.util.Property;
import io.netty.buffer.ByteBuf;
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
import io.netty.handler.codec.dns.DefaultDnsQuestion;
import io.netty.handler.codec.dns.DnsPtrRecord;
import io.netty.handler.codec.dns.DnsRawRecord;
import io.netty.handler.codec.dns.DnsRecord;
import io.netty.handler.codec.dns.DnsRecordType;
import io.netty.handler.codec.dns.DnsResponse;
import io.netty.handler.codec.dns.DnsResponseCode;
import io.netty.handler.codec.dns.DnsSection;
import io.netty.resolver.HostsFileEntriesResolver;
import io.netty.resolver.ResolvedAddressTypes;
import io.netty.resolver.dns.DefaultDnsCache;
import io.netty.resolver.dns.DefaultDnsServerAddressStreamProvider;
import io.netty.resolver.dns.DnsCache;
import io.netty.resolver.dns.DnsNameResolver;
import io.netty.resolver.dns.DnsNameResolverAccess;
import io.netty.resolver.dns.DnsQueryLifecycleObserverFactory;
import io.netty.resolver.dns.DnsServerAddressStreamProvider;
import io.netty.resolver.dns.NoopDnsQueryLifecycleObserverFactory;
import io.netty.resolver.dns.SequentialDnsServerAddressStreamProvider;
import io.netty.util.concurrent.Future;
import io.netty.util.internal.PlatformDependent;

/**
 * A DnsClient for resolving DNS name, with reasonably good defaults.
 */
@SuppressWarnings({"unused", "WeakerAccess"})
public
class DnsClient extends EndPoint {

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
    String getPublicIp() {
        final InetSocketAddress dnsServer = new InetSocketAddress("208.67.222.222", 53);  // openDNS

        DnsClient dnsClient = new DnsClient(dnsServer);
        final String resolve = dnsClient.resolve("myip.opendns.com", DnsRecordType.A);

        dnsClient.stop();

        return resolve;
    }

    private final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(getClass());
    private Class<? extends DatagramChannel> channelType;

    private DnsNameResolver resolver;
    private final Map<DnsRecordType, RecordDecoder<?>> customDecoders = new HashMap<DnsRecordType, RecordDecoder<?>>();

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

    private ResolvedAddressTypes resolvedAddressTypes = DnsNameResolverAccess.getDefaultResolvedAddressTypes();
    private boolean recursionDesired = true;
    private int maxQueriesPerResolve = 16;

    private boolean traceEnabled;
    private int maxPayloadSize = 4096;

    private boolean optResourceEnabled = true;

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
        this(Collections.singletonList(new InetSocketAddress(nameServerAddresses, 53)));
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

        // NOTE: A/AAAA use the built-in decoder

        customDecoders.put(DnsRecordType.MX, new MailExchangerDecoder());
        customDecoders.put(DnsRecordType.TXT, new TextDecoder());
        customDecoders.put(DnsRecordType.SRV, new ServiceDecoder());

        RecordDecoder<?> decoder = new DomainDecoder();
        customDecoders.put(DnsRecordType.NS, decoder);
        customDecoders.put(DnsRecordType.CNAME, decoder);
        customDecoders.put(DnsRecordType.PTR, decoder);
        customDecoders.put(DnsRecordType.SOA, new StartOfAuthorityDecoder());

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
     * Enable the automatic inclusion of a optional records that tries to give the remote DNS server a hint about
     * how much data the resolver can read per response. Some DNSServer may not support this and so fail to answer
     * queries. If you find problems you may want to disable this.
     *
     * @param optResourceEnabled if optional records inclusion is enabled
     *
     * @return {@code this}
     */
    public
    DnsClient optResourceEnabled(boolean optResourceEnabled) {
        this.optResourceEnabled = optResourceEnabled;
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
            return DnsNameResolverAccess.getDefaultResolvedAddressTypes();
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

        resolver = new DnsNameResolver(eventLoopGroup.next(), channelFactory, resolveCache, authoritativeDnsServerCache,
                                       dnsQueryLifecycleObserverFactory, queryTimeoutMillis, resolvedAddressTypes, recursionDesired,
                                       maxQueriesPerResolve, traceEnabled, maxPayloadSize, optResourceEnabled, hostsFileEntriesResolver,
                                       dnsServerAddressStreamProvider, searchDomains, ndots, decodeIdn);

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
        clearResolver();

        if (resolver != null) {
            resolver.close(); // also closes the UDP channel that DNS client uses
        }
    }


    /**
     * Resolves a specific hostname A record
     *
     * @param hostname the hostname, ie: google.com, that you want to resolve
     */
    public
    String resolve(String hostname) {
        if (resolver == null) {
            start();
        }

        if (resolver.resolvedAddressTypes() == ResolvedAddressTypes.IPV4_ONLY) {
            return resolve(hostname, DnsRecordType.A);
        }
        else {
            return resolve(hostname, DnsRecordType.AAAA);
        }
    }

    /**
     * Resolves a specific hostname record, of the specified type (PTR, MX, TXT, etc)
     * <p/>
     * Note that PTR absolutely MUST end in '.in-addr.arpa' in order for the DNS server to understand it.
     * -- because of this, we will automatically fix this in case that clients are unaware of this requirement
     *
     * @param hostname the hostname, ie: google.com, that you want to resolve
     * @param type     the DnsRecordType you want to resolve (PTR, MX, TXT, etc)
     * @return null indicates there was an error resolving the hostname
     */
    @SuppressWarnings("unchecked")
    private
    <T> T resolve(String hostname, DnsRecordType type) {
        if (resolver == null) {
            start();
        }

        hostname = IDN.toASCII(hostname);
        final int value = type.intValue();


        // we can use the included DNS resolver.
        if (value == DnsRecordType.A.intValue() || value == DnsRecordType.AAAA.intValue()) {
            // use "resolve", since it handles A/AAAA records
            final Future<InetAddress> resolve = resolver.resolve(hostname); // made up port, because it doesn't matter
            final Future<InetAddress> result = resolve.awaitUninterruptibly();

            // now return whatever value we had
            if (result.isSuccess() && result.isDone()) {
                try {
                    return (T) result.getNow().getHostAddress();
                } catch (Exception e) {
                    logger.error("Could not ask question to DNS server", e);
                    return null;
                }
            }

            Throwable cause = result.cause();

            Logger logger2 = this.logger;
            if (logger2.isDebugEnabled() && cause != null) {
                logger2.error("Could not ask question to DNS server.", cause);
                return null;
            }
        }
        else {
            // we use our own resolvers

            final Future<AddressedEnvelope<DnsResponse, InetSocketAddress>> query = resolver.query(new DefaultDnsQuestion(hostname, type));
            final Future<AddressedEnvelope<DnsResponse, InetSocketAddress>> result = query.awaitUninterruptibly();

            // now return whatever value we had
            if (result.isSuccess() && result.isDone()) {
                AddressedEnvelope<DnsResponse, InetSocketAddress> envelope = result.getNow();
                DnsResponse response = envelope.content();
                try {
                    final DnsResponseCode code = response.code();
                    if (code == DnsResponseCode.NOERROR) {
                        final RecordDecoder<?> decoder = customDecoders.get(type);
                        if (decoder != null) {
                            final int answerCount = response.count(DnsSection.ANSWER);
                            for (int i = 0; i < answerCount; i++) {
                                final DnsRecord r = response.recordAt(DnsSection.ANSWER, i);
                                if (r.type() == type) {
                                    // can be either RAW or it will be a PTR record
                                    if (r instanceof DnsRawRecord) {
                                        ByteBuf recordContent = ((DnsRawRecord) r).content();
                                        return (T) decoder.decode(r, recordContent);
                                    } else if (r instanceof DnsPtrRecord) {
                                        return (T) ((DnsPtrRecord)r).hostname();
                                    }
                                }
                            }
                        }

                        logger.error("Could not ask question to DNS server: Issue with decoder for type: {}", type);
                        return null;
                    }

                    logger.error("Could not ask question to DNS server: Error code {}", code);
                    return null;
                } finally {
                    response.release();
                }
            }
            else {
                Throwable cause = result.cause();

                Logger logger2 = this.logger;
                if (logger2.isDebugEnabled() && cause != null) {
                    logger2.error("Could not ask question to DNS server.", cause);
                    return null;
                }
            }
        }

        logger.error("Could not ask question to DNS server for type: {}", type.getClass().getSimpleName());
        return null;
    }

    public
    ServiceRecord resolveSRV(final String hostname) {
        return resolve(hostname, DnsRecordType.SRV);
    }

    public
    MailExchangerRecord resolveMX(final String hostname) {
        return resolve(hostname, DnsRecordType.MX);
    }

    public
    String resolveCNAME(final String hostname) {
        return resolve(hostname, DnsRecordType.CNAME);
    }

    public
    String resolveNS(final String hostname) {
        return resolve(hostname, DnsRecordType.NS);
    }

    public
    String resolvePTR(String hostname) {
        // PTR absolutely MUST end in ".in-addr.arpa"
        if (!hostname.endsWith(ptrSuffix)) {
            hostname += ptrSuffix;
        }

        return resolve(hostname, DnsRecordType.PTR);
    }

    public
    String resolveA(final String hostname) {
        return resolve(hostname, DnsRecordType.A);
    }

    public
    String resolveAAAA(final String hostname) {
        return resolve(hostname, DnsRecordType.AAAA);
    }

    public
    StartOfAuthorityRecord resolveSOA(final String hostname) {
        return resolve(hostname, DnsRecordType.SOA);
    }

    public
    List<String> resolveTXT(final String hostname) {
        return resolve(hostname, DnsRecordType.TXT);
    }
}

