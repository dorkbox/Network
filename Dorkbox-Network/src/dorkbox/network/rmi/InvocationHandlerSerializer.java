package dorkbox.network.rmi;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import dorkbox.network.connection.KryoExtra;
import org.slf4j.Logger;

import java.lang.reflect.Proxy;

public
class InvocationHandlerSerializer extends Serializer<Object> {
    private final org.slf4j.Logger logger;

    public
    InvocationHandlerSerializer(final Logger logger) {
        this.logger = logger;
    }

    @Override
    public
    void write(Kryo kryo, Output output, Object object) {
        RemoteInvocationHandler handler = (RemoteInvocationHandler) Proxy.getInvocationHandler(object);
        output.writeInt(handler.objectID, true);
    }

    @Override
    @SuppressWarnings({"unchecked", "AutoBoxing"})
    public
    Object read(Kryo kryo, Input input, Class<Object> type) {
        int objectID = input.readInt(true);

        KryoExtra kryoExtra = (KryoExtra) kryo;
        Object object = kryoExtra.connection.getImplementationObject(objectID);

        if (object == null) {
            logger.error("Unknown object ID in RMI ObjectSpace: {}", objectID);
        }
        return object;
    }
}
