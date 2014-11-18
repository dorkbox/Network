package dorkbox.network.rmi;

import java.lang.reflect.Method;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.KryoException;
import com.esotericsoftware.kryo.KryoSerializable;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;

/** Internal message to invoke methods remotely. */
class InvokeMethod implements KryoSerializable, RmiMessages {
    public int objectID;
    public Method method;
    public Object[] args;
    // The top bits of the ID indicate if the remote invocation should respond with return values and exceptions, respectively.
    // The remaining bites are a counter. This means up to 63 responses can be stored before undefined behavior occurs due to
    // possible duplicate IDs. A response data of 0 means to not respond.
    public byte responseData;

    @Override
    @SuppressWarnings("rawtypes")
    public void write(Kryo kryo, Output output) {
        output.writeInt(this.objectID, true);

        int methodClassID = kryo.getRegistration(this.method.getDeclaringClass()).getId();
        output.writeInt(methodClassID, true);

        CachedMethod[] cachedMethods = RmiBridge.getMethods(kryo, this.method.getDeclaringClass());
        CachedMethod cachedMethod = null;
        for (int i = 0, n = cachedMethods.length; i < n; i++) {
            cachedMethod = cachedMethods[i];
            if (cachedMethod.method.equals(this.method)) {
                output.writeByte(i);
                break;
            }
        }

        if (cachedMethod == null) {
            throw new KryoException("Cached method was null for class: " + this.method.getDeclaringClass().getName());
        }

        for (int i = 0, n = cachedMethod.serializers.length; i < n; i++) {
            Serializer serializer = cachedMethod.serializers[i];
            if (serializer != null) {
                kryo.writeObjectOrNull(output, this.args[i], serializer);
            } else {
                kryo.writeClassAndObject(output, this.args[i]);
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

        CachedMethod cachedMethod;
        try {
            cachedMethod = RmiBridge.getMethods(kryo, methodClass)[methodIndex];
        } catch (IndexOutOfBoundsException ex) {
            throw new KryoException("Invalid method index " + methodIndex + " for class: " + methodClass.getName());
        }
        this.method = cachedMethod.method;

        this.args = new Object[cachedMethod.serializers.length];
        for (int i = 0, n = this.args.length; i < n; i++) {
            Serializer<?> serializer = cachedMethod.serializers[i];
            if (serializer != null) {
                this.args[i] = kryo.readObjectOrNull(input, this.method.getParameterTypes()[i], serializer);
            } else {
                this.args[i] = kryo.readClassAndObject(input);
            }
        }

        this.responseData = input.readByte();
    }
}
