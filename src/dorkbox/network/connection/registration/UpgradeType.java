package dorkbox.network.connection.registration;

public
class UpgradeType {
    // The check is > 0, so these MUST be all > 0

    public static final byte NONE = (byte) 1;
    public static final byte ENCRYPT = (byte) 2;
    public static final byte COMPRESS = (byte) 3;
    public static final byte FRAGMENTED = (byte) 4;
}
