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

import dorkbox.network.dns.decoder.*;
import dorkbox.network.dns.record.MailExchangerRecord;
import dorkbox.network.dns.record.ServiceRecord;
import dorkbox.network.dns.record.StartOfAuthorityRecord;
import dorkbox.util.NamedThreadFactory;
import dorkbox.util.OS;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.channel.AddressedEnvelope;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.EpollDatagramChannel;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.oio.OioEventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.InternetProtocolFamily;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.oio.OioDatagramChannel;
import io.netty.handler.codec.dns.*;
import io.netty.resolver.dns.DnsNameResolver;
import io.netty.util.concurrent.Future;
import io.netty.util.internal.PlatformDependent;
import org.slf4j.Logger;

import java.net.IDN;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.AccessControlException;
import java.util.*;

/**
 * for now, we only support ipv4
 */
@SuppressWarnings("unused")
public
class DnsClient {
    static {
        try {
            // doesn't work in eclipse.
            // Needed for NIO selectors on Android 2.2, and to force IPv4.
            System.setProperty("java.net.preferIPv4Stack", Boolean.TRUE.toString());
            System.setProperty("java.net.preferIPv6Addresses", Boolean.FALSE.toString());
        } catch (AccessControlException ignored) {
        }
    }

    // @formatter:off
    public static final List<InetSocketAddress> DNS_SERVER_LIST = Arrays.asList(
        new InetSocketAddress("8.8.8.8", 53), // Google Public DNS
        new InetSocketAddress("8.8.4.4", 53),
        new InetSocketAddress("208.67.222.222", 53), // OpenDNS
        new InetSocketAddress("208.67.220.220", 53),
        new InetSocketAddress("37.235.1.174", 53), // FreeDNS
        new InetSocketAddress("37.235.1.177", 53)
    );
    // @formatter:on


    private static final String ptrSuffix = ".in-addr.arpa";

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

    /**
     * Creates a new DNS client, using the provided server (default port 53) for DNS query resolution
     *
     * @param nameServerAddresses the server to receive your DNS questions.
     */
    public
    DnsClient(final String nameServerAddresses) {
        this(Collections.singletonList(new InetSocketAddress(nameServerAddresses, 53)));
    }

    /**
     * Creates a new DNS client, using the provided server for DNS query resolution
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
     * @param nameServerAddresses the list of servers to receive your DNS questions, until it succeeds
     */
    public
    DnsClient(Collection<InetSocketAddress> nameServerAddresses) {
        EventLoopGroup group;
        Class<? extends DatagramChannel> channelType;

        final String threadName = "DnsClient";

        // setup the thread group to easily ID what the following threads belong to (and their spawned threads...)
        SecurityManager s = System.getSecurityManager();
        ThreadGroup nettyGroup = new ThreadGroup(s != null
                                                 ? s.getThreadGroup()
                                                 : Thread.currentThread()
                                                         .getThreadGroup(), threadName);

        if (PlatformDependent.isAndroid()) {
            // android ONLY supports OIO (not NIO)
            group = new OioEventLoopGroup(1, new NamedThreadFactory(threadName + "-UDP", nettyGroup));
            channelType = OioDatagramChannel.class;
        }
        else if (OS.isLinux()) {
            // JNI network stack is MUCH faster (but only on linux)
            group = new EpollEventLoopGroup(1, new NamedThreadFactory(threadName + "-UDP", nettyGroup));
            channelType = EpollDatagramChannel.class;
        }
        else {
            group = new NioEventLoopGroup(1, new NamedThreadFactory(threadName + "-UDP", nettyGroup));
            channelType = NioDatagramChannel.class;
        }

        resolver = new DnsNameResolver(group.next(), channelType, nameServerAddresses);
        resolver.setMaxTriesPerQuery(nameServerAddresses.size());
        // for now, we only support ipv4
        resolver.setResolveAddressTypes(InternetProtocolFamily.IPv4);

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

    public
    void reset() {
        resolver.clearCache();
    }

    public
    void setTtl(int min, int max) {
        resolver.setTtl(min, max);
    }

    /**
     * Resolves a specific hostname A record
     *
     * @param hostname the hostname, ie: google.com, that you want to resolve
     */
    public
    String resolve(String hostname) {
        if (resolver.resolveAddressTypes()
                    .get(0) == InternetProtocolFamily.IPv4) {
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
            final Future<InetSocketAddress> resolve = resolver.resolve(hostname, 1025); // made up port, because it doesn't matter
            final Future<InetSocketAddress> result = resolve.awaitUninterruptibly();

            // now return whatever value we had
            if (result.isSuccess() && result.isDone()) {
                try {
                    final InetAddress address = result.getNow()
                                                      .getAddress();

                    return (T) address.getHostAddress();
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

                final DnsResponseCode code = response.code();
                if (code == DnsResponseCode.NOERROR) {
                    final RecordDecoder<?> decoder = customDecoders.get(type);
                    if (decoder != null) {
                        final int answerCount = response.count(DnsSection.ANSWER);
                        for (int i = 0; i < answerCount; i++) {
                            final DnsRecord r = response.recordAt(DnsSection.ANSWER, i);
                            if (r.type() == type) {
                                ByteBuf recordContent = ((ByteBufHolder) r).content();

                                return (T) decoder.decode(r, recordContent);
                            }
                        }
                    }

                    logger.error("Could not ask question to DNS server: Issue with decoder for type: {}", type);
                    return null;
                }

                logger.error("Could not ask question to DNS server: Error code {}", code);
                return null;
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
     * Safely closes all associated resources/threads/connections
     */
    public
    void stop() {
        reset();
        resolver.close();
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
