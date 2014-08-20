package dorkbox.network.rmi;

import com.esotericsoftware.kryo.Serializer;

public interface SerializerRegistration<T extends Serializer<?>> {
    public void register(T serializer);
}