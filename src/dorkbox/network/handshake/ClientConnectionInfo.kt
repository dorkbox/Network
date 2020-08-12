package dorkbox.network.handshake

import org.slf4j.Logger

internal class ClientConnectionInfo(val subscriptionPort: Int,
                                    val publicationPort: Int,
                                    val sessionId: Int,
                                    val streamId: Int,
                                    val publicKey: ByteArray) {

    fun log(handshakeSessionId: Int, logger: Logger) {
        logger.debug("[{}] connected {}|{} (encrypted {})", handshakeSessionId, publicationPort, subscriptionPort, sessionId)
    }
}
