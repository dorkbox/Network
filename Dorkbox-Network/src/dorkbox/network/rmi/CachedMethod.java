package dorkbox.network.rmi;

import com.esotericsoftware.kryo.Serializer;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public
class CachedMethod {
    public Method method;
    public int methodClassID;
    public int methodIndex;

    @SuppressWarnings("rawtypes")
    public Serializer[] serializers;

    public Object invoke(Object target, Object[] args) throws IllegalAccessException, InvocationTargetException {
        return this.method.invoke(target, args);
    }
}
