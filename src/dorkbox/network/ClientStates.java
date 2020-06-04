package dorkbox.network;

import java.io.IOException;
import java.net.InetAddress;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.agrona.BufferUtil;
import org.agrona.concurrent.UnsafeBuffer;
import org.slf4j.Logger;

import dorkbox.network.aeron.EchoMessages;
import dorkbox.network.aeron.EchoServerAddressCounter;
import dorkbox.network.aeron.EchoServerDuologue;
import dorkbox.network.aeron.EchoServerExecutorService;
import dorkbox.network.aeron.EchoServerSessionAllocator;
import dorkbox.network.aeron.exceptions.EchoServerException;
import dorkbox.network.aeron.exceptions.EchoServerPortAllocationException;
import dorkbox.network.aeron.exceptions.EchoServerSessionAllocationException;
import dorkbox.network.aeron.server.PortAllocator;
import dorkbox.network.connection.EndPoint;
import io.aeron.Aeron;
import io.aeron.Publication;

class ClientStates {
    private static final Pattern PATTERN_HELLO = Pattern.compile("^HELLO ([0-9A-F]+)$");

    private final Map<Integer, InetAddress> client_session_addresses;
    private final Map<Integer, EchoServerDuologue> client_duologues;
    private final PortAllocator port_allocator;
    private final Aeron aeron;
    private final Clock clock;
    private final ServerConfiguration configuration;
    private Logger logger;
    private final UnsafeBuffer send_buffer;
    private final EchoServerExecutorService exec;
    private final EchoServerAddressCounter address_counter;
    private final EchoServerSessionAllocator session_allocator;

    ClientStates(final Aeron in_aeron,
                 final Clock in_clock,
                 final EchoServerExecutorService in_exec,
                 final ServerConfiguration in_configuration,
                 final Logger logger) {

        this.aeron = Objects.requireNonNull(in_aeron, "Aeron");
        this.clock = Objects.requireNonNull(in_clock, "Clock");
        this.exec = Objects.requireNonNull(in_exec, "Executor");
        this.configuration = Objects.requireNonNull(in_configuration, "Configuration");
        this.logger = logger;

        this.client_duologues = new HashMap<>(32);
        this.client_session_addresses = new HashMap<>(32);

        this.port_allocator = PortAllocator.create(this.configuration.clientStartPort,
                                                   2 * this.configuration.maxClientCount); // 2 ports used per connection

        this.address_counter = EchoServerAddressCounter.create();

        this.session_allocator = EchoServerSessionAllocator.create(EndPoint.RESERVED_SESSION_ID_LOW,
                                                                   EndPoint.RESERVED_SESSION_ID_HIGH,
                                                                   new SecureRandom());

        this.send_buffer = new UnsafeBuffer(BufferUtil.allocateDirectAligned(1024, 16));
    }

    void onInitialClientMessageProcess(final Publication publication,
                                       final String session_name,
                                       final Integer session_boxed,
                                       final String message) throws EchoServerException, IOException {
        this.exec.assertIsExecutorThread();

        logger.debug("[{}] received: {}", session_name, message);

        /*
         * The HELLO command is the only acceptable message from clients
         * on the all-clients channel.
         */
        final Matcher hello_matcher = PATTERN_HELLO.matcher(message);
        if (!hello_matcher.matches()) {
            EchoMessages.sendMessage(publication, this.send_buffer, errorMessage(session_name, "bad message"));
            return;
        }

        /*
         * Check to see if there are already too many clients connected.
         */
        if (this.client_duologues.size() >= this.configuration.maxClientCount) {
            logger.debug("server is full");
            EchoMessages.sendMessage(publication, this.send_buffer, errorMessage(session_name, "server full"));
            return;
        }

        /*
         * Check to see if this IP address already has the maximum number of
         * duologues allocated to it.
         */
        final InetAddress owner = this.client_session_addresses.get(session_boxed);

        if (this.address_counter.countFor(owner) >= this.configuration.maxConnectionsPerIpAddress) {
            logger.debug("too many connections for IP address");
            EchoMessages.sendMessage(publication, this.send_buffer, errorMessage(session_name, "too many connections for IP address"));
            return;
        }

        /*
         * Parse the one-time pad with which the client wants the server to
         * encrypt the identifier of the session that will be created.
         */
        final int duologue_key = Integer.parseUnsignedInt(hello_matcher.group(1), 16);

        /*
         * Allocate a new duologue, encrypt the resulting session ID, and send
         * a message to the client telling it where to find the new duologue.
         */
        final EchoServerDuologue duologue = this.allocateNewDuologue(session_name, session_boxed, owner);

        final String session_crypt = Integer.toUnsignedString(duologue_key ^ duologue.session(), 16)
                                            .toUpperCase();

        EchoMessages.sendMessage(publication,
                                 this.send_buffer,
                                 connectMessage(session_name, duologue.portData(), duologue.portControl(), session_crypt));
    }

