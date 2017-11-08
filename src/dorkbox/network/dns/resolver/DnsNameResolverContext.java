/*
 * Copyright 2014 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package dorkbox.network.dns.resolver;

import static java.lang.Math.min;
import static java.util.Collections.unmodifiableList;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import dorkbox.network.dns.DnsQuestion;
import dorkbox.network.dns.DnsResponse;
import dorkbox.network.dns.constants.DnsRecordType;
import dorkbox.network.dns.constants.DnsResponseCode;
import dorkbox.network.dns.constants.DnsSection;
import dorkbox.network.dns.records.AAAARecord;
import dorkbox.network.dns.records.ARecord;
import dorkbox.network.dns.records.CNAMERecord;
import dorkbox.network.dns.records.DnsMessage;
import dorkbox.network.dns.records.DnsRecord;
import dorkbox.network.dns.records.NSRecord;
import dorkbox.network.dns.resolver.addressProvider.DnsServerAddressStream;
import dorkbox.network.dns.resolver.addressProvider.DnsServerAddresses;
import dorkbox.network.dns.resolver.cache.DnsCache;
import dorkbox.network.dns.resolver.cache.DnsCacheEntry;
import io.netty.channel.AddressedEnvelope;
import io.netty.channel.ChannelPromise;
import io.netty.channel.socket.InternetProtocolFamily;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;
import io.netty.util.concurrent.Promise;
import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.StringUtil;
import io.netty.util.internal.ThrowableUtil;

abstract
class DnsNameResolverContext<T> {

    private static final FutureListener<AddressedEnvelope<DnsResponse, InetSocketAddress>> RELEASE_RESPONSE = new FutureListener<AddressedEnvelope<DnsResponse, InetSocketAddress>>() {
        @Override
        public
        void operationComplete(Future<AddressedEnvelope<DnsResponse, InetSocketAddress>> future) {
            if (future.isSuccess()) {
                future.getNow()
                      .release();
            }
        }
    };

    private static final RuntimeException NXDOMAIN_QUERY_FAILED_EXCEPTION =
            ThrowableUtil.unknownStackTrace(new RuntimeException("No answer found and NXDOMAIN response code returned"),
                                            DnsNameResolverContext.class,
                                            "onResponse(..)");

    private static final RuntimeException CNAME_NOT_FOUND_QUERY_FAILED_EXCEPTION =
            ThrowableUtil.unknownStackTrace(new RuntimeException("No matching CNAME record found"),
                                            DnsNameResolverContext.class,
                                            "onResponseCNAME(..)");

    private static final RuntimeException NO_MATCHING_RECORD_QUERY_FAILED_EXCEPTION =
            ThrowableUtil.unknownStackTrace(new RuntimeException("No matching record type found"),
                                            DnsNameResolverContext.class,
                                            "onResponseAorAAAA(..)");

    private static final RuntimeException UNRECOGNIZED_TYPE_QUERY_FAILED_EXCEPTION =
            ThrowableUtil.unknownStackTrace(new RuntimeException("Response type was unrecognized"),
                                            DnsNameResolverContext.class,
                                            "onResponse(..)");

    private static final RuntimeException NAME_SERVERS_EXHAUSTED_EXCEPTION =
            ThrowableUtil.unknownStackTrace(new RuntimeException("No name servers returned an answer"),
                                            DnsNameResolverContext.class,
                                            "tryToFinishResolve(..)");

    private final DnsNameResolver parent;
    private final DnsServerAddressStream nameServerAddrs;
    private final String hostname;
    private final DnsCache resolveCache;
    private final int maxAllowedQueries;
    private final InternetProtocolFamily[] resolvedInternetProtocolFamilies;

    private final Set<Future<AddressedEnvelope<DnsResponse, InetSocketAddress>>> queriesInProgress = Collections.newSetFromMap(new IdentityHashMap<Future<AddressedEnvelope<DnsResponse, InetSocketAddress>>, Boolean>());

    private List<DnsCacheEntry> resolvedEntries;
    private int allowedQueries;
    private boolean triedCNAME;

    DnsNameResolverContext(DnsNameResolver parent,
                           String hostname,
                           DnsCache resolveCache,
                           DnsServerAddressStream nameServerAddrs) {
        this.parent = parent;
        this.hostname = hostname;
        this.resolveCache = resolveCache;

        this.nameServerAddrs = ObjectUtil.checkNotNull(nameServerAddrs, "nameServerAddrs");

        maxAllowedQueries = parent.maxQueriesPerResolve();
        resolvedInternetProtocolFamilies = parent.resolvedInternetProtocolFamiliesUnsafe();
        allowedQueries = maxAllowedQueries;
    }

    void resolve(final Promise<T> promise) {
        if (parent.searchDomains().length == 0 || parent.ndots() == 0 || StringUtil.endsWith(hostname, '.')) {
            internalResolve(promise);
        }
        else {
            int dots = 0;
            for (int idx = hostname.length() - 1; idx >= 0; idx--) {
                if (hostname.charAt(idx) == '.' && ++dots >= parent.ndots()) {
                    internalResolve(promise);
                    return;
                }
            }

            doSearchDomainQuery(0, new FutureListener<T>() {
                private int count = 1;

                @Override
                public
                void operationComplete(Future<T> future) throws Exception {
                    if (future.isSuccess()) {
                        promise.trySuccess(future.getNow());
                    }
                    else if (count < parent.searchDomains().length) {
                        doSearchDomainQuery(count++, this);
                    }
                    else {
                        promise.tryFailure(new SearchDomainUnknownHostException(future.cause(), hostname));
                    }
                }
            });
        }
    }

    private static final
    class SearchDomainUnknownHostException extends UnknownHostException {
        SearchDomainUnknownHostException(Throwable cause, String originalHostname) {
            super("Search domain query failed. Original hostname: '" + originalHostname + "' " + cause.getMessage());
            setStackTrace(cause.getStackTrace());
        }

        @Override
        public
        Throwable fillInStackTrace() {
            return this;
        }
    }

    private
    void doSearchDomainQuery(int count, FutureListener<T> listener) {
        DnsNameResolverContext<T> nextContext = newResolverContext(parent,
                                                                   hostname + '.' + parent.searchDomains()[count],
                                                                   resolveCache,
                                                                   nameServerAddrs);
        Promise<T> nextPromise = parent.executor()
                                       .newPromise();
        nextContext.internalResolve(nextPromise);
        nextPromise.addListener(listener);
    }

    private
    void internalResolve(Promise<T> promise) {
        DnsServerAddressStream nameServerAddressStream = getNameServers(hostname);

        int[] recordTypes = parent.resolveRecordTypes();
        assert recordTypes.length > 0;
        final int end = recordTypes.length - 1;

        for (int i = 0; i < end; ++i) {
            if (!resolveQuery(hostname, recordTypes[i], nameServerAddressStream.duplicate(), promise)) {
                return;
            }
        }

        resolveQuery(hostname, recordTypes[end], nameServerAddressStream, promise);
    }

    /**
     * Add an authoritative nameserver to the cache if its not a root server.
     */
    private
    void addNameServerToCache(AuthoritativeNameServer name, InetAddress resolved, long ttl) {
        if (!name.isRootServer()) {
            // Cache NS record if not for a root server as we should never cache for root servers.
            parent.authoritativeDnsServerCache()
                  .cache(name.domainName(), resolved, ttl, parent.ch.eventLoop());
        }
    }

    /**
     * Returns the {@link DnsServerAddressStream} that was cached for the given hostname or {@code null} if non
     * could be found.
     */
    private
    DnsServerAddressStream getNameServersFromCache(String hostname) {
        int len = hostname.length();

        if (len == 0) {
            // We never cache for root servers.
            return null;
        }

        // We always store in the cache with a trailing '.'.
        if (hostname.charAt(len - 1) != '.') {
            hostname += ".";
        }

        int idx = hostname.indexOf('.');
        if (idx == hostname.length() - 1) {
            // We are not interested in handling '.' as we should never serve the root servers from cache.
            return null;
        }

        // We start from the closed match and then move down.
        for (; ; ) {
            // Skip '.' as well.
            hostname = hostname.substring(idx + 1);

            int idx2 = hostname.indexOf('.');
            if (idx2 <= 0 || idx2 == hostname.length() - 1) {
                // We are not interested in handling '.TLD.' as we should never serve the root servers from cache.
                return null;
            }
            idx = idx2;

            List<DnsCacheEntry> entries = parent.authoritativeDnsServerCache().get(hostname);
            if (entries != null && !entries.isEmpty()) {
                return DnsServerAddresses.sequential(new DnsCacheIterable(entries))
                                         .stream();
            }
        }
    }

    private final
    class DnsCacheIterable implements Iterable<InetSocketAddress> {
        private final List<DnsCacheEntry> entries;

        DnsCacheIterable(List<DnsCacheEntry> entries) {
            this.entries = entries;
        }

        @Override
        public
        Iterator<InetSocketAddress> iterator() {
            return new Iterator<InetSocketAddress>() {
                Iterator<DnsCacheEntry> entryIterator = entries.iterator();

                @Override
                public
                boolean hasNext() {
                    return entryIterator.hasNext();
                }

                @Override
                public
                InetSocketAddress next() {
                    InetAddress address = entryIterator.next()
                                                       .address();
                    return new InetSocketAddress(address, parent.dnsRedirectPort(address));
                }

                @Override
                public
                void remove() {
                    entryIterator.remove();
                }
            };
        }
    }

    private
    void resolveQuery(final DnsServerAddressStream nameServerAddrStream,
                      final int nameServerAddrStreamIndex,
                      final DnsQuestion question,
                      final Promise<T> promise) {

        resolveQuery(nameServerAddrStream,
                     nameServerAddrStreamIndex,
                     question,
                     parent.dnsQueryLifecycleObserverFactory().newDnsQueryLifecycleObserver(question),
                     promise);
    }

    private
    void resolveQuery(final DnsServerAddressStream nameServerAddrStream,
                      final int nameServerAddrStreamIndex,
                      final DnsQuestion question,
                      final DnsQueryLifecycleObserver queryLifecycleObserver,
                      final Promise<T> promise) {
        // question should have refCnt=2
        if (nameServerAddrStreamIndex >= nameServerAddrStream.size() || allowedQueries == 0 || promise.isCancelled()) {
            tryToFinishResolve(nameServerAddrStream, nameServerAddrStreamIndex, question, queryLifecycleObserver, promise);
            return;
        }

        --allowedQueries;

        final InetSocketAddress nameServerAddr = nameServerAddrStream.next();
        final ChannelPromise writePromise = parent.ch.newPromise();
        final Future<AddressedEnvelope<DnsResponse, InetSocketAddress>> f =
                parent.query0(nameServerAddr,
                              question,
                              writePromise,
                              parent.ch.eventLoop().<AddressedEnvelope<DnsResponse, InetSocketAddress>>newPromise());

        queriesInProgress.add(f);

        queryLifecycleObserver.queryWritten(nameServerAddr, writePromise);

        f.addListener(new FutureListener<AddressedEnvelope<DnsResponse, InetSocketAddress>>() {
            @Override
            public
            void operationComplete(Future<AddressedEnvelope<DnsResponse, InetSocketAddress>> future) {
                // future.result() should have refCnt=2
                // question should have refCnt=1
                queriesInProgress.remove(future);

                if (promise.isDone() || future.isCancelled()) {
                    queryLifecycleObserver.queryCancelled(allowedQueries);
                    return;
                }

                AddressedEnvelope<DnsResponse, InetSocketAddress> envelope = future.getNow();
                try {
                    if (future.isSuccess()) {
                        onResponse(nameServerAddrStream,
                                   nameServerAddrStreamIndex,
                                   question,
                                   envelope,
                                   queryLifecycleObserver,
                                   promise);
                    }
                    else {
                        // Server did not respond or I/O error occurred; try again.
                        queryLifecycleObserver.queryFailed(future.cause());

                        // query uses the question again...
                        question.retain();
                        resolveQuery(nameServerAddrStream, nameServerAddrStreamIndex + 1, question, promise);
                    }
                } finally {
                    // future.result() should have refCnt=2
                    // question should have refCnt=1
                    tryToFinishResolve(nameServerAddrStream, nameServerAddrStreamIndex, question,
                                       // queryLifecycleObserver has already been terminated at this point so we must
                                       // not allow it to be terminated again by tryToFinishResolve.
                                       NoopDnsQueryLifecycleObserver.INSTANCE, promise);
                }
            }
        });
    }

    void onResponse(final DnsServerAddressStream nameServerAddrStream,
                    final int nameServerAddrStreamIndex,
                    final DnsQuestion question,
                    AddressedEnvelope<DnsResponse, InetSocketAddress> envelope,
                    final DnsQueryLifecycleObserver queryLifecycleObserver,
                    Promise<T> promise) {

        final DnsResponse res = envelope.content();
        final int code = res.getHeader()
                            .getRcode();

        if (code == DnsResponseCode.NOERROR) {
            if (handleRedirect(question, envelope, queryLifecycleObserver, promise)) {
                // Was a redirect so return here as everything else is handled in handleRedirect(...)
                return;
            }
            final int type = question.getQuestion()
                                     .getType();

            if (type == DnsRecordType.A || type == DnsRecordType.AAAA) {
                onResponseAorAAAA(type, question, envelope, queryLifecycleObserver, promise);
            }
            else if (type == DnsRecordType.CNAME) {
                onResponseCNAME(question, envelope, queryLifecycleObserver, promise);
            }
            else {
                queryLifecycleObserver.queryFailed(UNRECOGNIZED_TYPE_QUERY_FAILED_EXCEPTION);
            }
            return;
        }

        // Retry with the next server if the server did not tell us that the domain does not exist.
        if (code != DnsResponseCode.NXDOMAIN) {
            resolveQuery(nameServerAddrStream,
                         nameServerAddrStreamIndex + 1,
                         question,
                         queryLifecycleObserver.queryNoAnswer(code),
                         promise);
        }
        else {
            queryLifecycleObserver.queryFailed(NXDOMAIN_QUERY_FAILED_EXCEPTION);
        }
    }

    /**
     * Handles a redirect answer if needed and returns {@code true} if a redirect query has been made.
     */
    private
    boolean handleRedirect(DnsQuestion question,
                           AddressedEnvelope<DnsResponse, InetSocketAddress> envelope,
                           final DnsQueryLifecycleObserver queryLifecycleObserver,
                           Promise<T> promise) {

        final DnsResponse res = envelope.content();

        // Check if we have answers, if not this may be an non authority NS and so redirects must be handled.
        DnsRecord[] answerArray = res.getSectionArray(DnsSection.ANSWER);
        if (answerArray.length == 0) {
            AuthoritativeNameServerList serverNames = extractAuthoritativeNameServers(question.getQuestion()
                                                                                              .getName()
                                                                                              .toString(), res);

            if (serverNames != null) {
                List<InetSocketAddress> nameServers = new ArrayList<InetSocketAddress>(serverNames.size());
                DnsRecord[] additionalArray = res.getSectionArray(DnsSection.ADDITIONAL);

                for (int i = 0; i < additionalArray.length; i++) {
                    final DnsRecord r = additionalArray[i];

                    if (r.getType() == DnsRecordType.A && !parent.supportsARecords() ||
                        r.getType() == DnsRecordType.AAAA && !parent.supportsAAAARecords()) {
                        continue;
                    }

                    final String recordName = r.getName()
                                               .toString();
                    AuthoritativeNameServer authoritativeNameServer = serverNames.remove(recordName);

                    if (authoritativeNameServer == null) {
                        // Not a server we are interested in.
                        continue;
                    }

                    InetAddress resolved = parseAddress(r, recordName);
                    if (resolved == null) {
                        // Could not parse it, move to the next.
                        continue;
                    }

                    nameServers.add(new InetSocketAddress(resolved, parent.dnsRedirectPort(resolved)));
                    addNameServerToCache(authoritativeNameServer, resolved, r.getTTL());
                }

                if (!nameServers.isEmpty()) {
                    resolveQuery(parent.uncachedRedirectDnsServerStream(nameServers),
                                 0,
                                 question,
                                 queryLifecycleObserver.queryRedirected(unmodifiableList(nameServers)),
                                 promise);
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Returns the {@code {@link AuthoritativeNameServerList} which were included in {@link DnsSection#AUTHORITY}
     * or {@code null} if non are found.
     */
    private static
    AuthoritativeNameServerList extractAuthoritativeNameServers(String questionName, DnsResponse res) {
        DnsRecord[] authority = res.getSectionArray(DnsSection.AUTHORITY);
        if (authority.length == 0) {
            return null;
        }

        System.err.println("TYODO");
        AuthoritativeNameServerList serverNames = new AuthoritativeNameServerList(questionName);
        for (int i = 0; i < authority.length; i++) {
            final DnsRecord dnsRecord = authority[i];
            serverNames.add(dnsRecord);
        }

        return serverNames;
    }

    private
    void onResponseAorAAAA(int qType,
                           DnsMessage question,
                           AddressedEnvelope<DnsResponse, InetSocketAddress> envelope,
                           final DnsQueryLifecycleObserver queryLifecycleObserver,
                           Promise<T> promise) {

        // We often get a bunch of CNAMES as well when we asked for A/AAAA.
        final DnsResponse response = envelope.content();
        final Map<String, String> cnames = buildAliasMap(response);

        DnsRecord[] answerArray = response.getSectionArray(DnsSection.ANSWER);

        boolean found = false;
        for (int i = 0; i < answerArray.length; i++) {
            final DnsRecord r = answerArray[i];
            final int type = r.getType();
            if (type != DnsRecordType.A && type != DnsRecordType.AAAA) {
                continue;
            }

            final String questionName = question.getQuestion()
                                                .getName()
                                                .toString();
            final String recordName = r.getName()
                                       .toString();

            // Make sure the record is for the questioned domain.
            if (!recordName.equals(questionName)) {
                // Even if the record's name is not exactly same, it might be an alias defined in the CNAME records.
                String resolved = questionName;
                do {
                    resolved = cnames.get(resolved);
                    if (recordName.equals(resolved)) {
                        break;
                    }
                } while (resolved != null);

                if (resolved == null) {
                    continue;
                }
            }

            InetAddress resolved = parseAddress(r, hostname);
            if (resolved == null) {
                continue;
            }

            if (resolvedEntries == null) {
                resolvedEntries = new ArrayList<DnsCacheEntry>(8);
            }

            final DnsCacheEntry e = new DnsCacheEntry(hostname, resolved);
            resolveCache.cache(hostname, resolved, r.getTTL(), parent.ch.eventLoop());
            resolvedEntries.add(e);
            found = true;

            // Note that we do not break from the loop here, so we decode/cache all A/AAAA records.
        }

        if (found) {
            queryLifecycleObserver.querySucceed();
            return;
        }

        if (cnames.isEmpty()) {
            queryLifecycleObserver.queryFailed(NO_MATCHING_RECORD_QUERY_FAILED_EXCEPTION);
        }
        else {
            // We asked for A/AAAA but we got only CNAME.
            onResponseCNAME(question, envelope, cnames, queryLifecycleObserver, promise);
        }
    }

    private
    InetAddress parseAddress(DnsRecord record, String name) {
        int type = record.getType();

        if (type == DnsRecordType.A) {
            ARecord aRecord = (ARecord) record;
            return aRecord.getAddress();
        }
        else if (type == DnsRecordType.AAAA) {
            AAAARecord aaaaRecord = (AAAARecord) record;
            return aaaaRecord.getAddress();
        }
        else {
            return null;
        }
    }

    private
    void onResponseCNAME(DnsMessage question,
                         AddressedEnvelope<DnsResponse, InetSocketAddress> envelope,
                         final DnsQueryLifecycleObserver queryLifecycleObserver,
                         Promise<T> promise) {
        onResponseCNAME(question, envelope, buildAliasMap(envelope.content()), queryLifecycleObserver, promise);
    }

    private
    void onResponseCNAME(DnsMessage question,
                         AddressedEnvelope<DnsResponse, InetSocketAddress> response,
                         Map<String, String> cnames,
                         final DnsQueryLifecycleObserver queryLifecycleObserver,
                         Promise<T> promise) {

        // Resolve the host name in the question into the real host name.
        String resolved = question.getQuestion()
                                  .getName()
                                  .toString();
        boolean found = false;
        while (!cnames.isEmpty()) { // Do not attempt to call Map.remove() when the Map is empty
            // because it can be Collections.emptyMap()
            // whose remove() throws a UnsupportedOperationException.
            final String next = cnames.remove(resolved);
            if (next != null) {
                found = true;
                resolved = next;
            }
            else {
                break;
            }
        }

        if (found) {
            followCname(resolved, queryLifecycleObserver, promise);
        }
        else {
            queryLifecycleObserver.queryFailed(CNAME_NOT_FOUND_QUERY_FAILED_EXCEPTION);
        }
    }

    private static
    Map<String, String> buildAliasMap(DnsMessage response) {
        DnsRecord[] answerArray = response.getSectionArray(DnsSection.ANSWER);
        Map<String, String> cnames = null;
        int length = answerArray.length;
        for (int i = 0; i < length; i++) {
            final DnsRecord record = answerArray[i];
            final int type = record.getType();
            if (type != DnsRecordType.CNAME) {
                continue;
            }

            System.err.println("CHECK ME ME! we don't have bytebuf content in this fashion anymore");
            CNAMERecord re = (CNAMERecord) record;
            final String domainName = re.getAlias()
                                        .toString();

            if (domainName == null) {
                continue;
            }

            if (cnames == null) {
                cnames = new HashMap<String, String>(min(8, length));
            }

            cnames.put(record.getName()
                        .toString()
                        .toLowerCase(Locale.US), domainName.toLowerCase(Locale.US));
        }

        return cnames != null ? cnames : Collections.<String, String>emptyMap();
    }

    void tryToFinishResolve(final DnsServerAddressStream nameServerAddrStream,
                            final int nameServerAddrStreamIndex,
                            final DnsQuestion question,
                            final DnsQueryLifecycleObserver queryLifecycleObserver,
                            final Promise<T> promise) {
        // There are no queries left to try.
        if (!queriesInProgress.isEmpty()) {
            queryLifecycleObserver.queryCancelled(allowedQueries);

            // There are still some queries we did not receive responses for.
            if (gotPreferredAddress()) {
                // But it's OK to finish the resolution process if we got a resolved address of the preferred type.
                finishResolve(promise, question);
            }

            // We did not get any resolved address of the preferred type, so we can't finish the resolution process.
            return;
        }

        // There are no queries left to try.
        if (resolvedEntries == null) {
            if (nameServerAddrStreamIndex < nameServerAddrStream.size()) {
                // the query is going to use the question again...
                question.retain();
                if (queryLifecycleObserver == NoopDnsQueryLifecycleObserver.INSTANCE) {
                    // If the queryLifecycleObserver has already been terminated we should create a new one for this
                    // fresh query.
                    resolveQuery(nameServerAddrStream, nameServerAddrStreamIndex + 1, question, promise);
                }
                else {
                    resolveQuery(nameServerAddrStream, nameServerAddrStreamIndex + 1, question, queryLifecycleObserver, promise);
                }
                return;
            }

            queryLifecycleObserver.queryFailed(NAME_SERVERS_EXHAUSTED_EXCEPTION);

            // .. and we could not find any A/AAAA records.
            if (!triedCNAME) {
                // As the last resort, try to query CNAME, just in case the name server has it.
                triedCNAME = true;

                resolveQuery(hostname, DnsRecordType.CNAME, getNameServers(hostname), promise);
                return;
            }
        }
        else {
            queryLifecycleObserver.queryCancelled(allowedQueries);
        }

        // We have at least one resolved address or tried CNAME as the last resort..
        finishResolve(promise, question);
    }

    private
    boolean gotPreferredAddress() {
        if (resolvedEntries == null) {
            return false;
        }

        final int size = resolvedEntries.size();
        final Class<? extends InetAddress> inetAddressType = parent.preferredAddressType()
                                                                   .addressType();
        for (int i = 0; i < size; i++) {
            InetAddress address = resolvedEntries.get(i)
                                                 .address();
            if (inetAddressType.isInstance(address)) {
                return true;
            }
        }
        return false;
    }

    private
    void finishResolve(Promise<T> promise, final DnsQuestion question) {
        // now we are done with the question.
        question.release();

        if (!queriesInProgress.isEmpty()) {
            // If there are queries in progress, we should cancel it because we already finished the resolution.
            for (Iterator<Future<AddressedEnvelope<DnsResponse, InetSocketAddress>>> i = queriesInProgress.iterator(); i.hasNext(); ) {
                Future<AddressedEnvelope<DnsResponse, InetSocketAddress>> f = i.next();
                i.remove();

                if (!f.cancel(false)) {
                    f.addListener(RELEASE_RESPONSE);
                }
            }
        }

        if (resolvedEntries != null) {
            // Found at least one resolved address.
            for (InternetProtocolFamily f : resolvedInternetProtocolFamilies) {
                if (finishResolve(f.addressType(), resolvedEntries, promise)) {
                    return;
                }
            }
        }

        // No resolved address found.
        final int tries = maxAllowedQueries - allowedQueries;
        final StringBuilder buf = new StringBuilder(64);

        buf.append("failed to resolve '")
           .append(hostname)
           .append('\'');
        if (tries > 1) {
            if (tries < maxAllowedQueries) {
                buf.append(" after ")
                   .append(tries)
                   .append(" queries ");
            }
            else {
                buf.append(". Exceeded max queries per resolve ")
                   .append(maxAllowedQueries)
                   .append(' ');
            }
        }

        final UnknownHostException cause = new UnknownHostException(buf.toString());
        cause.setStackTrace(new StackTraceElement[0]);

        resolveCache.cache(hostname, cause, parent.ch.eventLoop());
        promise.tryFailure(cause);
    }

    abstract
    boolean finishResolve(Class<? extends InetAddress> addressType, List<DnsCacheEntry> resolvedEntries, Promise<T> promise);

    abstract
    DnsNameResolverContext<T> newResolverContext(DnsNameResolver parent,
                                                 String hostname,
                                                 DnsCache resolveCache,
                                                 DnsServerAddressStream nameServerAddrs);

    private
    DnsServerAddressStream getNameServers(String hostname) {
        DnsServerAddressStream stream = getNameServersFromCache(hostname);
        return stream == null ? nameServerAddrs : stream;
    }

    private
    void followCname(String cname, final DnsQueryLifecycleObserver queryLifecycleObserver, Promise<T> promise) {

        // Use the same server for both CNAME queries
        DnsServerAddressStream stream = DnsServerAddresses.singleton(getNameServers(cname).next())
                                                          .stream();
        DnsQuestion cnameQuestion = null;
        try {
            if (parent.supportsARecords()) {
                cnameQuestion = DnsQuestion.newResolveQuestion(hostname, DnsRecordType.A, parent.isRecursionDesired());
            }
            if (parent.supportsAAAARecords()) {
                cnameQuestion = DnsQuestion.newResolveQuestion(hostname, DnsRecordType.AAAA, parent.isRecursionDesired());
            }

        } catch (Throwable cause) {
            queryLifecycleObserver.queryFailed(cause);
            PlatformDependent.throwException(cause);
        }

        if (cnameQuestion != null) {
            resolveQuery(stream, 0, cnameQuestion, queryLifecycleObserver.queryCNAMEd(cnameQuestion), promise);
        }
    }

    private
    boolean resolveQuery(String hostname, int type, DnsServerAddressStream dnsServerAddressStream, Promise<T> promise) {

        DnsQuestion message = DnsQuestion.newResolveQuestion(hostname, type, parent.isRecursionDesired());
        if (message == null) {
            return false;
        }

        resolveQuery(dnsServerAddressStream, 0, message, promise);
        return true;
    }

    /**
     * Holds the closed DNS Servers for a domain.
     */
    private static final
    class AuthoritativeNameServerList {

        private final String questionName;

        // We not expect the linked-list to be very long so a double-linked-list is overkill.
        private AuthoritativeNameServer head;
        private int count;

        AuthoritativeNameServerList(String questionName) {
            this.questionName = questionName.toLowerCase(Locale.US);
        }

        void add(DnsRecord record) {
            if (record.getType() != DnsRecordType.NS) {
                return;
            }

            // Only include servers that serve the correct domain.
            String recordName = record.getName()
                                      .toString();
            if (questionName.length() < recordName.length()) {
                return;
            }

            int dots = 0;
            for (int a = recordName.length() - 1, b = questionName.length() - 1; a >= 0; a--, b--) {
                char c = recordName.charAt(a);
                if (questionName.charAt(b) != c) {
                    return;
                }
                if (c == '.') {
                    dots++;
                }
            }

            if (head != null && head.dots > dots) {
                // We already have a closer match so ignore this one, no need to parse the domainName etc.
                return;
            }

            System.err.println("DOUBLE CHECK me! we do things differently now!");
            NSRecord re = (NSRecord) record;
            final String domainName = re.getAdditionalName()
                                        .toString();
            if (domainName == null) {
                // Could not be parsed, ignore.
                return;
            }

            // We are only interested in preserving the nameservers which are the closest to our qName, so ensure
            // we drop servers that have a smaller dots count.
            if (head == null || head.dots < dots) {
                count = 1;
                head = new AuthoritativeNameServer(dots, recordName, domainName);
            }
            else if (head.dots == dots) {
                AuthoritativeNameServer serverName = head;
                while (serverName.next != null) {
                    serverName = serverName.next;
                }
                serverName.next = new AuthoritativeNameServer(dots, recordName, domainName);
                count++;
            }
        }

        // Just walk the linked-list and mark the entry as removed when matched, so next lookup will need to process
        // one node less.
        AuthoritativeNameServer remove(String nsName) {
            AuthoritativeNameServer serverName = head;

            while (serverName != null) {
                if (!serverName.removed && serverName.nsName.equalsIgnoreCase(nsName)) {
                    serverName.removed = true;
                    return serverName;
                }
                serverName = serverName.next;
            }
            return null;
        }

        int size() {
            return count;
        }
    }


    static final
    class AuthoritativeNameServer {
        final int dots;
        final String nsName;
        final String domainName;

        AuthoritativeNameServer next;
        boolean removed;

        AuthoritativeNameServer(int dots, String domainName, String nsName) {
            this.dots = dots;
            this.nsName = nsName;
            this.domainName = domainName;
        }

        /**
         * Returns {@code true} if its a root server.
         */
        boolean isRootServer() {
            return dots == 1;
        }

        /**
         * The domain for which the {@link AuthoritativeNameServer} is responsible.
         */
        String domainName() {
            return domainName;
        }
    }
}
