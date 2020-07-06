package dorkbox.network.connection

import org.slf4j.Logger

class ClientConnectionInfo(val subscriptionPort: Int,
                           val publicationPort: Int,
                           val sessionId: Int,
                           val streamId: Int,
                           val publicKey: ByteArray) {

    fun log(handshakeSessionId: Int, logger: Logger) {
        logger.debug("[{}] connect {} {} (encrypted {})", handshakeSessionId, subscriptionPort, publicationPort, sessionId)
    }
}
