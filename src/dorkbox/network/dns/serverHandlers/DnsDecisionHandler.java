/*
 * Copyright 2018 dorkbox, llc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package dorkbox.network.dns.serverHandlers;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;

import dorkbox.network.DnsClient;
import dorkbox.network.dns.DnsEnvelope;
import dorkbox.network.dns.DnsServerResponse;
import dorkbox.network.dns.Name;
import dorkbox.network.dns.constants.DnsOpCode;
import dorkbox.network.dns.constants.DnsRecordType;
import dorkbox.network.dns.constants.DnsResponseCode;
import dorkbox.network.dns.constants.DnsSection;
import dorkbox.network.dns.constants.Flags;
import dorkbox.network.dns.records.ARecord;
import dorkbox.network.dns.records.DnsMessage;
import dorkbox.network.dns.records.DnsRecord;
import dorkbox.network.dns.records.Header;
import dorkbox.network.dns.records.Update;
import dorkbox.network.dns.resolver.DnsNameResolver;
import dorkbox.util.collections.LockFreeHashMap;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;

public
class DnsDecisionHandler extends ChannelInboundHandlerAdapter {

    private final Logger logger;
    private final LockFreeHashMap<Name, ArrayList<ARecord>> aRecordMap;
    private final DnsClient dnsClient;

    public
    DnsDecisionHandler(final Logger logger) {
        this.logger = logger;

        dnsClient = new DnsClient();
        dnsClient.start();

        aRecordMap = new LockFreeHashMap<Name, ArrayList<ARecord>>();
    }

    /**
     * Adds a domain name query result, so clients that request the domain name will get the ipAddress
     *
     * @param domainName the domain name to have results for
     * @param aRecords the A records (can be multiple) to return for the requested domain name
     */
    public
    void addARecord(final Name domainName, final ArrayList<ARecord> aRecords) {
        aRecordMap.put(domainName, aRecords);
    }

    @Override
    public
    void channelRead(ChannelHandlerContext context, Object message) throws Exception {
        onChannelRead(context, (DnsEnvelope) message);
    }

    @Override
    public
    void exceptionCaught(final ChannelHandlerContext context, final Throwable cause) throws Exception {
        logger.error("DecisionHandler#exceptionCaught", cause);
        super.exceptionCaught(context, cause);
    }

    private
    void onChannelRead(final ChannelHandlerContext context, final DnsEnvelope dnsMessage) {
        int opcode = dnsMessage.getHeader()
                               .getOpcode();

        switch (opcode) {
            case DnsOpCode.QUERY:
                onQuery(context, dnsMessage, dnsMessage.recipient());
                return;

            case DnsOpCode.IQUERY:
                onIQuery(context, dnsMessage, dnsMessage.recipient());
                return;

            case DnsOpCode.NOTIFY:
                onNotify(context, dnsMessage, dnsMessage.recipient());
                return;

            case DnsOpCode.STATUS:
                onStatus(context, dnsMessage, dnsMessage.recipient());
                return;

            case DnsOpCode.UPDATE:
                onUpdate(context, (Update) (DnsMessage) dnsMessage, dnsMessage.recipient());
                return;

            default:
                logger.error("Unknown DNS opcode {} from {}",
                             opcode,
                             context.channel()
                                    .remoteAddress());
        }
    }

    private
    void onIQuery(final ChannelHandlerContext context, final DnsMessage dnsQuestion, final InetSocketAddress recipient) {
        System.err.println("DECISION HANDLER READ");
        System.err.println(dnsQuestion);
    }

    private
    void onNotify(final ChannelHandlerContext context, final DnsMessage dnsQuestion, final InetSocketAddress recipient) {
        System.err.println("DECISION HANDLER READ");
        System.err.println(dnsQuestion);
    }

    private
    void onQuery(final ChannelHandlerContext context, final DnsMessage dnsQuestion, final InetSocketAddress recipient) {
        // either I have an answer, or I don't (and have to forward to another DNS server
        // it might be more than 1 question...
        Header header = dnsQuestion.getHeader();
        int count = header.getCount(DnsSection.QUESTION);

        // we don't support more than 1 question at a time.
        if (count == 1) {
            DnsRecord[] sectionArray = dnsQuestion.getSectionArray(DnsSection.QUESTION);
            final DnsRecord dnsRecord = sectionArray[0];
            final Name name = dnsRecord.getName();
            final long ttl = dnsRecord.getTTL();
            final int type = dnsRecord.getType();


            // what type of record? A, AAAA, MX, PTR, etc?
            if (DnsRecordType.A == type) {
                DnsNameResolver resolver = dnsClient.getResolver();

                String domainName = name.toString(true);

                // check to see if we have it in our local hosts file
                final InetAddress inetAddress = resolver.resolveHostsFileEntry(domainName);
                if (inetAddress != null) {
                    DnsServerResponse dnsResponse = new DnsServerResponse(dnsQuestion,
                                                                          (InetSocketAddress) context.channel()
                                                                                                     .localAddress(),
                                                                          recipient);

                    Header responseHeader = dnsResponse.getHeader();
                    responseHeader.setFlag(Flags.QR);
                    responseHeader.setRcode(DnsResponseCode.NOERROR);

                    dnsResponse.addRecord(dnsRecord, DnsSection.QUESTION);

                    ARecord aRecord = new ARecord(name, dnsRecord.getDClass(), ttl, inetAddress);
                    dnsResponse.addRecord(aRecord, DnsSection.ANSWER);

                    context.channel()
                           .write(dnsResponse);

                    return;
                }


                // check our local cache
                ArrayList<ARecord> records = aRecordMap.get(name);
                if (records != null) {
                    DnsServerResponse dnsResponse = new DnsServerResponse(dnsQuestion,
                                                                          (InetSocketAddress) context.channel()
                                                                                                     .localAddress(),
                                                                          recipient);

                    Header responseHeader = dnsResponse.getHeader();
                    responseHeader.setFlag(Flags.QR);
                    responseHeader.setRcode(DnsResponseCode.NOERROR);

                    dnsResponse.addRecord(dnsRecord, DnsSection.QUESTION);

                    for (ARecord record : records) {
                        dnsResponse.addRecord(record, DnsSection.ANSWER);
                        logger.debug("Writing A record response: {}", record.getAddress());
                    }

                    context.channel()
                           .write(dnsResponse);

                    return;
                }
                else {
                    // have to send this on to the forwarder
                    logger.debug("Sending DNS query to the forwarder...");


                    // use "resolve", since it handles A/AAAA records + redirects correctly
                    resolver.resolveAll(domainName)
                            .addListener(new FutureListener<List<InetAddress>>() {
                                @Override
                                public
                                void operationComplete(final Future<List<InetAddress>> future) throws Exception {
                                    List<InetAddress> resolvedAddresses = future.getNow();

                                    DnsServerResponse dnsResponse = new DnsServerResponse(dnsQuestion,
                                                                                          (InetSocketAddress) context.channel()
                                                                                                                     .localAddress(),
                                                                                          recipient);

                                    Header responseHeader = dnsResponse.getHeader();
                                    responseHeader.setFlag(Flags.QR);

                                    dnsResponse.addRecord(dnsRecord, DnsSection.QUESTION);

                                    if (resolvedAddresses == null || resolvedAddresses.isEmpty()) {
                                        responseHeader.setRcode(DnsResponseCode.NXDOMAIN);
                                    }
                                    else {
                                        responseHeader.setRcode(DnsResponseCode.NOERROR);

                                        ArrayList<ARecord> records = new ArrayList<ARecord>();

                                        for (int i = 0; i < resolvedAddresses.size(); i++) {
                                            final InetAddress resolvedAddress = resolvedAddresses.get(i);

                                            ARecord record = new ARecord(name, dnsRecord.getDClass(), ttl, resolvedAddress);
                                            records.add(record);
                                            dnsResponse.addRecord(record, DnsSection.ANSWER);
                                        }


                                        // we got here because there were no cached records in our record map -- so we save them!
                                        // duplicates are not an issue because they will always be the same answer
                                        aRecordMap.put(name, records);
                                    }

                                    context.channel()
                                           .write(dnsResponse);

                                }
                            });
                }
            }
            return;
        }

        DnsRecord[] sectionArray = dnsQuestion.getSectionArray(DnsSection.QUESTION);
        DnsRecord dnsRecord = sectionArray[0];

        System.err.println(dnsRecord);
    }

    private
    void onStatus(final ChannelHandlerContext context, final DnsMessage dnsQuestion, final InetSocketAddress recipient) {
        System.err.println("DECISION HANDLER READ");
        System.err.println(dnsQuestion);
    }

    private
    void onUpdate(final ChannelHandlerContext context, final Update dnsUpdate, final InetSocketAddress recipient) {
        System.err.println("DECISION HANDLER READ");
        System.err.println(dnsUpdate);
    }

    public
    void stop() {
        dnsClient.stop();
    }
}
