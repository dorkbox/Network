package dorkbox.network;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.socket.DatagramPacket;
import io.netty.handler.codec.dns.DnsQueryEncoder;
import io.netty.handler.codec.dns.DnsQuestion;
import io.netty.handler.codec.dns.DnsResource;
import io.netty.handler.codec.dns.DnsResponse;
import io.netty.handler.codec.dns.DnsResponseDecoder;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;

import org.junit.Test;

import dorkbox.network.dns.RecordDecoderFactory;
import dorkbox.network.dns.record.MailExchangerRecord;
import dorkbox.network.dns.record.ServiceRecord;
import dorkbox.network.dns.record.StartOfAuthorityRecord;

public class DnsRecordDecoderTests {

    @SuppressWarnings("unused")
    private static void submitDNS(final String server, final DnsQuestion question) {
        final InetSocketAddress dnsServer = new InetSocketAddress(server, 53);

        DnsClient dnsClient = new DnsClient(dnsServer);
        List<Object> answers = dnsClient.submitQuestion(question);
        dnsClient.stop();


//        final Promise<Object> newPromise = GlobalEventExecutor.INSTANCE.newPromise();
//
//        NioEventLoopGroup group = new NioEventLoopGroup();
//        Bootstrap bootstrap = new Bootstrap();
//        bootstrap.group(group);
//        bootstrap.channel(NioDatagramChannel.class);
//        bootstrap.option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT);
//
//        bootstrap.handler(new ChannelInitializer<DatagramChannel>() {
//            @Override
//            protected void initChannel(DatagramChannel ch) throws Exception {
//                ChannelPipeline pipeline = ch.pipeline();
//                pipeline.addLast(new DnsQueryEncoder());
//                pipeline.addLast(new DnsResponseDecoder() {
//                    @Override
//                    protected void decode(ChannelHandlerContext ctx, DatagramPacket packet, List<Object> out) throws Exception {
//                        ByteBuf buf = packet.content();
//
//                        ByteBuffer nioBuffer = buf.nioBuffer();
//                        int limit = nioBuffer.limit();
//
//                        byte[] array = new byte[limit];
//                        buf.getBytes(0, array);
//                        Sys.printArray(array);
//
//                        super.decode(ctx, packet, out);
//                    }
//                });
//            }
//        });
//
//        ChannelFuture connect = bootstrap.connect(dnsServer);
//        connect.addListener(new ChannelFutureListener() {
//            @Override
//            public void operationComplete(ChannelFuture future) throws Exception {
//                if (future.isSuccess()) {
//                    DnsQuery query = new DnsQuery(1, dnsServer).addQuestion(question);
//
//                    ChannelFuture writeAndFlush = future.channel().writeAndFlush(query);
//                    writeAndFlush.addListener(new ChannelFutureListener() {
//                        @Override
//                        public void operationComplete(ChannelFuture future) throws Exception {
//                            if (future.isSuccess()) {
//                                future.channel().pipeline().addLast(new SimpleChannelInboundHandler<DnsResponse>() {
//                                    @Override
//                                    protected void channelRead0(ChannelHandlerContext ctx, DnsResponse msg) throws Exception {
//                                        DnsResponseCode errorCode = msg.header().responseCode();
//
//                                        if (errorCode == DnsResponseCode.NOERROR) {
//                                            RecordDecoderFactory factory = RecordDecoderFactory.getFactory();
//
//                                            List<DnsResource> resources = msg.answers();
//                                            List<Object> records = new ArrayList<>(resources.size());
//                                            for (DnsResource resource : resources) {
//                                                Object record = factory.decode(msg, resource);
//                                                records.add(record);
//                                            }
//                                            newPromise.setSuccess(records);
//                                        } else {
//                                            newPromise.setFailure(new DnsException(errorCode));
//                                        }
//                                        ctx.close();
//                                    }
//
//                                    @Override
//                                    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
//                                        newPromise.setFailure(cause);
//                                        ctx.close();
//                                    }
//                                });
//                            } else {
//                                if (!future.isDone()) {
//                                    newPromise.setFailure(future.cause());
//                                }
//                            }
//                        }
//                    });
//                } else {
//                    if (!future.isDone()) {
//                        newPromise.setFailure(future.cause());
//                    }
//                }
//            }
//        });
//
//        Promise<Object> result = newPromise.awaitUninterruptibly();
//        if (result.isSuccess() && result.isDone()) {
//            Object object = result.getNow();
////            if (object instanceof InetAddress) {
////                return ((InetAddress) object).getHostAddress();
////            }
//
//        } else {
//            Throwable cause = result.cause();
//            cause.printStackTrace();
//        }
    }

