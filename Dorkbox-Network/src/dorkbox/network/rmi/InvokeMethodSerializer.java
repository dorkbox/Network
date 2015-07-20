package dorkbox.network.rmi;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.KryoException;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import dorkbox.network.connection.KryoExtra;

/** Internal message to invoke methods remotely. */
public
class InvokeMethodSerializer extends Serializer<InvokeMethod> {
    private RmiBridge rmi;

    public InvokeMethodSerializer(RmiBridge rmi) {
        this.rmi = rmi;
    }

    @Override
    @SuppressWarnings("rawtypes")
    public void write(Kryo kryo, Output output, InvokeMethod object) {
        output.writeInt(object.objectID, true);
        output.writeInt(object.cachedMethod.methodClassID, true);
        output.writeByte(object.cachedMethod.methodIndex);

        Serializer[] serializers = object.cachedMethod.serializers;
        Object[] args = object.args;
        for (int i = 0, n = serializers.length; i < n; i++) {
            Serializer serializer = serializers[i];
            if (serializer != null) {
                kryo.writeObjectOrNull(output, args[i], serializer);
            } else {
                kryo.writeClassAndObject(output, args[i]);
            }
        }

        output.writeByte(object.responseData);
    }

    @Override
    public InvokeMethod read(Kryo kryo, Input input, Class<InvokeMethod> type) {
        InvokeMethod invokeMethod = new InvokeMethod();

        invokeMethod.objectID = input.readInt(true);

        int methodClassID = input.readInt(true);
        Class<?> methodClass = kryo.getRegistration(methodClassID).getType();

        byte methodIndex = input.readByte();
        try {
            KryoExtra kryoExtra = (KryoExtra) kryo;

            invokeMethod.cachedMethod = kryoExtra.getMethods(methodClass)[methodIndex];
        } catch (IndexOutOfBoundsException ex) {
            throw new KryoException("Invalid method index " + methodIndex + " for class: " + methodClass.getName());
        }

        Serializer<?>[] serializers = invokeMethod.cachedMethod.serializers;
        Class<?>[] parameterTypes = invokeMethod.cachedMethod.method.getParameterTypes();
        Object[] args = new Object[serializers.length];
        invokeMethod.args = args;
        for (int i = 0, n = args.length; i < n; i++) {
            Serializer<?> serializer = serializers[i];
            if (serializer != null) {
                args[i] = kryo.readObjectOrNull(input, parameterTypes[i], serializer);
            } else {
                args[i] = kryo.readClassAndObject(input);
            }
        }

        invokeMethod.responseData = input.readByte();

        return invokeMethod;
    }
}
