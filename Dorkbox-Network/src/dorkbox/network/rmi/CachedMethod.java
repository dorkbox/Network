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

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.util.Util;
import com.esotericsoftware.reflectasm.MethodAccess;
import dorkbox.network.connection.Connection;
import dorkbox.network.connection.EndPoint;
import dorkbox.util.ClassHelper;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public
class CachedMethod {
    // not concurrent because they are setup during system initialization
    public static final Map<Class<?>, Class<?>> overriddenMethods = new HashMap<Class<?>, Class<?>>();
    public static final Map<Class<?>, Class<?>> overriddenReverseMethods = new HashMap<Class<?>, Class<?>>();

    private static final Map<Class<?>, CachedMethod[]> methodCache = new ConcurrentHashMap<Class<?>, CachedMethod[]>(EndPoint.DEFAULT_THREAD_POOL_SIZE);

    // type will be likely be the interface
    public static
    CachedMethod[] getMethods(final Kryo kryo, final Class<?> type) {
        CachedMethod[] cachedMethods = methodCache.get(type);
        if (cachedMethods != null) {
            return cachedMethods;
        }

        // race-conditions are OK, because we just recreate the same thing.
        ArrayList<Method> methods = getMethods(type);

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
        // interface as the first parameter.
        Map<Method, Method> overriddenMethods = getOverriddenMethods(type, methods);


        MethodAccess methodAccess = null;
        if (kryo.getAsmEnabled() && !Util.isAndroid && Modifier.isPublic(type.getModifiers())) {
            methodAccess = MethodAccess.get(type);
        }

        int n = methods.size();
        cachedMethods = new CachedMethod[n];
        for (int i = 0; i < n; i++) {
            Method origMethod = methods.get(i);
            Method method = origMethod;
            MethodAccess localMethodAccess = methodAccess;
            Class<?>[] parameterTypes = method.getParameterTypes();
            Class<?>[] asmParameterTypes = parameterTypes;

            Method overriddenMethod = overriddenMethods.remove(method);
            boolean overridden = overriddenMethod != null;
            if (overridden) {
                // we can override the details of this method BECAUSE (and only because) our kryo registration override will return
                // the correct object for this overridden method to be called on.
                method = overriddenMethod;

                Class<?> overrideType = method.getDeclaringClass();

                if (kryo.getAsmEnabled() && !Util.isAndroid && Modifier.isPublic(overrideType.getModifiers())) {
                    localMethodAccess = MethodAccess.get(overrideType);
                    asmParameterTypes = method.getParameterTypes();
                }
            }

            CachedMethod cachedMethod = null;
            if (localMethodAccess != null) {
                try {
                    AsmCachedMethod asmCachedMethod = new AsmCachedMethod();
                    asmCachedMethod.methodAccessIndex = localMethodAccess.getIndex(method.getName(), asmParameterTypes);
                    asmCachedMethod.methodAccess = localMethodAccess;
                    cachedMethod = asmCachedMethod;
                } catch (RuntimeException ignored) {
                    ignored.printStackTrace();
                }
            }

            if (cachedMethod == null) {
                cachedMethod = new CachedMethod();
            }
            cachedMethod.method = method;
            cachedMethod.origMethod = origMethod;
            cachedMethod.methodClassID = kryo.getRegistration(method.getDeclaringClass())
                                             .getId();
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

        methodCache.put(type, cachedMethods);
        return cachedMethods;
    }

    private static
    Map<Method, Method> getOverriddenMethods(final Class<?> type, final ArrayList<Method> origMethods) {
        final Class<?> implType = overriddenMethods.get(type);

        if (implType != null) {
            ArrayList<Method> implMethods = getMethods(implType);

            HashMap<Method, Method> overrideMap = new HashMap<Method, Method>(implMethods.size());

            for (Method origMethod : origMethods) {
                String name = origMethod.getName();
                Class<?>[] types = origMethod.getParameterTypes();
                int modLength = types.length + 1;

                METHOD_CHECK:
                for (Method implMethod : implMethods) {
                    String checkName = implMethod.getName();
                    Class<?>[] checkTypes = implMethod.getParameterTypes();
                    int checkLength = checkTypes.length;

                    if (modLength != checkLength || !(name.equals(checkName))) {
                        continue;
                    }

                    // checkLength > 0
                    Class<?> checkType = checkTypes[0];
                    if (ClassHelper.hasInterface(dorkbox.network.connection.Connection.class, checkType)) {
                        // now we check to see if our "check" method is equal to our "cached" method + Connection
                        for (int k = 1; k < checkLength; k++) {
                            if (types[k - 1] == checkTypes[k]) {
                                overrideMap.put(origMethod, implMethod);
                                break METHOD_CHECK;
                            }
                        }
                    }
                }
            }

            return overrideMap;
        }
        else {
            return new HashMap<Method, Method>(0);
        }
    }



    private static
    ArrayList<Method> getMethods(final Class<?> type) {
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
        return methods;
    }

    /**
     * Called by the SerializationManager, so that RMI classes that are overridden for serialization purposes, can check to see if certain
     * methods need to be overridden.
     */
    public static
    void registerOverridden(final Class<?> ifaceClass, final Class<?> implClass) {
        overriddenMethods.put(ifaceClass, implClass);
        overriddenReverseMethods.put(implClass, ifaceClass);
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
        if (origMethod == null) {
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
