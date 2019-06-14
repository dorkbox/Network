package dorkbox.network.connection.connectionType;

import dorkbox.network.connection.registration.UpgradeType;

/**
 * Used in {@link IpConnectionTypeRule} to decide what kind of connection a matching IP Address should have.
 */
public enum ConnectionType {
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
    COMPRESS_AND_ENCRYPT(UpgradeType.ENCRYPT)
    ;

    private final byte type;

    ConnectionType(byte type) {
        this.type = type;
    }

    public
    byte getType() {
        return type;
    }
}
