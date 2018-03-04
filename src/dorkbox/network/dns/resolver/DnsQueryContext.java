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

import static io.netty.util.internal.ObjectUtil.checkNotNull;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

import dorkbox.network.dns.DnsQuestion;
import dorkbox.network.dns.clientHandlers.DnsResponse;
import dorkbox.network.dns.constants.DnsSection;
import dorkbox.network.dns.records.DnsRecord;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelPromise;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import io.netty.util.concurrent.Promise;
import io.netty.util.concurrent.ScheduledFuture;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

final
class DnsQueryContext {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(DnsQueryContext.class);

    private final DnsNameResolver parent;
    private final Promise<DnsResponse> promise;
    private final int id;
    private final DnsQuestion question;

    private final InetSocketAddress nameServerAddr;

    private volatile ScheduledFuture<?> timeoutFuture;

    DnsQueryContext(DnsNameResolver parent,
                    InetSocketAddress nameServerAddr,
                    DnsQuestion question,
                    Promise<DnsResponse> promise) {

        this.parent = checkNotNull(parent, "parent");
        this.nameServerAddr = checkNotNull(nameServerAddr, "nameServerAddr");
        this.question = checkNotNull(question, "question");
        this.promise = checkNotNull(promise, "promise");

        id = parent.queryContextManager.add(this);

        question.init(id, nameServerAddr);
    }

    void query(ChannelPromise writePromise) {
        final DnsQuestion question = question();
        final InetSocketAddress nameServerAddr = nameServerAddr();

        if (logger.isDebugEnabled()) {
            logger.debug("{} WRITE: [{}: {}], {}", parent.ch, id, nameServerAddr, question);
        }

        sendQuery(question, writePromise);
    }

    InetSocketAddress nameServerAddr() {
        return nameServerAddr;
    }

    DnsQuestion question() {
        return question;
    }

    private
    void sendQuery(final DnsQuestion query, final ChannelPromise writePromise) {
        if (parent.channelFuture.isDone()) {
            writeQuery(query, writePromise);
        }
        else {
            parent.channelFuture.addListener(new GenericFutureListener<Future<? super Channel>>() {
                @Override
                public
                void operationComplete(Future<? super Channel> future) throws Exception {
                    if (future.isSuccess()) {
                        writeQuery(query, writePromise);
                    }
                    else {
                        Throwable cause = future.cause();
                        promise.tryFailure(cause);
                        writePromise.setFailure(cause);
                    }
                }
            });
        }
    }

    private
    void writeQuery(final DnsQuestion query, final ChannelPromise writePromise) {
        final ChannelFuture writeFuture = parent.ch.writeAndFlush(query, writePromise);
        if (writeFuture.isDone()) {
            onQueryWriteCompletion(writeFuture);
        }
        else {
            writeFuture.addListener(new ChannelFutureListener() {
                @Override
                public
                void operationComplete(ChannelFuture future) throws Exception {
                    onQueryWriteCompletion(writeFuture);
                }
            });
        }
    }

    private
    void onQueryWriteCompletion(ChannelFuture writeFuture) {
        if (!writeFuture.isSuccess()) {
            writeFuture.cause()
                       .printStackTrace();
            setFailure("failed to send a query", writeFuture.cause());
            return;
        }

        // Schedule a query timeout task if necessary.
        final long queryTimeoutMillis = parent.queryTimeoutMillis();
        if (queryTimeoutMillis > 0) {
            timeoutFuture = parent.ch.eventLoop()
                                     .schedule(new Runnable() {
                                         @Override
                                         public
                                         void run() {
                                             if (promise.isDone()) {
                                                 // Received a response before the query times out.
                                                 return;
                                             }

                                             setFailure("query timed out after " + queryTimeoutMillis + " milliseconds", null);
                                         }
                                     }, queryTimeoutMillis, TimeUnit.MILLISECONDS);
        }
    }

    private
    void setFailure(String message, Throwable cause) {
        final InetSocketAddress nameServerAddr = nameServerAddr();
        parent.queryContextManager.remove(nameServerAddr, id);

        final StringBuilder buf = new StringBuilder(message.length() + 64);
        buf.append('[')
           .append(nameServerAddr)
           .append("] ")
           .append(message)
           .append(" (no stack trace available)");

        final DnsNameResolverException e;
        if (cause != null) {
            e = new DnsNameResolverException(nameServerAddr, question(), buf.toString(), cause);
        }
        else {
            e = new DnsNameResolverException(nameServerAddr, question(), buf.toString());
        }

        promise.tryFailure(e);
    }

    void finish(DnsResponse response) {

        try {
            DnsRecord[] sectionArray = response.getSectionArray(DnsSection.QUESTION);
            if (sectionArray.length != 1) {
                logger.warn("Received a DNS response with invalid number of questions: {}", response);
                return;
            }

            DnsRecord[] questionArray = question.getSectionArray(DnsSection.QUESTION);
            if (questionArray.length != 1) {
                logger.warn("Received a DNS response with invalid number of query questions: {}", response);
                return;
            }


            if (!questionArray[0].equals(sectionArray[0])) {
                logger.warn("Received a mismatching DNS response: {}", response);
                return;
            }

            setSuccess(response);
        } finally {
            if (question.isResolveQuestion()) {
                // for resolve questions (always A/AAAA), we convert the answer into InetAddress, however with OTHER TYPES, we pass
                // back the result to the user, and if we release it, all of the content will be cleared.
                response.release();
            }
        }
    }

    private
    void setSuccess(DnsResponse response) {
        parent.queryContextManager.remove(nameServerAddr(), id);

        // Cancel the timeout task.
        final ScheduledFuture<?> timeoutFuture = this.timeoutFuture;
        if (timeoutFuture != null) {
            timeoutFuture.cancel(false);
        }

        Promise<DnsResponse> promise = this.promise;
        if (promise.setUncancellable()) {
            response.retain();
            // response now has a refCnt = 2
            if (!promise.trySuccess(response)) { // question is used here!
                // We failed to notify the promise as it was failed before, thus we need to release the envelope
                response.release();
            }

            response.release();
        }
    }
}
