/*
 * Copyright 2010 dorkbox, llc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dorkbox.network.connection;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.util.Util;
import com.esotericsoftware.reflectasm.MethodAccess;
import dorkbox.network.pipeline.ByteBufInput;
import dorkbox.network.pipeline.ByteBufOutput;
import dorkbox.network.rmi.AsmCachedMethod;
import dorkbox.network.rmi.CachedMethod;
import dorkbox.util.crypto.bouncycastle.GCMBlockCipher_ByteBuf;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.compression.SnappyAccess;
import org.bouncycastle.crypto.engines.AESFastEngine;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

public
class KryoExtra extends Kryo {
    final ByteBufOutput output;
    final ByteBufInput input;

    final Inflater inflater;
    final Deflater deflater;
    final SnappyAccess snappy;

    final ByteBuf tmpBuffer1;
    final ByteBuf tmpBuffer2;
    final GCMBlockCipher_ByteBuf aesEngine;

    private final Map<Class<?>, CachedMethod[]> methodCache = new ConcurrentHashMap<Class<?>, CachedMethod[]>();
    private final boolean asmEnabled = !KryoCryptoSerializationManager.useUnsafeMemory;

    // not thread safe
    public ConnectionImpl connection;


    public
    KryoExtra() {
        this.snappy = new SnappyAccess();
        this.deflater = new Deflater(KryoCryptoSerializationManager.compressionLevel, true);
        this.inflater = new Inflater(true);

        this.input = new ByteBufInput();
        this.output = new ByteBufOutput();

        this.tmpBuffer1 = Unpooled.buffer(1024);
        this.tmpBuffer2 = Unpooled.buffer(1024);
        this.aesEngine = new GCMBlockCipher_ByteBuf(new AESFastEngine());
    }

    public
    CachedMethod[] getMethods(Class<?> type) {
        CachedMethod[] cachedMethods = this.methodCache.get(type); // Maybe should cache per Kryo instance?
        if (cachedMethods != null) {
            return cachedMethods;
        }

        ArrayList<Method> allMethods = new ArrayList<Method>();

        Class<?> nextClass = type;
        while (nextClass != null) {
            Collections.addAll(allMethods, nextClass.getDeclaredMethods());
            nextClass = nextClass.getSuperclass();
            if (nextClass == Object.class) {
                break;
            }
        }

        ArrayList<Method> methods = new ArrayList<Method>(Math.max(1, allMethods.size()));
        for (int i = 0, n = allMethods.size(); i < n; i++) {
            Method method = allMethods.get(i);
            int modifiers = method.getModifiers();
            if (Modifier.isStatic(modifiers)) {
                continue;
            }
            if (Modifier.isPrivate(modifiers)) {
                continue;
            }
            if (method.isSynthetic()) {
                continue;
            }
            methods.add(method);
        }
        Collections.sort(methods, new Comparator<Method>() {
            @Override
            public
            int compare(Method o1, Method o2) {
                // Methods are sorted so they can be represented as an index.
                int diff = o1.getName()
                             .compareTo(o2.getName());
                if (diff != 0) {
                    return diff;
                }
                Class<?>[] argTypes1 = o1.getParameterTypes();
                Class<?>[] argTypes2 = o2.getParameterTypes();
                if (argTypes1.length > argTypes2.length) {
                    return 1;
                }
                if (argTypes1.length < argTypes2.length) {
                    return -1;
                }
                for (int i = 0; i < argTypes1.length; i++) {
                    diff = argTypes1[i].getName()
                                       .compareTo(argTypes2[i].getName());
                    if (diff != 0) {
                        return diff;
                    }
                }
                throw new RuntimeException("Two methods with same signature!"); // Impossible.
            }
        });

        Object methodAccess = null;
        if (asmEnabled && !Util.isAndroid && Modifier.isPublic(type.getModifiers())) {
            methodAccess = MethodAccess.get(type);
        }


        int n = methods.size();
        cachedMethods = new CachedMethod[n];
        for (int i = 0; i < n; i++) {
            Method method = methods.get(i);
            Class<?>[] parameterTypes = method.getParameterTypes();

            CachedMethod cachedMethod = null;
            if (methodAccess != null) {
                try {
                    AsmCachedMethod asmCachedMethod = new AsmCachedMethod();
                    asmCachedMethod.methodAccessIndex = ((MethodAccess) methodAccess).getIndex(method.getName(), parameterTypes);
                    asmCachedMethod.methodAccess = (MethodAccess) methodAccess;
                    cachedMethod = asmCachedMethod;
                } catch (RuntimeException ignored) {
                }
            }

            if (cachedMethod == null) {
                cachedMethod = new CachedMethod();
            }
            cachedMethod.method = method;
            cachedMethod.methodClassID = getRegistration(method.getDeclaringClass()).getId();
            cachedMethod.methodIndex = i;

            // Store the serializer for each final parameter.
            cachedMethod.serializers = new Serializer<?>[parameterTypes.length];
            for (int ii = 0, nn = parameterTypes.length; ii < nn; ii++) {
                if (isFinal(parameterTypes[ii])) {
                    cachedMethod.serializers[ii] = getSerializer(parameterTypes[ii]);
                }
            }

            cachedMethods[i] = cachedMethod;
        }

        this.methodCache.put(type, cachedMethods);
        return cachedMethods;
    }
}