    @Test
    public void decode_A_Record() {
        // submitDNS("resolver1.opendns.com", new DnsQuestion("myip.opendns.com", DnsType.A)); //good

        byte[] data = new byte[] {0,1,-127,-128,0,1,0,1,0,0,0,0,4,109,121,105,112,7,111,112,101,110,100,110,115,
                                  3,99,111,109,0,0,1,0,1,-64,12,0,1,0,1,0,0,0,0,0,4,127,0,0,1};

        EmbeddedChannel embedder = new EmbeddedChannel(new DnsQueryEncoder(), new DnsResponseDecoder());
        ByteBuf packet = Unpooled.wrappedBuffer(data);

        DatagramPacket datagramPacket = new DatagramPacket(packet, null, new InetSocketAddress(0));
        embedder.writeInbound(datagramPacket);

        DnsResponse dnsResponse = embedder.readInbound();
        List<DnsResource> answers = dnsResponse.answers();
        if (answers.size() != 1) {
            fail("Wrong number of answers");
        }

        for (DnsResource answer : answers) {
            Object record = RecordDecoderFactory.getFactory().decode(dnsResponse, answer);
            if (record instanceof InetAddress) {
                String hostAddress = ((InetAddress)record).getHostAddress();
                assertEquals(hostAddress, "127.0.0.1");
                return;
            }
        }

        fail("Unable to decode answer");
    }

    @Test
    public void decode_PTR_Record() {
        // PTR absolutely MUST end in '.in-addr.arpa' in order for the DNS server to understand it.
        // our DNS client will FIX THIS, so that end-users do NOT have to know this!
        // submitDNS("127.0.1.1", new DnsQuestion("204.228.150.3", DnsType.PTR));

        byte[] data = new byte[] {0,1,-127,-128,0,1,0,1,0,0,0,0,3,50,48,52,3,50,50,56,3,49,53,48,1,51,7,105,110,45,97,100,100,114,4,97,114,112,97,0,0,
                12,0,1,-64,12,0,12,0,1,0,0,84,95,0,32,16,110,48,48,51,45,48,48,48,45,48,48,48,45,48,48,48,6,115,116,97,116,105,99,2,
                103,101,3,99,111,109,0};

        EmbeddedChannel embedder = new EmbeddedChannel(new DnsResponseDecoder());
        ByteBuf packet = Unpooled.wrappedBuffer(data);

        DatagramPacket datagramPacket = new DatagramPacket(packet, null, new InetSocketAddress(0));
        embedder.writeInbound(datagramPacket);

        DnsResponse dnsResponse = embedder.readInbound();
        List<DnsResource> answers = dnsResponse.answers();
        if (answers.size() > 1) {
            fail("Too many answers");
        }

        for (DnsResource answer : answers) {
            Object record = RecordDecoderFactory.getFactory().decode(dnsResponse, answer);
            if (record instanceof String) {
                String hostAddress = (String)record;
                assertEquals(hostAddress, "n003-000-000-000.static.ge.com");
                return;
            }
        }

        fail("Unable to decode answer");
    }

