package dorkbox.network.rmi;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import com.esotericsoftware.kryo.Serializer;

class CachedMethod {
    Method method;
    int methodClassID;
    int methodIndex;

    @SuppressWarnings("rawtypes")
    Serializer[] serializers;

    public Object invoke(Object target, Object[] args) throws IllegalAccessException, InvocationTargetException {
        return this.method.invoke(target, args);
    }
}