    private static
    String connectMessage(final String session_name, final int port_data, final int port_control, final String session) {
        return new StringBuilder(64).append(session_name)
                                    .append(" CONNECT ")
                                    .append(port_data)
                                    .append(" ")
                                    .append(port_control)
                                    .append(" ")
                                    .append(session)
                                    .toString();
    }

    private static
    String errorMessage(final String session_name, final String message) {
        return new StringBuilder(64).append(session_name)
                                    .append(" ERROR ")
                                    .append(message)
                                    .toString();
    }

    private
    EchoServerDuologue allocateNewDuologue(final String session_name, final Integer session_boxed, final InetAddress owner)
            throws EchoServerPortAllocationException, EchoServerSessionAllocationException {
        this.address_counter.increment(owner);

        final EchoServerDuologue duologue;
        try {
            final int[] ports = this.port_allocator.allocate(2);
            try {
                final int session = this.session_allocator.allocate();
                try {
                    duologue = EchoServerDuologue.create(this.aeron,
                                                         this.clock,
                                                         this.exec,
                                                         this.configuration.listenIpAddress,
                                                         owner,
                                                         session,
                                                         ports[0],
                                                         ports[1]);
                    logger.debug("[{}] created new duologue", session_name);
                    this.client_duologues.put(session_boxed, duologue);
                } catch (final Exception e) {
                    this.session_allocator.free(session);
                    throw e;
                }
            } catch (final EchoServerSessionAllocationException e) {
                this.port_allocator.free(ports[0]);
                this.port_allocator.free(ports[1]);
                throw e;
            }
        } catch (final EchoServerPortAllocationException e) {
            this.address_counter.decrement(owner);
            throw e;
        }
        return duologue;
    }

    void onInitialClientDisconnected(final int session_id) {
        this.exec.assertIsExecutorThread();

        this.client_session_addresses.remove(Integer.valueOf(session_id));
    }

    void onInitialClientConnected(final int session_id, final InetAddress client_address) {
        this.exec.assertIsExecutorThread();

        this.client_session_addresses.put(Integer.valueOf(session_id), client_address);
    }

    public
    void poll() {
        this.exec.assertIsExecutorThread();

        final Iterator<Map.Entry<Integer, EchoServerDuologue>> iter = this.client_duologues.entrySet()
                                                                                           .iterator();

        /*
         * Get the current time; used to expire duologues.
         */

        final Instant now = this.clock.instant();

        while (iter.hasNext()) {
            final Map.Entry<Integer, EchoServerDuologue> entry = iter.next();
            final EchoServerDuologue duologue = entry.getValue();

            final String session_name = Integer.toString(entry.getKey()
                                                              .intValue());

            /*
             * If the duologue has either been closed, or has expired, it needs
             * to be deleted.
             */
            boolean delete = false;
            if (duologue.isExpired(now)) {
                logger.debug("[{}] duologue expired", session_name);
                delete = true;
            }

            if (duologue.isClosed()) {
                logger.debug("[{}] duologue closed", session_name);
                delete = true;
            }

            if (delete) {
                try {
                    duologue.close();
                } finally {
                    logger.debug("[{}] deleted duologue", session_name);
                    iter.remove();
                    this.port_allocator.free(duologue.portData());
                    this.port_allocator.free(duologue.portControl());
                    this.address_counter.decrement(duologue.ownerAddress());
                }
                continue;
            }

            /*
             * Otherwise, poll the duologue for activity.
             */
            duologue.poll();
        }
    }
}
