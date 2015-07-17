package dorkbox.network.util;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Registration;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import dorkbox.network.connection.Connection;
import dorkbox.network.rmi.RmiRegisterClassesCallback;
import dorkbox.network.rmi.SerializerRegistration;
import io.netty.buffer.ByteBuf;

public
class NullConnectionSerializationManager implements ConnectionSerializationManager {

    @Override
    public
    void register(Class<?> clazz) {
    }

    @Override
    public
    void register(Class<?> clazz, Serializer<?> serializer) {
    }

    @Override
    public
    void register(Class<?> type, Serializer<?> serializer, int id) {
    }

    @Override
    public
    void write(ByteBuf buffer, Object message) {
    }

    @Override
    public
    Object read(ByteBuf buffer, int length) {
        return null;
    }

    @Override
    public
    void writeFullClassAndObject(final Output output, final Object value) {

    }

    @Override
    public
    Object readFullClassAndObject(final Input input) {
        return null;
    }

    @Override
    public
    Kryo borrow() {
        return null;
    }

    @Override
    public
    void release(final Kryo kryo) {

    }

    @Override
    @SuppressWarnings("rawtypes")
    public
    void registerSerializer(Class<?> clazz, SerializerRegistration registration) {
    }

    @Override
    public
    void registerForRmiClasses(RmiRegisterClassesCallback callback) {
    }

    @Override
    public
    Registration getRegistration(Class<?> clazz) {
        return null;
    }

    @Override
    public
    boolean isEncrypted(ByteBuf buffer) {
        return false;
    }

    @Override
    public
    void writeWithCryptoTcp(Connection connection, ByteBuf buffer, Object message) {
    }

    @Override
    public
    void writeWithCryptoUdp(Connection connection, ByteBuf buffer, Object message) {
    }

    @Override
    public
    Object readWithCryptoTcp(Connection connection, ByteBuf buffer, int length) {
        return null;
    }

    @Override
    public
    Object readWithCryptoUdp(Connection connection, ByteBuf buffer, int length) {
        return null;
    }
}