    @Test
    public void decode_CNAME_Record() {
//        submitDNS("8.8.8.8", new DnsQuestion("www.atmos.org", DnsType.CNAME)); // good

        byte[] data = new byte[] {0,1,-127,-128,0,1,0,1,0,0,0,0,3,119,119,119,5,97,116,109,111,115,3,111,114,103,0,0,5,0,1,-64,12,0,5,0,1,0,0,2,87,
                0,18,5,97,116,109,111,115,6,103,105,116,104,117,98,3,99,111,109,0};

        EmbeddedChannel embedder = new EmbeddedChannel(new DnsQueryEncoder(), new DnsResponseDecoder());
        ByteBuf packet = Unpooled.wrappedBuffer(data);

        DatagramPacket datagramPacket = new DatagramPacket(packet, null, new InetSocketAddress(0));
        embedder.writeInbound(datagramPacket);

        DnsResponse dnsResponse = embedder.readInbound();
        List<DnsResource> answers = dnsResponse.answers();
        if (answers.size() != 1) {
            fail("Wrong number of answers");
        }

        for (DnsResource answer : answers) {
            Object record = RecordDecoderFactory.getFactory().decode(dnsResponse, answer);
            assertEquals(record, "atmos.github.com");
            return;
        }

        fail("Unable to decode answer");
    }

    @Test
    public void decode_MX_Record() {
//        submitDNS("8.8.8.8", new DnsQuestion("bbc.co.uk", DnsType.MX)); // good

        byte[] data = new byte[] {0,1,-127,-128,0,1,0,2,0,0,0,0,3,98,98,99,2,99,111,2,117,107,0,0,15,0,1,-64,12,0,15,0,1,0,0,0,121,0,31,0,10,
                8,99,108,117,115,116,101,114,49,2,101,117,11,109,101,115,115,97,103,101,108,97,98,115,3,99,111,109,0,-64,12,0,15,0,1,0,0,0,121,0,
                14,0,20,9,99,108,117,115,116,101,114,49,97,-64,50};

        EmbeddedChannel embedder = new EmbeddedChannel(new DnsQueryEncoder(), new DnsResponseDecoder());
        ByteBuf packet = Unpooled.wrappedBuffer(data);

        DatagramPacket datagramPacket = new DatagramPacket(packet, null, new InetSocketAddress(0));
        embedder.writeInbound(datagramPacket);

        DnsResponse dnsResponse = embedder.readInbound();
        List<DnsResource> answers = dnsResponse.answers();
        if (answers.size() != 2) {
            fail("Wrong number of answers");
        }

        for (DnsResource answer : answers) {
            Object record = RecordDecoderFactory.getFactory().decode(dnsResponse, answer);
            if (record instanceof MailExchangerRecord) {
                String name = ((MailExchangerRecord) record).name();
                if (!(name.equals("cluster1.eu.messagelabs.com") || name.equals("cluster1a.eu.messagelabs.com"))) {
                    fail("Records not correct");
                    return;
                }
            } else {
                fail("Records not correct");
            }
        }
    }

    @Test
    public void decode_SRV_Record() {
//      submitDNS("8.8.8.8", new DnsQuestion("_pop3._tcp.fudo.org", DnsType.SRV)); // good

        byte[] data = new byte[] {0,1,-127,-128,0,1,0,1,0,0,0,0,5,95,112,111,112,51,4,95,116,99,112,4,102,117,100,111,3,111,114,103,0,0,33,0,1,-64,12,0,33,
                0,1,0,0,42,47,0,20,0,0,0,0,0,110,3,116,121,114,4,102,117,100,111,3,111,114,103,0};

        EmbeddedChannel embedder = new EmbeddedChannel(new DnsQueryEncoder(), new DnsResponseDecoder());
        ByteBuf packet = Unpooled.wrappedBuffer(data);

        DatagramPacket datagramPacket = new DatagramPacket(packet, null, new InetSocketAddress(0));
        embedder.writeInbound(datagramPacket);

        DnsResponse dnsResponse = embedder.readInbound();
        List<DnsResource> answers = dnsResponse.answers();
        if (answers.size() != 1) {
            fail("Wrong number of answers");
        }

        for (DnsResource answer : answers) {
            Object record = RecordDecoderFactory.getFactory().decode(dnsResponse, answer);
            if (record instanceof ServiceRecord) {
                String name = ((ServiceRecord) record).target();
                assertEquals(name, "tyr.fudo.org");
                return;
            } else {
                fail("Records not correct");
            }
        }
    }

