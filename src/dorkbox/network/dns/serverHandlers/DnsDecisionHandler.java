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
import java.net.UnknownHostException;

import org.slf4j.Logger;

import dorkbox.network.dns.DnsEnvelope;
import dorkbox.network.dns.Name;
import dorkbox.network.dns.constants.DnsOpCode;
import dorkbox.network.dns.constants.DnsRecordType;
import dorkbox.network.dns.constants.DnsResponseCode;
import dorkbox.network.dns.constants.DnsSection;
import dorkbox.network.dns.records.*;
import dorkbox.util.collections.IntMap;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;

public class DnsDecisionHandler extends ChannelInboundHandlerAdapter {


    private final Logger logger;
    private IntMap responses = new IntMap();
    // private final DnsClient dnsClient;
    private final InetAddress localHost;

    public
    DnsDecisionHandler(final Logger logger) {
        this.logger = logger;

        // dnsClient = new DnsClient();

        InetAddress local;
        try {
            local = InetAddress.getLocalHost();
        } catch (UnknownHostException e) {
            e.printStackTrace();
            local = null;
        }

        localHost = local;
    }

    @Override
    public
    void channelRead(ChannelHandlerContext context, Object message) throws Exception {
        onChannelRead(context, (DnsEnvelope) message);
        ReferenceCountUtil.release(message);
    }

    public
    void onChannelRead(final ChannelHandlerContext context, final DnsEnvelope dnsMessage) {
        int opcode = dnsMessage.getHeader()
                               .getOpcode();

        switch (opcode) {
            case DnsOpCode.QUERY:
                onQuery(context, dnsMessage, dnsMessage.recipient());
                dnsMessage.release();
                return;

            case DnsOpCode.IQUERY:
                onIQuery(context, dnsMessage, dnsMessage.recipient());
                dnsMessage.release();
                return;

            case DnsOpCode.NOTIFY:
                onNotify(context, dnsMessage, dnsMessage.recipient());
                dnsMessage.release();
                return;

            case DnsOpCode.STATUS:
                onStatus(context, dnsMessage, dnsMessage.recipient());
                dnsMessage.release();
                return;

            case DnsOpCode.UPDATE:
                onUpdate(context, (Update) (DnsMessage) dnsMessage, dnsMessage.recipient());
                dnsMessage.release();
                return;

            default:
                logger.error("Unknown DNS opcode {} from {}", opcode, context.channel().remoteAddress());
                dnsMessage.release();
        }
    }

