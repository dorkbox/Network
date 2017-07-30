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

import java.net.IDN;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.AccessControlException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

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
import dorkbox.util.Version;
import io.netty.buffer.ByteBuf;
import io.netty.channel.AddressedEnvelope;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.ReflectiveChannelFactory;
import io.netty.channel.epoll.EpollDatagramChannel;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.oio.OioEventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
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
import io.netty.resolver.ResolvedAddressTypes;
import io.netty.resolver.dns.DefaultDnsServerAddressStreamProvider;
import io.netty.resolver.dns.DnsNameResolver;
import io.netty.resolver.dns.DnsNameResolverBuilder;
import io.netty.resolver.dns.DnsServerAddressStreamProvider;
import io.netty.resolver.dns.SequentialDnsServerAddressStreamProvider;
import io.netty.util.concurrent.Future;
import io.netty.util.internal.PlatformDependent;

/**
 * for now, we only support ipv4
 */
@SuppressWarnings("unused")
public
class DnsClient {

    // duplicated in EndPoint
    static {
        //noinspection Duplicates
        try {
            // doesn't work when running from inside eclipse.
            // Needed for NIO selectors on Android 2.2, and to force IPv4.
            System.setProperty("java.net.preferIPv4Stack", Boolean.TRUE.toString());
            System.setProperty("java.net.preferIPv6Addresses", Boolean.FALSE.toString());

            // java6 has stack overflow problems when loading certain classes in it's classloader. The result is a StackOverflow when
            // loading them normally
            if (OS.javaVersion == 6) {
                if (PlatformDependent.hasUnsafe()) {
                    PlatformDependent.newFixedMpscQueue(8);
                }
            }
        } catch (AccessControlException ignored) {
        }
    }

    /**
     * This is a list of all of the public DNS servers to query, when submitting DNS queries
     */
    // @formatter:off
    @Property
    public static List<InetSocketAddress> DNS_SERVER_LIST = Arrays.asList(
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
    Version getVersion() {
        return new Version("1.24");
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
    private final DnsNameResolver resolver;

    private final Map<DnsRecordType, RecordDecoder<?>> customDecoders = new HashMap<DnsRecordType, RecordDecoder<?>>();
    private ThreadGroup threadGroup;
    public static final String THREAD_NAME = "DnsClient";
    private EventLoopGroup eventLoopGroup;

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
     * Creates a new DNS client, with a cache that will obey the TTL of the response
     *
     * @param nameServerAddresses the list of servers to receive your DNS questions, until it succeeds
     */
    public
    DnsClient(Collection<InetSocketAddress> nameServerAddresses) {
        this(nameServerAddresses, 0, Integer.MAX_VALUE);
    }

    /**
     * Creates a new DNS client.
     *
     * The default TTL value is {@code 0} and {@link Integer#MAX_VALUE}, which practically tells this resolver to
     * respect the TTL from the DNS server.
     *
     * @param nameServerAddresses the list of servers to receive your DNS questions, until it succeeds
     * @param minTtl the minimum TTL
     * @param maxTtl the maximum TTL
     */
    public
    DnsClient(Collection<InetSocketAddress> nameServerAddresses, int minTtl, int maxTtl) {
        Class<? extends DatagramChannel> channelType;

        // setup the thread group to easily ID what the following threads belong to (and their spawned threads...)
        SecurityManager s = System.getSecurityManager();
        threadGroup = new ThreadGroup(s != null
                                      ? s.getThreadGroup()
                                      : Thread.currentThread()
                                              .getThreadGroup(), THREAD_NAME);
        threadGroup.setDaemon(true);

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


        List<DnsServerAddressStreamProvider> list = new ArrayList<DnsServerAddressStreamProvider>(nameServerAddresses.size());

        DnsNameResolverBuilder builder = new DnsNameResolverBuilder(eventLoopGroup.next());
        builder.channelFactory(new ReflectiveChannelFactory<DatagramChannel>(channelType))
               .channelType(channelType)
               .ttl(minTtl, maxTtl)
               .resolvedAddressTypes(ResolvedAddressTypes.IPV4_ONLY) // for now, we only support ipv4
               .nameServerProvider(new SequentialDnsServerAddressStreamProvider(nameServerAddresses));

        resolver = builder.build();


        // A/AAAA use the built-in decoder

        customDecoders.put(DnsRecordType.MX, new MailExchangerDecoder());
        customDecoders.put(DnsRecordType.TXT, new TextDecoder());
        customDecoders.put(DnsRecordType.SRV, new ServiceDecoder());

        RecordDecoder<?> decoder = new DomainDecoder();
        customDecoders.put(DnsRecordType.NS, decoder);
        customDecoders.put(DnsRecordType.CNAME, decoder);
        customDecoders.put(DnsRecordType.PTR, decoder);
        customDecoders.put(DnsRecordType.SOA, new StartOfAuthorityDecoder());
    }

    /**
     * Resolves a specific hostname A record
     *
     * @param hostname the hostname, ie: google.com, that you want to resolve
     */
    public
    String resolve(String hostname) {
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


    /**
     * Clears the DNS resolver cache
     */
    public
    void reset() {
        resolver.resolveCache()
                .clear();
    }


    /**
     * Safely closes all associated resources/threads/connections
     */
    public
    void stop() {
        // we also want to stop the thread group (but NOT in our current thread!)
        if (Thread.currentThread()
                  .getThreadGroup()
                  .getName()
                  .equals(THREAD_NAME)) {

            Thread thread = new Thread(new Runnable() {
                @Override
                public
                void run() {
                    DnsClient.this.stopInThread();
                }
            });
            thread.setDaemon(false);
            thread.setName("DnsClient Shutdown");
            thread.start();
        }
        else {
            stopInThread();
        }
    }


    private
    void stopInThread() {
        reset();
        resolver.close(); // also closes the UDP channel that DNS client uses

        // Sometimes there might be "lingering" connections (ie, halfway though registration) that need to be closed.
        long maxShutdownWaitTimeInMilliSeconds = EndPoint.maxShutdownWaitTimeInMilliSeconds;



        // we want to WAIT until after the event executors have completed shutting down.
        List<Future<?>> shutdownThreadList = new LinkedList<Future<?>>();

        // now wait it them to finish!
        // It can take a few seconds to shut down the executor. This will affect unit testing, where connections are quickly created/stopped
        eventLoopGroup.shutdownGracefully(maxShutdownWaitTimeInMilliSeconds, maxShutdownWaitTimeInMilliSeconds * 4, TimeUnit.MILLISECONDS)
                      .syncUninterruptibly();

        threadGroup.interrupt();
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

