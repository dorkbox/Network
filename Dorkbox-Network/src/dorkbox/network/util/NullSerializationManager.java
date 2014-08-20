package dorkbox.network.util;

import io.netty.buffer.ByteBuf;

import com.esotericsoftware.kryo.Registration;
import com.esotericsoftware.kryo.Serializer;

import dorkbox.network.connection.Connection;
import dorkbox.network.rmi.RmiRegisterClassesCallback;
import dorkbox.network.rmi.SerializerRegistration;

public class NullSerializationManager implements SerializationManager {

    @Override
    public boolean setReferences(boolean references) {
        return false;
    }

    @Override
    public void setRegistrationRequired(boolean registrationRequired) {
    }

    @Override
    public void register(Class<?> clazz) {
    }

    @Override
    public void register(Class<?> clazz, Serializer<?> serializer) {
    }

    @Override
    @SuppressWarnings("rawtypes")
    public void registerSerializer(Class<?> clazz, SerializerRegistration registration) {
    }

    @Override
    public Registration register(Class<?> type, Serializer<?> serializer, int id) {
        return null;
    }

    @Override
    public Registration getRegistration(Class<?> clazz) {
        return null;
    }

    @Override
    public boolean isEncrypted(ByteBuf buffer) {
        return false;
    }

    @Override
    public void write(ByteBuf buffer, Object message) {
    }

    @Override
    public void writeWithCryptoTcp(Connection connection, ByteBuf buffer, Object message) {
    }

    @Override
    public void writeWithCryptoUdp(Connection connection, ByteBuf buffer, Object message) {
    }

    @Override
    public Object read(ByteBuf buffer, int length) {
        return null;
    }

    @Override
    public Object readWithCryptoTcp(Connection connection, ByteBuf buffer, int length) {
        return null;
    }

    @Override
    public Object readWithCryptoUdp(Connection connection, ByteBuf buffer, int length) {
        return null;
    }

    @Override
    public void registerForRmiClasses(RmiRegisterClassesCallback callback) {
    }
}
