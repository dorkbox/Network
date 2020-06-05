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

import java.io.IOException;
import java.time.Clock;
import java.util.Arrays;

import org.agrona.DirectBuffer;

import dorkbox.network.aeron.EchoAddresses;
import dorkbox.network.aeron.EchoChannels;
import dorkbox.network.aeron.EchoMessages;
import dorkbox.network.aeron.EchoServerExecutor;
import dorkbox.network.aeron.EchoServerExecutorService;
import dorkbox.network.connection.Connection;
import dorkbox.network.connection.EndPointServer;
import dorkbox.network.connection.connectionType.ConnectionRule;
import dorkbox.network.ipFilter.IpFilterRule;
import dorkbox.util.exceptions.SecurityException;
import io.aeron.FragmentAssembler;
import io.aeron.Image;
import io.aeron.Publication;
import io.aeron.Subscription;
import io.aeron.logbuffer.FragmentHandler;
import io.aeron.logbuffer.Header;

/**
 * The server can only be accessed in an ASYNC manner. This means that the server can only be used in RESPONSE to events. If you access the
 * server OUTSIDE of events, you will get inaccurate information from the server (such as getConnections())
 * <p/>
 * To put it bluntly, ONLY have the server do work inside of a listener!
 */
public
class Server<C extends Connection> extends EndPointServer {

    /**
     * Gets the version number.
     */
    public static
    String getVersion() {
        return "4.1";
    }

    private volatile boolean isRunning = false;

    private final EchoServerExecutorService executor;
    private final ClientStates clients;

    /**
     * Starts a LOCAL <b>only</b> server, with the default serialization scheme.
     */
    public
    Server() throws SecurityException, IOException {
        this(new ServerConfiguration());
    }

    /**
     * Convenience method to starts a server with the specified Connection Options
     */
    @SuppressWarnings("AutoBoxing")
    public
    Server(ServerConfiguration config) throws SecurityException, IOException {
        // watch-out for serialization... it can be NULL incoming. The EndPoint (superclass) sets it, if null, so
        // you have to make sure to use this.serialization
        super(config);

        EchoServerExecutorService exec = null;
        try {
            this.executor  = EchoServerExecutor.create(Server.class);

            try {
                this.clients = new ClientStates(this.aeron, Clock.systemUTC(), this.executor, this.config, logger);
            } catch (final Exception e) {
                if (mediaDriver != null) {
                    mediaDriver.close();
                }
                throw e;
            }
        } catch (final Exception e) {
            try {
                if (exec != null) {
                    exec.close();
                }
            } catch (final Exception c_ex) {
                e.addSuppressed(c_ex);
            }
            throw new IOException(e);
        }
    }

    /**
     * Binds the server to the configured, underlying protocols.
     * <p/>
     * This method will also BLOCK until the stop method is called, and if you want to continue running code after this method invocation,
     * bind should be called in a separate, non-daemon thread.
     */
    public
    void bind() {
        bind(true);
    }

    /**
     * Binds the server to the configured, underlying protocols.
     * <p/>
     * This is a more advanced method, and you should consider calling <code>bind()</code> instead.
     *
     * @param blockUntilTerminate
     *                 will BLOCK until the server stop method is called, and if you want to continue running code after this method
     *                 invocation, bind should be called in a separate, non-daemon thread - or with false as the parameter.
     */
    @SuppressWarnings("AutoBoxing")
    public
    void bind(boolean blockUntilTerminate) {
        if (isRunning) {
            logger.error("Unable to bind when the server is already running!");
            return;
        }

        isRunning = true;

        Publication publication = null;
        Subscription subscription = null;
        FragmentHandler handler = null;
        try {
            publication = EchoChannels.createPublicationDynamicMDC(this.aeron,
                                                                   this.config.listenIpAddress,
                                                                   this.config.controlPort,
                                                                   UDP_STREAM_ID);

            subscription = EchoChannels.createSubscriptionWithHandlers(this.aeron,
                                                                       this.config.listenIpAddress,
                                                                       this.config.port,
                                                                       UDP_STREAM_ID,
                                                                       this::onInitialClientConnected,
                                                                       this::onInitialClientDisconnected);



            /**
             * Note: Reassembly has been shown to be minimal impact to latency. But not totally negligible. If the lowest latency is desired, then limiting message sizes to MTU size is a good practice.
             *
             * Note: There is a maximum length allowed for messages which is the min of 1/8th a term length or 16MB. Messages larger than this should chunked using an application level chunking protocol. Chunking has better recovery properties from failure and streams with mechanical sympathy.
             */
            final Publication finalPublication = publication;
            handler = new FragmentAssembler((buffer, offset, length, header)->this.onInitialClientMessage(finalPublication,
                                                                                                          buffer,
                                                                                                          offset,
                                                                                                          length,
                                                                                                          header));

            final FragmentHandler initialConnectionHandler = handler;
            final Subscription initialConnectionSubscription = subscription;

            while (true) {
                this.executor.execute(()->{
                    initialConnectionSubscription.poll(initialConnectionHandler, 100); // this checks to see if there are NEW clients
                    this.clients.poll();  // this manages existing clients
                });

                try {
                    Thread.sleep(100L);
                } catch (final InterruptedException e) {
                    Thread.currentThread()
                          .interrupt();
                }
            }
        } finally {
            if (publication != null) {
                publication.close();
            }

            if (subscription != null) {
                subscription.close();
            }
        }


        // we now BLOCK until the stop method is called.
        // if we want to continue running code in the server, bind should be called in a separate, non-daemon thread.
        // if (blockUntilTerminate) {
            // waitForShutdown();
        // }
    }

