package dorkbox.network.rmi;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import dorkbox.network.connection.ConnectionImpl;
import dorkbox.network.connection.KryoExtra;
import dorkbox.network.util.exceptions.NetException;

/**
 * Serializes an object registered with the RmiBridge so the receiving side
 * gets a {@link RemoteObject} proxy rather than the bytes for the serialized
 * object.
 *
 * @author Nathan Sweet <misc@n4te.com>
 */
public class RemoteObjectSerializer<T> extends Serializer<T> {

    public RemoteObjectSerializer() {
    }

    @Override
    public void write(Kryo kryo, Output output, T object) {
        KryoExtra kryoExtra = (KryoExtra) kryo;
        int id = kryoExtra.connection.getRegisteredId(object);
        if (id == Integer.MAX_VALUE) {
            throw new NetException("Object not found in an ObjectSpace: " + object);
        }

        output.writeInt(id, true);
    }

    @SuppressWarnings({"rawtypes","unchecked"})
    @Override
    public T read(Kryo kryo, Input input, Class type) {
        KryoExtra kryoExtra = (KryoExtra) kryo;
        int objectID = input.readInt(true);
        final ConnectionImpl connection = kryoExtra.connection;
        return (T) connection.rmiBridge.getRemoteObject(connection, objectID, type);
    }
}
