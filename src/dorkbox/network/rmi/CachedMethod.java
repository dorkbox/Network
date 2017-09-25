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
 *
 * Copyright (c) 2008, Nathan Sweet
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following
 * conditions are met:
 *
 * - Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
 * - Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following
 * disclaimer in the documentation and/or other materials provided with the distribution.
 * - Neither the name of Esoteric Software nor the names of its contributors may be used to endorse or promote products derived
 * from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING,
 * BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT
 * SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package dorkbox.network.rmi;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.util.IdentityMap;
import com.esotericsoftware.kryo.util.Util;
import com.esotericsoftware.reflectasm.MethodAccess;

import dorkbox.network.connection.Connection;
import dorkbox.network.connection.EndPoint;
import dorkbox.network.connection.KryoExtra;
import dorkbox.network.util.CryptoSerializationManager;
import dorkbox.network.util.RmiSerializationManager;
import dorkbox.util.ClassHelper;

public
class CachedMethod {
    private static final Logger logger = LoggerFactory.getLogger(CachedMethod.class);

    private static final Comparator<Method> METHOD_COMPARATOR = new Comparator<Method>() {
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
            throw new RuntimeException("Two methods with same signature!"); // Impossible, should never happen
        }
    };

    // the purpose of the method cache, is to accelerate looking up methods for specific class
    private static final Map<Class<?>, CachedMethod[]> methodCache = new ConcurrentHashMap<Class<?>, CachedMethod[]>(EndPoint.DEFAULT_THREAD_POOL_SIZE);


    /**
     * Called when we read a RMI method invocation on the "server" side (by kryo)
     *
     * @param type is the implementation type
     */
    static
    CachedMethod[] getMethods(final Kryo kryo, final Class<?> type, final int classId) {
        CachedMethod[] cachedMethods = methodCache.get(type);
        if (cachedMethods != null) {
            return cachedMethods;
        }

        cachedMethods = getCachedMethods(kryo, type, classId);
        methodCache.put(type, cachedMethods);

        return cachedMethods;
    }


    /**
     * Called when we write an RMI method invocation on the "client" side (by RmiProxyHandler)
     *
     * @param type this is the interface.
     */
    static
    CachedMethod[] getMethods(final RmiSerializationManager serializationManager, final Class<?> type, final int classId) {
        CachedMethod[] cachedMethods = methodCache.get(type);
        if (cachedMethods != null) {
            return cachedMethods;
        }

        final KryoExtra kryo = serializationManager.takeKryo();
        try {
            cachedMethods = getCachedMethods(kryo, type, classId);
            methodCache.put(type, cachedMethods);
        } finally {
            serializationManager.returnKryo(kryo);
        }

        return cachedMethods;
    }

    // race-conditions are OK, because we just recreate the same thing.
    private static
    CachedMethod[] getCachedMethods(final Kryo kryo, final Class<?> type, final int classId) {
        // sometimes, the method index is based upon an interface and NOT the implementation. We have to clear that up here.
        CryptoSerializationManager serialization = ((KryoExtra) kryo).getSerializationManager();

        // when there is an interface available, we want to use that instead of the implementation. This is because the incoming
        // implementation is ACTUALLY mapped (on the "client" side) to the interface. If we don't use the interface, we will have the
        // wrong order of methods, so invoking a method by it's index will fail.
        Class<?> interfaceClass = serialization.getRmiIface(classId);

        final ArrayList<Method> methods;

        if (interfaceClass == null) {
            methods = getMethods(type);
        } else {
            methods = getMethods(interfaceClass);
        }

        final int size = methods.size();
        final CachedMethod[] cachedMethods = new CachedMethod[size];

        // In situations where we want to pass in the Connection (to an RMI method), we have to be able to override method A, with method B.
        // This is to support calling RMI methods from an interface (that does pass the connection reference) to
        // an implType, that DOES pass the connection reference. The remote side (that initiates the RMI calls), MUST use
        // the interface, and the implType may override the method, so that we add the connection as the first in
        // the list of parameters.
        //
        // for example:
        // Interface: foo(String x)
        //      Impl: foo(Connection c, String x)
        //
        // The implType (if it exists, with the same name, and with the same signature+connection) will be called from the interface.
        // This MUST hold valid for both remote and local connection types.

        // To facilitate this functionality, for methods with the same name, the "overriding" method is the one that inherits the Connection
        // interface as the first parameter, and  .registerRemote(ifaceClass, implClass)  must be called.

        // will only be valid if implementation type is NOT NULL (otherwise will be null)
        final IdentityMap<Method, Method> overriddenMethods;
        if (interfaceClass == null) {
            overriddenMethods = null;
        } else {
            // type here must be the implementation
            overriddenMethods = getOverriddenMethods(type, methods);
        }

        final boolean asmEnabled = kryo.getFieldSerializerConfig().isUseAsm();

        MethodAccess methodAccess = null;
        // reflectASM can't get any method from the 'Object' object, and it MUST be public
        if (asmEnabled && type != Object.class && !Util.isAndroid && Modifier.isPublic(type.getModifiers())) {
            methodAccess = MethodAccess.get(type);
            if (methodAccess.getMethodNames().length == 0 && methodAccess.getParameterTypes().length == 0 &&
                methodAccess.getReturnTypes().length == 0) {

                // there was NOTHING reflectASM found, so trying to use it doesn't do us any good
                methodAccess = null;
            }
        }

        for (int i = 0; i < size; i++) {
            final Method origMethod = methods.get(i);
            Method method = origMethod; // copy because one or more can be overridden
            Class<?> declaringClass = method.getDeclaringClass();
            MethodAccess localMethodAccess = methodAccess; // copy because one or more can be overridden
            Class<?>[] parameterTypes = method.getParameterTypes();
            Class<?>[] asmParameterTypes = parameterTypes;

            if (overriddenMethods != null) {
                Method overriddenMethod = overriddenMethods.remove(method);
                if (overriddenMethod != null) {
                    // we can override the details of this method BECAUSE (and only because) our kryo registration override will return
                    // the correct object for this overridden method to be called on.
                    method = overriddenMethod;

                    Class<?> overrideType = declaringClass;
                    if (asmEnabled && !Util.isAndroid && Modifier.isPublic(overrideType.getModifiers())) {
                        localMethodAccess = MethodAccess.get(overrideType);
                        asmParameterTypes = method.getParameterTypes();
                    }
                }
            }

            CachedMethod cachedMethod = null;
            if (localMethodAccess != null) {
                try {
                    final int index = localMethodAccess.getIndex(method.getName(), asmParameterTypes);

                    AsmCachedMethod asmCachedMethod = new AsmCachedMethod();
                    asmCachedMethod.methodAccessIndex = index;
                    asmCachedMethod.methodAccess = localMethodAccess;
                    cachedMethod = asmCachedMethod;
                } catch (Exception e) {
                    if (logger.isTraceEnabled()) {
                        logger.trace("Unable to use ReflectAsm for {}.{}", declaringClass, method.getName(), e);
                    }
                }
            }

            if (cachedMethod == null) {
                cachedMethod = new CachedMethod();
            }
            cachedMethod.method = method;
            cachedMethod.origMethod = origMethod;


            // on the "server", we only register the implementation. NOT THE INTERFACE, so for RMI classes, we have to get the impl
            Class<?> impl = serialization.getRmiImpl(declaringClass);
            if (impl != null) {
                cachedMethod.methodClassID = kryo.getRegistration(impl)
                                                 .getId();
            }
            else {
                cachedMethod.methodClassID = kryo.getRegistration(declaringClass)
                                                 .getId();
            }
            cachedMethod.methodIndex = i;

            // Store the serializer for each final parameter.
            // ONLY for the ORIGINAL method, not he overridden one.
            cachedMethod.serializers = new Serializer<?>[parameterTypes.length];
            for (int ii = 0, nn = parameterTypes.length; ii < nn; ii++) {
                if (kryo.isFinal(parameterTypes[ii])) {
                    cachedMethod.serializers[ii] = kryo.getSerializer(parameterTypes[ii]);
                }
            }

            cachedMethods[i] = cachedMethod;
        }

        return cachedMethods;
    }

    // does not null check
    private static
    IdentityMap<Method, Method> getOverriddenMethods(final Class<?> type, final ArrayList<Method> origMethods) {
        final ArrayList<Method> implMethods = getMethods(type);
        final IdentityMap<Method, Method> overrideMap = new IdentityMap<Method, Method>(implMethods.size());

        for (Method origMethod : origMethods) {
            String name = origMethod.getName();
            Class<?>[] origTypes = origMethod.getParameterTypes();
            int origLength = origTypes.length + 1;

            METHOD_CHECK:
            for (Method implMethod : implMethods) {
                String checkName = implMethod.getName();
                Class<?>[] checkTypes = implMethod.getParameterTypes();
                int checkLength = checkTypes.length;

                if (origLength != checkLength || !(name.equals(checkName))) {
                    continue;
                }

                // checkLength > 0
                Class<?> shouldBeConnectionType = checkTypes[0];
                if (ClassHelper.hasInterface(dorkbox.network.connection.Connection.class, shouldBeConnectionType)) {
                    // now we check to see if our "check" method is equal to our "cached" method + Connection
                    if (checkLength == 1) {
                        overrideMap.put(origMethod, implMethod);
                        break;
                    }
                    else {
                        for (int k = 1; k < checkLength; k++) {
                            if (origTypes[k - 1] == checkTypes[k]) {
                                overrideMap.put(origMethod, implMethod);
                                break METHOD_CHECK;
                            }
                        }
                    }
                }
            }
        }

        return overrideMap;
    }

    private static
    ArrayList<Method> getMethods(final Class<?> type) {
        final ArrayList<Method> allMethods = new ArrayList<Method>();

        Class<?> nextClass = type;
        while (nextClass != null) {
            Collections.addAll(allMethods, nextClass.getDeclaredMethods());
            nextClass = nextClass.getSuperclass();
            if (nextClass == Object.class) {
                break;
            }
        }

        final ArrayList<Method> methods = new ArrayList<Method>(Math.max(1, allMethods.size()));
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

        Collections.sort(methods, METHOD_COMPARATOR);
        return methods;
    }


    public Method method;
    public int methodClassID;
    public int methodIndex;

    /**
     * in some cases, we want to override the cached method, with one that supports passing 'Connection' as the first argument. This is
     * completely OPTIONAL, however - greatly adds functionality to RMI methods.
     */
    public transient Method origMethod;

    @SuppressWarnings("rawtypes")
    public Serializer[] serializers;

    public
    Object invoke(final Connection connection, Object target, Object[] args) throws IllegalAccessException, InvocationTargetException {
        // did we override our cached method?
        if (method == origMethod) {
            return this.method.invoke(target, args);
        }
        else {
            int length = args.length;
            Object[] newArgs = new Object[length + 1];
            newArgs[0] = connection;
            System.arraycopy(args, 0, newArgs, 1, length);

            return this.method.invoke(target, newArgs);
        }
    }
}