    /**
     * Adds an IP+subnet rule that defines if that IP+subnet is allowed/denied connectivity to this server.
     * <p>
     * If there are any IP+subnet added to this list - then ONLY those are permitted (all else are denied)
     * <p>
     * If there is nothing added to this list - then ALL are permitted
     */
    public
    void addIpFilterRule(IpFilterRule... rules) {
        ipFilterRules.addAll(Arrays.asList(rules));
    }

    /**
     * Adds an IP+subnet rule that defines what type of connection this IP+subnet should have.
     *  - NOTHING : Nothing happens to the in/out bytes
     *  - COMPRESS: The in/out bytes are compressed with LZ4-fast
     *  - COMPRESS_AND_ENCRYPT: The in/out bytes are compressed (LZ4-fast) THEN encrypted (AES-256-GCM)
     *
     * If no rules are defined, then for LOOPBACK, it will always be `COMPRESS` and for everything else it will always be `COMPRESS_AND_ENCRYPT`.
     *
     * If rules are defined, then everything by default is `COMPRESS_AND_ENCRYPT`.
     *
     * The compression algorithm is LZ4-fast, so there is a small performance impact for a very large gain
     *   Compress   :       6.210 micros/op;  629.0 MB/s (output: 55.4%)
     *   Uncompress :       0.641 micros/op; 6097.9 MB/s
     */
    public
    void addConnectionRules(ConnectionRule... rules) {
        connectionRules.addAll(Arrays.asList(rules));
    }

    /**
     * @return true if this server has successfully bound to an IP address and is running
     */
    public
    boolean isRunning() {
        return isRunning;
    }

    /**
     * Checks to see if a server (using the specified configuration) is running. This will check across JVMs by checking the
     * network socket directly, and assumes that if the port is in use and answers, then the server is "running". This does not try to
     * authenticate or validate the connection.
     * <p>
     * This does not check local-channels (which are intra-JVM only). Uses `Broadcast` to check for UDP servers
     * </p>
     *
     * @return true if the configuration matches and can connect (but not verify) to the TCP control socket.
     */
    public static
    boolean isRunning(Configuration config) {
        // create an IPC client to see if we can connect to the same machine. IF YES, then
        // String host = config.host;
        //
        // for us, we want a "*" host to connect to the "any" interface.
        // if (host == null) {
        //     host = "0.0.0.0";
        // }


        // create a client and see if it can connect

        if (config.port > 0) {
            // List<BroadcastResponse> broadcastResponses = null;
            // try {
            //     broadcastResponses = Broadcast.discoverHosts0(null, config.controlPort1, 500, true);
            //     return !broadcastResponses.isEmpty();
            // } catch (IOException ignored) {
            // }
        }

        return false;
    }


    private
    void onInitialClientMessage(final Publication publication,
                                final DirectBuffer buffer,
                                final int offset,
                                final int length,
                                final Header header) {
        final String message = EchoMessages.parseMessageUTF8(buffer, offset, length);

        final String session_name = Integer.toString(header.sessionId());
        final Integer session_boxed = Integer.valueOf(header.sessionId());

        this.executor.execute(()->{
            try {
                this.clients.onInitialClientMessageProcess(publication, session_name, session_boxed, message);
            } catch (final Exception e) {
                logger.error("could not process client message: ", e);
            }
        });
    }

    private
    void onInitialClientConnected(final Image image) {
        this.executor.execute(()->{
            logger.debug("[{}] initial client connected ({})", Integer.toString(image.sessionId()), image.sourceIdentity());

            this.clients.onInitialClientConnected(image.sessionId(), EchoAddresses.extractAddress(image.sourceIdentity()));
        });
    }

    private
    void onInitialClientDisconnected(final Image image) {
        this.executor.execute(()->{
            logger.debug("[{}] initial client disconnected ({})", Integer.toString(image.sessionId()), image.sourceIdentity());

            this.clients.onInitialClientDisconnected(image.sessionId());
        });
    }

    @Override
    public
    void close() {
        super.close();
        isRunning = false;
    }
}

