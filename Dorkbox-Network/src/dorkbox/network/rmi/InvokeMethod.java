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
	// The top two bytes of the ID indicate if the remote invocation should respond with return values and exceptions,
    // respectively. The rest is a six bit counter. This means up to 63 responses can be stored before undefined behavior
    // occurs due to possible duplicate IDs.
	public byte responseID;

	@Override
    @SuppressWarnings("rawtypes")
    public void write (Kryo kryo, Output output) {
		output.writeInt(objectID, true);

		int methodClassID = kryo.getRegistration(method.getDeclaringClass()).getId();
		output.writeInt(methodClassID, true);

		CachedMethod[] cachedMethods = RmiBridge.getMethods(kryo, method.getDeclaringClass());
		CachedMethod cachedMethod = null;
		for (int i = 0, n = cachedMethods.length; i < n; i++) {
			cachedMethod = cachedMethods[i];
			if (cachedMethod.method.equals(method)) {
				output.writeByte(i);
				break;
			}
		}

		if (cachedMethod == null) {
		    throw new KryoException("Cached method was null for class: " + method.getDeclaringClass().getName());
		}

		for (int i = 0, n = cachedMethod.serializers.length; i < n; i++) {
			Serializer serializer = cachedMethod.serializers[i];
			if (serializer != null)
				kryo.writeObjectOrNull(output, args[i], serializer);
			else
				kryo.writeClassAndObject(output, args[i]);
		}

		output.writeByte(responseID);
	}

	@Override
    public void read (Kryo kryo, Input input) {
		objectID = input.readInt(true);

		int methodClassID = input.readInt(true);
		Class<?> methodClass = kryo.getRegistration(methodClassID).getType();
		byte methodIndex = input.readByte();

		CachedMethod cachedMethod;
		try {
			cachedMethod = RmiBridge.getMethods(kryo, methodClass)[methodIndex];
		} catch (IndexOutOfBoundsException ex) {
			throw new KryoException("Invalid method index " + methodIndex + " for class: " + methodClass.getName());
		}
		method = cachedMethod.method;

		args = new Object[cachedMethod.serializers.length];
		for (int i = 0, n = args.length; i < n; i++) {
			Serializer<?> serializer = cachedMethod.serializers[i];
			if (serializer != null)
				args[i] = kryo.readObjectOrNull(input, method.getParameterTypes()[i], serializer);
			else
				args[i] = kryo.readClassAndObject(input);
		}

		responseID = input.readByte();
	}
}