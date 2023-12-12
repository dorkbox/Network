/*
 * Copyright 2023 dorkbox, llc
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

@file:Suppress("MemberVisibilityCanBePrivate", "DuplicatedCode")

package dorkbox.network.handshake

import dorkbox.netUtil.IP
import dorkbox.network.Configuration
import dorkbox.network.Server
import dorkbox.network.ServerConfiguration
import dorkbox.network.aeron.AeronDriver
import dorkbox.network.aeron.AeronDriver.Companion.uriHandshake
import dorkbox.network.aeron.AeronPoller
import dorkbox.network.connection.Connection
import dorkbox.network.connection.IpInfo
import dorkbox.network.exceptions.ServerException
import dorkbox.network.exceptions.ServerHandshakeException
import dorkbox.network.exceptions.ServerTimedoutException
import dorkbox.util.NamedThreadFactory
import dorkbox.util.Sys
import io.aeron.CommonContext
import io.aeron.FragmentAssembler
import io.aeron.Image
import io.aeron.Publication
import io.aeron.logbuffer.FragmentHandler
import io.aeron.logbuffer.Header
import net.jodah.expiringmap.ExpirationPolicy
import net.jodah.expiringmap.ExpiringMap
import org.agrona.DirectBuffer
import org.slf4j.Logger
import java.net.Inet4Address
import java.util.concurrent.*

internal object ServerHandshakePollers {
    fun disabled(serverInfo: String): AeronPoller {
        return object : AeronPoller {
            override fun poll(): Int { return 0 }
            override fun close() {}
            override val info = serverInfo
        }
    }

    class IpcProc<CONNECTION : Connection>(
        val logger: Logger,
        val server: Server<CONNECTION>,
        val driver: AeronDriver,
        val handshake: ServerHandshake<CONNECTION>
    ): FragmentHandler {

        private val isReliable = server.config.isReliable
        private val handshaker = server.handshaker
        private val handshakeTimeoutNs = handshake.handshakeTimeoutNs

        // note: the expire time here is a LITTLE longer than the expire time in the client, this way we can adjust for network lag if it's close
        private val publications = ExpiringMap.builder()
            .apply {
                this.expiration(handshakeTimeoutNs, TimeUnit.NANOSECONDS)
            }
            .expirationPolicy(ExpirationPolicy.CREATED)
            .expirationListener<Long, Publication> { connectKey, publication ->
                try {
                    // we might not be able to close this connection.
                    driver.close(publication, "Server IPC Handshake ($connectKey)")
                }
                catch (e: Exception) {
                    server.listenerManager.notifyError(e)
                }

            }
            .build<Long, Publication>()

        override fun onFragment(buffer: DirectBuffer, offset: Int, length: Int, header: Header) {
            // this is processed on the thread that calls "poll". Subscriptions are NOT multi-thread safe!

            // The sessionId is unique within a Subscription and unique across all Publication's from a sourceIdentity.
            // for the handshake, the sessionId IS NOT GLOBALLY UNIQUE
            val sessionId = header.sessionId()
            val streamId = header.streamId()
            val image = header.context() as Image

            val logInfo = "$sessionId/$streamId : IPC" // Server is the "source", client mirrors the server

            // ugh, this is verbose -- but necessary
            val message = try {
                val msg = handshaker.readMessage(buffer, offset, length)

                // VALIDATE:: a Registration object is the only acceptable message during the connection phase
                if (msg !is HandshakeMessage) {
                    throw ServerHandshakeException("[$logInfo] Connection not allowed! unrecognized message: $msg")
                } else if (logger.isTraceEnabled) {
                    logger.trace("[$logInfo] (${msg.connectKey}) received HS: $msg")
                }
                msg
            } catch (e: Exception) {
                server.listenerManager.notifyError(ServerHandshakeException("[$logInfo] Error de-serializing handshake message!!", e))
                null
            }


            if (message == null) {
                // we should immediately remove the logbuffer for this! Aeron will **EVENTUALLY** remove the logbuffer, but if errors
                // and connections occur too quickly (within the cleanup/linger period), we can run out of memory!
                driver.deleteLogFile(image)
                return
            }


            // NOTE: This MUST to happen in separates thread so that we can take as long as we need when creating publications and handshaking,
            //  because under load -- this will REGULARLY timeout! Under no circumstance can this happen in the main processing thread!!
            server.eventDispatch.HANDSHAKE.launch {
                // we have read all the data, now dispatch it.
                // HandshakeMessage.HELLO
                // HandshakeMessage.DONE
                val messageState = message.state
                val connectKey = message.connectKey


                if (messageState == HandshakeMessage.HELLO) {
                    // we create a NEW publication for the handshake, which connects directly to the client handshake subscription

                    val publicationUri = uriHandshake(CommonContext.IPC_MEDIA, isReliable)

                    // this will always connect to the CLIENT handshake subscription!
                    val publication = try {
                        driver.addPublication(publicationUri, message.streamId, logInfo, true)
                    }
                    catch (e: Exception) {
                        // we should immediately remove the logbuffer for this! Aeron will **EVENTUALLY** remove the logbuffer, but if errors
                        // and connections occur too quickly (within the cleanup/linger period), we can run out of memory!
                        driver.deleteLogFile(image)

                        server.listenerManager.notifyError(ServerHandshakeException("[$logInfo] Cannot create IPC publication back to client remote process", e))
                        return@launch
                    }

                    try {
                        // we actually have to wait for it to connect before we continue
                        driver.waitForConnection(publication, handshakeTimeoutNs, logInfo) { cause ->
                            ServerTimedoutException("$logInfo publication cannot connect with client in ${Sys.getTimePrettyFull(handshakeTimeoutNs)}", cause)
                        }
                    }
                    catch (e: Exception) {
                        // we should immediately remove the logbuffer for this! Aeron will **EVENTUALLY** remove the logbuffer, but if errors
                        // and connections occur too quickly (within the cleanup/linger period), we can run out of memory!
                        driver.deleteLogFile(image)

                        server.listenerManager.notifyError(ServerHandshakeException("[$logInfo] Cannot create IPC publication back to client remote process", e))
                        return@launch
                    }


                    try {
                        val success = handshake.processIpcHandshakeMessageServer(
                            server = server,
                            handshaker = handshaker,
                            aeronDriver = driver,
                            handshakePublication = publication,
                            publicKey = message.publicKey!!,
                            message = message,
                            logInfo = logInfo,
                            logger = logger
                        )

                        if (success) {
                            publications[connectKey] = publication
                        }
                        else {
                            try {
                                // we might not be able to close this connection.
                                driver.close(publication, logInfo)
                            }
                            catch (e: Exception) {
                                server.listenerManager.notifyError(e)
                            }
                        }
                    }
                    catch (e: Exception) {
                        // we should immediately remove the logbuffer for this! Aeron will **EVENTUALLY** remove the logbuffer, but if errors
                        // and connections occur too quickly (within the cleanup/linger period), we can run out of memory!
                        driver.deleteLogFile(image)

                        try {
                            // we might not be able to close this connection.
                            driver.close(publication, logInfo)
                        }
                        catch (e: Exception) {
                            server.listenerManager.notifyError(e)
                        }

                        server.listenerManager.notifyError(ServerHandshakeException("[$logInfo] Error processing IPC handshake", e))
                    }
                } else {
                    // HandshakeMessage.DONE

                    val publication = publications.remove(connectKey)
                    if (publication == null) {
                        // we should immediately remove the logbuffer for this! Aeron will **EVENTUALLY** remove the logbuffer, but if errors
                        // and connections occur too quickly (within the cleanup/linger period), we can run out of memory!
                        driver.deleteLogFile(image)

                        server.listenerManager.notifyError(ServerHandshakeException("[$logInfo] No publication back to IPC"))
                        return@launch
                    }

                    try {
                        handshake.validateMessageTypeAndDoPending(
                            server = server,
                            handshaker = handshaker,
                            handshakePublication = publication,
                            message = message,
                            logInfo = logInfo,
                            logger = logger
                        )
                    } catch (e: Exception) {
                        server.listenerManager.notifyError(ServerHandshakeException("[$logInfo] Error processing IPC handshake", e))
                    }

                    // we should immediately remove the logbuffer for this! Aeron will **EVENTUALLY** remove the logbuffer, but if errors
                    // and connections occur too quickly (within the cleanup/linger period), we can run out of memory!
                    driver.deleteLogFile(image)

                    try {
                        // we might not be able to close this connection.
                        driver.close(publication, logInfo)
                    }
                    catch (e: Exception) {
                        server.listenerManager.notifyError(e)
                    }
                }
            }
        }

        fun close() {
            publications.forEach { (connectKey, publication) ->
                AeronDriver.sessionIdAllocator.free(publication.sessionId())
                try {
                    // we might not be able to close this connection.
                    driver.close(publication, "Server Handshake ($connectKey)")
                }
                catch (e: Exception) {
                    server.listenerManager.notifyError(e)
                }
            }
            publications.clear()
        }
    }

    class UdpProc<CONNECTION : Connection>(
        val logger: Logger,
        val server: Server<CONNECTION>,
        val driver: AeronDriver,
        val handshake: ServerHandshake<CONNECTION>,
        val isReliable: Boolean
    ): FragmentHandler {
        companion object {
            init {
                ExpiringMap.setThreadFactory(NamedThreadFactory("ExpiringMap-Eviction", Configuration.networkThreadGroup, true))
            }
        }

        private val ipInfo = server.ipInfo
        private val handshaker = server.handshaker
        private val handshakeTimeoutNs = handshake.handshakeTimeoutNs

        private val serverPortSub = server.port1
        // MDC 'dynamic control mode' means that the server will to listen for status messages and NAK (from the client) on a port.
        private val mdcPortPub = server.port2

        // note: the expire time here is a LITTLE longer than the expire time in the client, this way we can adjust for network lag if it's close
        private val publications = ExpiringMap.builder()
            .apply {

                this.expiration(handshakeTimeoutNs, TimeUnit.NANOSECONDS)
            }
            .expirationPolicy(ExpirationPolicy.CREATED)
            .expirationListener<Long, Publication> { connectKey, publication ->
                try {
                    // we might not be able to close this connection.
                    driver.close(publication, "Server UDP Handshake ($connectKey)")
                }
                catch (e: Exception) {
                    server.listenerManager.notifyError(e)
                }
            }
            .build<Long, Publication>()


        override fun onFragment(buffer: DirectBuffer, offset: Int, length: Int, header: Header) {
            // The sessionId is unique within a Subscription and unique across all Publication's from a sourceIdentity.
            // for the handshake, the sessionId IS NOT GLOBALLY UNIQUE


            // this is processed on the thread that calls "poll". Subscriptions are NOT multi-thread safe!

            // The sessionId is unique within a Subscription and unique across all Publication's from a sourceIdentity.
            // for the handshake, the sessionId IS NOT GLOBALLY UNIQUE
            val sessionId = header.sessionId()
            val streamId = header.streamId()
            val image = header.context() as Image

            // note: this address will ALWAYS be an IP:PORT combo  OR  it will be aeron:ipc  (if IPC, it will be a different handler!)
            val remoteIpAndPort = image.sourceIdentity()

            // split
            val splitPoint = remoteIpAndPort.lastIndexOf(':')
            var clientAddressString = remoteIpAndPort.substring(0, splitPoint)

            // this should never be null, because we are feeding it a valid IP address from aeron
            val clientAddress = IP.toAddress(clientAddressString)
            if (clientAddress == null) {
                // we should immediately remove the logbuffer for this! Aeron will **EVENTUALLY** remove the logbuffer, but if errors
                // and connections occur too quickly (within the cleanup/linger period), we can run out of memory!
                driver.deleteLogFile(image)


                // Server is the "source", client mirrors the server
                server.listenerManager.notifyError(ServerHandshakeException("[$sessionId/$streamId] Connection from $clientAddressString not allowed! Invalid IP address!"))
                return
            }

            val isRemoteIpv4 = clientAddress is Inet4Address
            if (!isRemoteIpv4) {
                // this is necessary to clean up the address when adding it to aeron, since different formats mess it up
                clientAddressString = IP.toString(clientAddress)

                if (ipInfo.ipType == IpInfo.Companion.IpListenType.IPv4Wildcard) {
                    // we should immediately remove the logbuffer for this! Aeron will **EVENTUALLY** remove the logbuffer, but if errors
                    // and connections occur too quickly (within the cleanup/linger period), we can run out of memory!
                    driver.deleteLogFile(image)

                    // we DO NOT want to listen to IPv4 traffic, but we received IPv4 traffic!
                    server.listenerManager.notifyError(ServerHandshakeException("[$sessionId/$streamId] Connection from $clientAddressString not allowed! IPv4 connections not permitted!"))
                    return
                }
            }

            val logInfo = "$sessionId/$streamId:$clientAddressString"


            // ugh, this is verbose -- but necessary
            val message = try {
                val msg = handshaker.readMessage(buffer, offset, length)

                // VALIDATE:: a Registration object is the only acceptable message during the connection phase
                if (msg !is HandshakeMessage) {
                    throw ServerHandshakeException("[$logInfo] Connection not allowed! unrecognized message: $msg")
                } else if (logger.isTraceEnabled) {
                    logger.trace("[$logInfo] (${msg.connectKey}) received HS: $msg")
                }
                msg
            } catch (e: Exception) {
                server.listenerManager.notifyError(ServerHandshakeException("[$logInfo] Error de-serializing handshake message!!", e))
                null
            }

            if (message == null) {
                // we should immediately remove the logbuffer for this! Aeron will **EVENTUALLY** remove the logbuffer, but if errors
                // and connections occur too quickly (within the cleanup/linger period), we can run out of memory!
                driver.deleteLogFile(image)
                return
            }

            // NOTE: This MUST to happen in separates thread so that we can take as long as we need when creating publications and handshaking,
            //  because under load -- this will REGULARLY timeout! Under no circumstance can this happen in the main processing thread!!
            server.eventDispatch.HANDSHAKE.launch {
                // HandshakeMessage.HELLO
                // HandshakeMessage.DONE
                val messageState = message.state
                val connectKey = message.connectKey

                if (messageState == HandshakeMessage.HELLO) {
                    // we create a NEW publication for the handshake, which connects directly to the client handshake subscription

                    // we explicitly have the publisher "connect to itself", because we are using MDC to work around NAT.
                    // It will "auto-connect" to the correct client port (negotiated by the MDC client subscription negotiating on the
                    // control port of the server)
                    val publicationUri = uriHandshake(CommonContext.UDP_MEDIA, isReliable)
                        .controlEndpoint(ipInfo.getAeronPubAddress(isRemoteIpv4) + ":" + mdcPortPub)


                    // this will always connect to the CLIENT handshake subscription!
                    val publication = try {
                        driver.addPublication(publicationUri, message.streamId, logInfo, false)
                    } catch (e: Exception) {
                        // we should immediately remove the logbuffer for this! Aeron will **EVENTUALLY** remove the logbuffer, but if errors
                        // and connections occur too quickly (within the cleanup/linger period), we can run out of memory!
                        driver.deleteLogFile(image)

                        server.listenerManager.notifyError(ServerHandshakeException("[$logInfo] Cannot create publication back to $clientAddressString", e))
                        return@launch
                    }

                    try {
                        // we actually have to wait for it to connect before we continue.
                        //
                        driver.waitForConnection(publication, handshakeTimeoutNs, logInfo) { cause ->
                            ServerTimedoutException("$logInfo publication cannot connect with client in ${Sys.getTimePrettyFull(handshakeTimeoutNs)}", cause)
                        }
                    } catch (e: Exception) {
                        // we should immediately remove the logbuffer for this! Aeron will **EVENTUALLY** remove the logbuffer, but if errors
                        // and connections occur too quickly (within the cleanup/linger period), we can run out of memory!
                        driver.deleteLogFile(image)

                        server.listenerManager.notifyError(ServerHandshakeException("[$logInfo] Cannot create publication back to $clientAddressString", e))
                        return@launch
                    }

                    try {
                        val success = handshake.processUdpHandshakeMessageServer(
                            server = server,
                            handshaker = handshaker,
                            handshakePublication = publication,
                            publicKey = message.publicKey!!,
                            clientAddress = clientAddress,
                            clientAddressString = clientAddressString,
                            portPub = message.port,
                            portSub = serverPortSub,
                            mdcPortPub = mdcPortPub,
                            isReliable = isReliable,
                            message = message,
                            logInfo = logInfo,
                            logger = logger
                        )

                        if (success) {
                            publications[connectKey] = publication
                        } else {
                            // we should immediately remove the logbuffer for this! Aeron will **EVENTUALLY** remove the logbuffer, but if errors
                            // and connections occur too quickly (within the cleanup/linger period), we can run out of memory!
                            driver.deleteLogFile(image)

                            try {
                                // we might not be able to close this connection.
                                driver.close(publication, logInfo)
                            }
                            catch (e: Exception) {
                                server.listenerManager.notifyError(e)
                            }
                        }
                    } catch (e: Exception) {
                        // we should immediately remove the logbuffer for this! Aeron will **EVENTUALLY** remove the logbuffer, but if errors
                        // and connections occur too quickly (within the cleanup/linger period), we can run out of memory!
                        driver.deleteLogFile(image)

                        try {
                            // we might not be able to close this connection.
                            driver.close(publication, logInfo)
                        }
                        catch (e: Exception) {
                            driver.close(publication, logInfo)
                        }

                        server.listenerManager.notifyError(ServerHandshakeException("[$logInfo] Error processing IPC handshake", e))
                    }
                } else {
                    // HandshakeMessage.DONE

                    val publication = publications.remove(connectKey)

                    if (publication == null) {
                        // we should immediately remove the logbuffer for this! Aeron will **EVENTUALLY** remove the logbuffer, but if errors
                        // and connections occur too quickly (within the cleanup/linger period), we can run out of memory!
                        driver.deleteLogFile(image)

                        server.listenerManager.notifyError(ServerHandshakeException("[$logInfo] No publication back to $clientAddressString"))
                        return@launch
                    }

                    try {
                        handshake.validateMessageTypeAndDoPending(
                            server = server,
                            handshaker = handshaker,
                            handshakePublication = publication,
                            message = message,
                            logInfo = logInfo,
                            logger = logger
                        )
                    } catch (e: Exception) {
                        server.listenerManager.notifyError(ServerHandshakeException("[$logInfo] Error processing IPC handshake", e))
                    }

                    try {
                        // we might not be able to close this connection.
                        driver.close(publication, logInfo)
                    }
                    catch (e: Exception) {
                        server.listenerManager.notifyError(e)
                    }

                    // we should immediately remove the logbuffer for this! Aeron will **EVENTUALLY** remove the logbuffer, but if errors
                    // and connections occur too quickly (within the cleanup/linger period), we can run out of memory!
                    driver.deleteLogFile(image)
                }
            }
        }

        fun close() {
            publications.forEach { (connectKey, publication) ->
                AeronDriver.sessionIdAllocator.free(publication.sessionId())

                try {
                    // we might not be able to close this connection.
                    driver.close(publication, "Server Handshake ($connectKey)")
                }
                catch (e: Exception) {
                    server.listenerManager.notifyError(e)
                }
            }
            publications.clear()
        }
    }

    fun <CONNECTION : Connection> ipc(server: Server<CONNECTION>, handshake: ServerHandshake<CONNECTION>): AeronPoller {
        val logger = server.logger
        val config = server.config as ServerConfiguration

        val poller = try {
            val driver = ServerHandshakeDriver.build(
                aeronDriver = server.aeronDriver,
                isIpc = true,
                port = 0,
                ipInfo = server.ipInfo,
                streamIdSub = config.ipcId,
                sessionIdSub = AeronDriver.RESERVED_SESSION_ID_INVALID,
                logInfo = "HANDSHAKE-IPC"
            )

            object : AeronPoller {
                // NOTE: Handlers are called on the client conductor thread. The client conductor thread expects handlers to do safe
                //  publication of any state to other threads and not be:
                //   - long running
                //   - re-entrant with the client
                val subscription = driver.subscription

                val delegate = IpcProc(logger, server, server.aeronDriver, handshake)
                val handler = FragmentAssembler(delegate)

                override fun poll(): Int {
                    return subscription.poll(handler, 1)
                }

                override fun close() {
                    delegate.close()
                    handler.clear()
                    driver.close(server)
                    logger.info("Closed IPC poller")
                }

                override val info = "IPC ${driver.info}"
            }
        } catch (e: Exception) {
            server.listenerManager.notifyError(ServerException("Unable to create IPC listener.", e))
            disabled("IPC Disabled")
        }

        return poller
    }



    fun <CONNECTION : Connection> ip4(server: Server<CONNECTION>, handshake: ServerHandshake<CONNECTION>): AeronPoller {
        val logger = server.logger
        val config = server.config
        val isReliable = config.isReliable

        val poller = try {
            val driver = ServerHandshakeDriver.build(
                aeronDriver = server.aeronDriver,
                isIpc = false,
                ipInfo = server.ipInfo,
                port = server.port1,
                streamIdSub = config.udpId,
                sessionIdSub = 9,
                logInfo = "HANDSHAKE-IPv4"
            )

            object : AeronPoller {
                // NOTE: Handlers are called on the client conductor thread. The client conductor thread expects handlers to do safe
                //  publication of any state to other threads and not be:
                //   - long running
                //   - re-entrant with the client
                val subscription = driver.subscription

                val delegate = UdpProc(logger, server, server.aeronDriver, handshake, isReliable)
                val handler = FragmentAssembler(delegate)

                override fun poll(): Int {
                    return subscription.poll(handler, 1)
                }

                override fun close() {
                    delegate.close()
                    handler.clear()
                    driver.close(server)
                    logger.info("Closed IPv4 poller")
                }

                override val info = "IPv4 ${driver.info}"
            }
        } catch (e: Exception) {
            server.listenerManager.notifyError(ServerException("Unable to create IPv4 listener.", e))
            disabled("IPv4 Disabled")
        }

        return poller
    }

    fun <CONNECTION : Connection> ip6(server: Server<CONNECTION>, handshake: ServerHandshake<CONNECTION>): AeronPoller {
        val logger = server.logger
        val config = server.config
        val isReliable = config.isReliable

        val poller =  try {
            val driver = ServerHandshakeDriver.build(
                aeronDriver = server.aeronDriver,
                isIpc = false,
                ipInfo = server.ipInfo,
                port = server.port1,
                streamIdSub = config.udpId,
                sessionIdSub = 0,
                logInfo = "HANDSHAKE-IPv6"
            )

            object : AeronPoller {
                // NOTE: Handlers are called on the client conductor thread. The client conductor thread expects handlers to do safe
                //  publication of any state to other threads and not be:
                //   - long running
                //   - re-entrant with the client
                val subscription = driver.subscription

                val delegate = UdpProc(logger, server, server.aeronDriver, handshake, isReliable)
                val handler = FragmentAssembler(delegate)

                override fun poll(): Int {
                    return subscription.poll(handler, 1)
                }

                override fun close() {
                    delegate.close()
                    handler.clear()
                    driver.close(server)
                    logger.info("Closed IPv4 poller")
                }

                override val info = "IPv6 ${driver.info}"
            }
        } catch (e: Exception) {
            server.listenerManager.notifyError(ServerException("Unable to create IPv6 listener."))
            disabled("IPv6 Disabled")
        }

        return poller
    }

    fun <CONNECTION : Connection> ip6Wildcard(server: Server<CONNECTION>, handshake: ServerHandshake<CONNECTION>): AeronPoller {

        val logger = server.logger
        val config = server.config
        val isReliable = config.isReliable

        val poller = try {
            val driver = ServerHandshakeDriver.build(
                aeronDriver = server.aeronDriver,
                isIpc = false,
                ipInfo = server.ipInfo,
                port = server.port1,
                streamIdSub = config.udpId,
                sessionIdSub = 0,
                logInfo = "HANDSHAKE-IPv4+6"
            )

            object : AeronPoller {
                // NOTE: Handlers are called on the client conductor thread. The client conductor thread expects handlers to do safe
                //  publication of any state to other threads and not be:
                //   - long running
                //   - re-entrant with the client
                val subscription = driver.subscription

                val delegate = UdpProc(logger, server, server.aeronDriver, handshake, isReliable)
                val handler = FragmentAssembler(delegate)

                override fun poll(): Int {
                    return subscription.poll(handler, 1)
                }

                override fun close() {
                    delegate.close()
                    handler.clear()
                    driver.close(server)
                    logger.info("Closed IPv4+6 poller")
                }

                override val info = "IPv4+6 ${driver.info}"
            }
        } catch (e: Exception) {
            server.listenerManager.notifyError(ServerException("Unable to create IPv4+6 listeners.", e))
            disabled("IPv4+6 Disabled")
        }

        return poller
    }
}
