package dorkbox.network.rmi;

import java.lang.reflect.Method;

import com.esotericsoftware.kryo.Serializer;

class CachedMethod {
    Method method;

    @SuppressWarnings("rawtypes")
    Serializer[] serializers;
}
