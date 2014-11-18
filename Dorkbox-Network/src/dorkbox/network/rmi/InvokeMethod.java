package dorkbox.network.rmi;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.KryoException;
import com.esotericsoftware.kryo.KryoSerializable;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;

/** Internal message to invoke methods remotely. */
class InvokeMethod implements KryoSerializable, RmiMessages {
    public int objectID;
    public CachedMethod cachedMethod;
    public Object[] args;

    // The top bits of the ID indicate if the remote invocation should respond with return values and exceptions, respectively.
    // The remaining bites are a counter. This means up to 63 responses can be stored before undefined behavior occurs due to
    // possible duplicate IDs. A response data of 0 means to not respond.
    public byte responseData;

    @Override
    @SuppressWarnings("rawtypes")
    public void write(Kryo kryo, Output output) {
        output.writeInt(this.objectID, true);
        output.writeInt(this.cachedMethod.methodClassID, true);
        output.writeByte(this.cachedMethod.methodIndex);

        Serializer[] serializers = this.cachedMethod.serializers;
        Object[] args = this.args;
        for (int i = 0, n = serializers.length; i < n; i++) {
            Serializer serializer = serializers[i];
            if (serializer != null) {
                kryo.writeObjectOrNull(output, args[i], serializer);
            } else {
                kryo.writeClassAndObject(output, args[i]);
            }
        }

        output.writeByte(this.responseData);
    }

    @Override
    public void read(Kryo kryo, Input input) {
        this.objectID = input.readInt(true);

        int methodClassID = input.readInt(true);
        Class<?> methodClass = kryo.getRegistration(methodClassID).getType();

        byte methodIndex = input.readByte();
        try {
            this.cachedMethod = RmiBridge.getMethods(kryo, methodClass)[methodIndex];
        } catch (IndexOutOfBoundsException ex) {
            throw new KryoException("Invalid method index " + methodIndex + " for class: " + methodClass.getName());
        }

        Serializer<?>[] serializers = this.cachedMethod.serializers;
        Class<?>[] parameterTypes = this.cachedMethod.method.getParameterTypes();
        Object[] args = new Object[serializers.length];
        this.args = args;
        for (int i = 0, n = args.length; i < n; i++) {
            Serializer<?> serializer = serializers[i];
            if (serializer != null) {
                args[i] = kryo.readObjectOrNull(input, parameterTypes[i], serializer);
            } else {
                args[i] = kryo.readClassAndObject(input);
            }
        }

        this.responseData = input.readByte();
    }
}
