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
package dorkbox.network.dns.serverHandlers.wip;

import org.slf4j.Logger;

import dorkbox.network.dns.DnsEnvelope;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.MessageToByteEncoder;

public class ForwardingHandler extends MessageToByteEncoder<DnsEnvelope> {


    private final int maxPayloadSize = 512;
    private final Logger logger;

    public
    ForwardingHandler(final Logger logger) {
        this.logger = logger;
    }

    // protected ServerConfiguration config;
	// protected ChannelFactory clientChannelFactory;

	// public
    // ForwardingHandler(ServerConfiguration config,
     //                  ChannelFactory clientChannelFactory) {
	// 	this.config = config;
	// 	this.clientChannelFactory = clientChannelFactory;
	// }


    @Override
    protected
    void encode(final ChannelHandlerContext context, final DnsEnvelope message, final ByteBuf out) throws Exception {
        System.err.println("FORWARD HANDLER ENCODE");

        // TODO: forward the message to ANOTHER dns server because we don't know what the awnser is

        // try {
        //     DnsOutput dnsOutput = new DnsOutput(out);
        //     message.toWire(dnsOutput);
        //
        //     //todo: need void promise
        //     context.channel()
        //            .writeAndFlush(new DatagramPacket(out, message.recipient(), message.sender()));
        //            // .write(new DatagramPacket(out, message.recipient(), message.sender()));
        // } catch (Exception e) {
        //     context.fireExceptionCaught(new IOException("Unable to write dns message: " + message, e));
        // }
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
	// 			return Channels.pipeline(new ClientHandler(original, e.getChannel(), e.getRemoteAddress()));
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
	// 			public void operationComplete(ChannelFuture future) throws Exception {
	// 				LOG.debug("request complete isSuccess : {}", future.isSuccess());
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
            logger.error("ClientHandler#exceptionCaught");
            logger.error(cause.getMessage(), cause);
            // e.getFuture()
            //  .setFailure(t);
            ctx.channel().close();
        }
	}
}
