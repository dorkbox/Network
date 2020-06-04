package dorkbox.network.serialization;

/**
 * Signals the remote end that certain things need to happen
 */
public
class ControlMessage {
    public static final byte INVALID_STATUS = 0x0;
    public static final byte CONNECTING = 0x2;
    public static final byte CONNECTED = 0x3;

    public static final byte DISCONNECT = 0x7F; // max signed byte value, 127


    public byte command = INVALID_STATUS;
    public byte payload = INVALID_STATUS;

    public ControlMessage() {
    }
}
