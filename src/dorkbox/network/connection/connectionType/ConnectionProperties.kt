package dorkbox.network.connection.connectionType

import dorkbox.network.handshake.UpgradeType

/**
 * Used in [IpConnectionTypeRule] to decide what kind of connection a matching IP Address should have.
 */
enum class ConnectionProperties(val type: Byte) {
    /**
     * No compression, no encryption
     */
    NOTHING(UpgradeType.NONE),

    /**
     * Only compression
     */
    COMPRESS(UpgradeType.COMPRESS),

    /**
     * Compression + encryption
     */
    COMPRESS_AND_ENCRYPT(UpgradeType.ENCRYPT);

}