    @Test
    public void decode_SOA_Record() {
//      submitDNS("8.8.8.8", new DnsQuestion("google.com", DnsType.SOA)); // good

        byte[] data = new byte[] {0,1,-127,-128,0,1,0,1,0,0,0,0,6,103,111,111,103,108,101,3,99,111,109,0,0,6,0,1,-64,12,0,6,0,1,0,0,84,95,0,38,3,
                110,115,49,-64,12,9,100,110,115,45,97,100,109,105,110,-64,12,120,11,-120,-88,0,0,28,32,0,0,7,8,0,18,117,0,0,0,1,44};

        EmbeddedChannel embedder = new EmbeddedChannel(new DnsQueryEncoder(), new DnsResponseDecoder());
        ByteBuf packet = Unpooled.wrappedBuffer(data);

        DatagramPacket datagramPacket = new DatagramPacket(packet, null, new InetSocketAddress(0));
        embedder.writeInbound(datagramPacket);

        DnsResponse dnsResponse = embedder.readInbound();
        List<DnsResource> answers = dnsResponse.answers();
        if (answers.size() != 1) {
            fail("Wrong number of answers");
        }

        for (DnsResource answer : answers) {
            Object record = RecordDecoderFactory.getFactory().decode(dnsResponse, answer);
            if (record instanceof StartOfAuthorityRecord) {
                StartOfAuthorityRecord startOfAuthorityRecord = (StartOfAuthorityRecord) record;
                assertEquals(startOfAuthorityRecord.primaryNameServer(), "ns1.google.com");
                assertEquals(startOfAuthorityRecord.responsiblePerson(), "dns-admin.google.com");
                return;
            } else {
                fail("Records not correct");
            }
        }
    }

    @Test
    public void decode_TXT_Record() {
//      submitDNS("8.8.8.8", new DnsQuestion("real-world-systems.com", DnsType.TXT)); // good

        byte[] data = new byte[] {0,1,-127,-128,0,1,0,1,0,0,0,0,18,114,101,97,108,45,119,111,114,108,100,45,115,121,115,116,101,109,115,3,99,111,109,0,0,16,0,1,-64,
                12,0,16,0,1,0,0,56,63,0,58,57,118,61,115,112,102,49,32,43,97,32,43,109,120,32,43,105,112,52,58,50,48,57,46,50,51,54,46,55,
                49,46,49,55,32,43,105,112,52,58,49,55,52,46,49,50,55,46,49,49,57,46,51,51,32,126,97,108,108};

        EmbeddedChannel embedder = new EmbeddedChannel(new DnsQueryEncoder(), new DnsResponseDecoder());
        ByteBuf packet = Unpooled.wrappedBuffer(data);

        DatagramPacket datagramPacket = new DatagramPacket(packet, null, new InetSocketAddress(0));
        embedder.writeInbound(datagramPacket);

        DnsResponse dnsResponse = embedder.readInbound();
        List<DnsResource> answers = dnsResponse.answers();
        if (answers.size() != 1) {
            fail("Wrong number of answers");
        }

        for (DnsResource answer : answers) {
            Object record = RecordDecoderFactory.getFactory().decode(dnsResponse, answer);
            if (record instanceof List) {
                Object expected = ((List<?>) record).get(0);
                assertEquals(expected, "v=spf1 +a +mx +ip4:209.236.71.17 +ip4:174.127.119.33 ~all");
                return;
            }
        }
        fail("Records not correct");
    }
}
