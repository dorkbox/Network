package dorkbox.network.rmi;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.sun.xml.internal.ws.encoding.soap.SerializationException;

import dorkbox.network.connection.Connection;
import dorkbox.network.connection.EndPoint;

/**
 * Serializes an object registered with the RmiBridge so the receiving side
 * gets a {@link RemoteObject} proxy rather than the bytes for the serialized
 * object.
 *
 * @author Nathan Sweet <misc@n4te.com>
 */
public class RemoteObjectSerializer<T> extends Serializer<T> {

    private final RmiBridge rmi;

    public RemoteObjectSerializer(EndPoint endpoint) {
        this.rmi = (RmiBridge) endpoint.rmi();
    }

    @Override
    public void write(Kryo kryo, Output output, T object) {
        int id = this.rmi.getRegisteredId(object);
        if (id == Integer.MAX_VALUE) {
            throw new SerializationException("Object not found in an ObjectSpace: " + object);
        }

        output.writeInt(id, true);
    }

    @SuppressWarnings({"rawtypes","unchecked"})
    @Override
    public T read(Kryo kryo, Input input, Class type) {
        int objectID = input.readInt(true);
        Connection connection = (Connection) kryo.getContext().get(Connection.connection);
        return (T) this.rmi.getRemoteObject(connection, objectID, type);
    }
}