    private
    void onQuery(final ChannelHandlerContext context, final DnsMessage dnsQuestion, final InetSocketAddress recipient) {
        // either I have an answer, or I don't (and have to forward to another DNS server
        // it might be more that 1 question...
        Header header = dnsQuestion.getHeader();
        int count = header.getCount(DnsSection.QUESTION);

        // we don't support more than 1 question at a time.
        if (count == 1) {
                DnsEnvelope dnsEnvelope = new DnsEnvelope(dnsQuestion.getHeader()
                                                                  .getID(),
                                                          (InetSocketAddress) context.channel().localAddress(),
                                                          recipient);

                // dnsEnvelope.getHeader().setRcode(DnsResponseCode.NXDOMAIN);

            DnsRecord[] sectionArray = dnsQuestion.getSectionArray(DnsSection.QUESTION);
            DnsRecord dnsRecord = sectionArray[0];
            Name name = dnsRecord.getName();
            long ttl = dnsRecord.getTTL();
            int type = dnsRecord.getType();

            // // what type of record? A, AAAA, MX, PTR, etc?
            if (DnsRecordType.A == type) {
                ARecord answerRecord = new ARecord(name, dnsRecord.getDClass(), 10, localHost);
                dnsEnvelope.addRecord(dnsRecord, DnsSection.QUESTION);
                dnsEnvelope.addRecord(answerRecord, DnsSection.ANSWER);

                dnsEnvelope.getHeader().setRcode(DnsResponseCode.NOERROR);

                System.err.println("write");
            }

            // dnsEnvelope.retain();
            // NOTE: I suspect this must be a "client" that writes back. there are errors if not.
            context.channel()
                   .writeAndFlush(dnsEnvelope);


            // out.add(new DatagramPacket(buf, recipient, null));



// 		ChannelBuffer buffer = (ChannelBuffer) e.getMessage();
            // 		DNSMessage msg = new DNSMessage(buffer);
            // 		msg.header().id(this.original.header().id());
            // 		ChannelBuffer newone = ChannelBuffers.buffer(buffer.capacity());
            // 		msg.write(newone);
            // 		newone.resetReaderIndex();
            // 		this.originalChannel.write(newone, this.originalAddress)
            // 				.addListener(new ChannelFutureListener() {
            // 					@Override
            // 					public void operationComplete(ChannelFuture future)
            // 							throws Exception {
            // 						e.getChannel().close();
            // 					}
            // 				});
            // 	}

            return;
        }

        // boolean success = false;
        // try {
        //     DnsMessage dnsMessage = new DnsMessage(dnsQuestion.getHeader()
        //                                                       .getID());
        //
        //     dnsMessage.getHeader()
        //               .setRcode(DnsResponseCode.NOERROR);
        //
        //     // what type of record? A, AAAA, MX, PTR, etc?
        //
        //     DnsRecord[] sectionArray = dnsMessage.getSectionArray(DnsSection.ANSWER);
        //
        //     // if (code == DnsResponseCode.NOERROR) {
        //     //     return response.getSectionArray(DnsSection.ANSWER);
        //     // }
        //     //
        //     // DnsOutput dnsOutput = new DnsOutput(buf);
        //     // query.toWire(dnsOutput);
        //     success = true;
        // } finally {
        //     if (!success) {
        //         // buf.release();
        //     }
        // }


        DnsRecord[] sectionArray = dnsQuestion.getSectionArray(DnsSection.QUESTION);
        DnsRecord dnsRecord = sectionArray[0];

        System.err.println(dnsRecord);
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
    void onStatus(final ChannelHandlerContext context, final DnsMessage dnsQuestion, final InetSocketAddress recipient) {
        System.err.println("DECISION HANDLER READ");
        System.err.println(dnsQuestion);
    }

    private
    void onUpdate(final ChannelHandlerContext context, final Update dnsUpdate, final InetSocketAddress recipient) {
        System.err.println("DECISION HANDLER READ");
        System.err.println(dnsUpdate);
    }






    // @Override
	// public void messageReceived(ChannelHandlerContext ctx, final MessageEvent e) throws Exception {
	// 	final DNSMessage original = DNSMessage.class.cast(e.getMessage());
    //
	// 	ClientBootstrap cb = new ClientBootstrap(this.clientChannelFactory);
	// 	cb.setOption("broadcast", "false");
	// 	cb.setPipelineFactory(new ChannelPipelineFactory() {
	// 		@Override
	// 		public ChannelPipeline getPipeline() throws Exception {
	// 			return Channels.pipeline(new ClientHanler(original, e
	// 					.getChannel(), e.getRemoteAddress()));
	// 		}
	// 	});
    //
	// 	List<SocketAddress> newlist = new ArrayList<SocketAddress>(this.config.getForwarders());
	// 	sendRequest(e, original, cb, newlist);
	// }
    //
    // protected void sendRequest(final MessageEvent e, final DNSMessage original, final ClientBootstrap bootstrap, final List<SocketAddress> forwarders) {
	// 	if (0 < forwarders.size()) {
	// 		SocketAddress sa = forwarders.remove(0);
	// 		LOG.debug("send to {}", sa);
    //
	// 		ChannelFuture f = bootstrap.connect(sa);
	// 		ChannelBuffer newone = ChannelBuffers.buffer(512);
	// 		DNSMessage msg = new DNSMessage(original);
	// 		msg.write(newone);
	// 		newone.resetReaderIndex();
	// 		final Channel c = f.getChannel();
    //
	// 		if (LOG.isDebugEnabled()) {
	// 			LOG.debug(
	// 					"STATUS : [isOpen/isConnected/isWritable {}] {} {}",
	// 					new Object[] {
	// 							new boolean[] { c.isOpen(), c.isConnected(),
	// 									c.isWritable() }, c.getRemoteAddress(),
	// 							c.getClass() });
	// 		}
    //
	// 		c.write(newone, sa).addListener(new ChannelFutureListener() {
	// 			@Override
	// 			public void operationComplete(ChannelFuture future)
	// 					throws Exception {
	// 				LOG.debug("request complete isSuccess : {}",
	// 						future.isSuccess());
	// 				if (future.isSuccess() == false) {
	// 					if (0 < forwarders.size()) {
	// 						sendRequest(e, original, bootstrap, forwarders);
	// 					} else {
	// 						original.header().rcode(RCode.ServFail);
	// 						ChannelBuffer buffer = ChannelBuffers.buffer(512);
	// 						original.write(buffer);
	// 						// close inbound channel
	// 						e.getChannel().write(buffer)
	// 								.addListener(ChannelFutureListener.CLOSE);
	// 					}
	// 				}
	// 			}
	// 		});
    //
	// 		// f.awaitUninterruptibly(30, TimeUnit.SECONDS);
	// 	}
	// }

    @Override
    public
    void exceptionCaught(final ChannelHandlerContext context, final Throwable cause) throws Exception {
        logger.error("ForwardingHandler#exceptionCaught", cause);
        super.exceptionCaught(context, cause);
    }


	protected class ClientHandler extends ChannelInboundHandlerAdapter {

	// 	protected DNSMessage original;
    //
	// 	protected Channel originalChannel;
    //
	// 	protected SocketAddress originalAddress;
    //
	// 	public ClientHanler(DNSMessage msg, Channel c, SocketAddress sa) {
	// 		this.original = msg;
	// 		this.originalChannel = c;
	// 		this.originalAddress = sa;
	// 	}
    //
	// 	@Override
	// 	public void messageReceived(ChannelHandlerContext ctx, final MessageEvent e) throws Exception {
	// 		LOG.debug("ClientHanler#messageReceived");
	// 		ChannelBuffer buffer = (ChannelBuffer) e.getMessage();
	// 		DNSMessage msg = new DNSMessage(buffer);
	// 		msg.header().id(this.original.header().id());
	// 		ChannelBuffer newone = ChannelBuffers.buffer(buffer.capacity());
	// 		msg.write(newone);
	// 		newone.resetReaderIndex();
	// 		this.originalChannel.write(newone, this.originalAddress)
	// 				.addListener(new ChannelFutureListener() {
	// 					@Override
	// 					public void operationComplete(ChannelFuture future)
	// 							throws Exception {
	// 						e.getChannel().close();
	// 					}
	// 				});
	// 	}
    //

        @Override
        public
        void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause) throws Exception {
            logger.error("ClientHanler#exceptionCaught");
            logger.error(cause.getMessage(), cause);
            // e.getFuture()
            //  .setFailure(t);
            ctx.channel().close();
        }
	}
}
