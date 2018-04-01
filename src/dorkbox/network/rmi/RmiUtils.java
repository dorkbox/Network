package dorkbox.network.rmi;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;

import org.slf4j.Logger;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.reflectasm.MethodAccess;

import dorkbox.network.connection.Connection;
import dorkbox.util.generics.ClassHelper;

/**
 * Utility methods for creating a method cache for a class or interface.
 *
 * Additionally, this will override methods on the implementation so that methods can be called with a {@link Connection} parameter as the
 * first parameter, with all other parameters being equal to the interface.
 *
 * This is to support calling RMI methods from an interface (that does pass the connection reference) to
 * an implType, that DOES pass the connection reference. The remote side (that initiates the RMI calls), MUST use
 * the interface, and the implType may override the method, so that we add the connection as the first in
 * the list of parameters.
 *
 * for example:
 * Interface: foo(String x)
 *
 *      Impl: foo(String x) -> not called
 *      Impl: foo(Connection c, String x) -> this is called instead
 *
 * The implType (if it exists, with the same name, and with the same signature + connection parameter) will be called from the interface
 * instead of the method that would NORMALLY be called.
 */
public
class RmiUtils {
    private static final Comparator<Method> METHOD_COMPARATOR = new Comparator<Method>() {
        @Override
        public
        int compare(Method o1, Method o2) {
            // Methods are sorted so they can be represented as an index.
            String o1Name = o1.getName();
            String o2Name = o2.getName();


            int diff = o1Name.compareTo(o2Name);
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

            // Impossible, should never happen
            // return 0;
            throw new RuntimeException("Two methods with same signature! ('" + o1Name + "', '" + o2Name + "'");
        }
    };

    private static
    MethodAccess getReflectAsmMethod(final Logger logger, final Class<?> clazz) {
        try {
            MethodAccess methodAccess = MethodAccess.get(clazz);
            if (methodAccess.getMethodNames().length == 0 &&
                methodAccess.getParameterTypes().length == 0 &&
                methodAccess.getReturnTypes().length == 0) {

                // there was NOTHING that reflectASM found, so trying to use it doesn't do us any good
                return null;
            }

            return methodAccess;
        } catch (Exception e) {
            logger.error("Unable to create ReflectASM method access", e);
            return null;
        }
    }


    /**
     * @param logger
     * @param kryo
     * @param asmEnabled
     * @param iFace this is never null.
     * @param impl this is NULL on the rmi "client" side. This is NOT NULL on the "server" side (where the object lives)
     * @param classId
     * @return
     */
    public static
    CachedMethod[] getCachedMethods(final Logger logger, final Kryo kryo, final boolean asmEnabled, final Class<?> iFace, final Class<?> impl, final int classId) {
        MethodAccess ifaceMethodAccess = null;
        MethodAccess implMethodAccess = null;

        // RMI is **ALWAYS** based upon an interface, so we must always make sure to get the methods of the interface, instead of the
        // implementation, otherwise we will have the wrong order of methods, so invoking a method by it's index will fail.

        final Method[] methods = getMethods(iFace);
        final int size = methods.length;
        final CachedMethod[] cachedMethods = new CachedMethod[size];

        if (impl != null) {
            if (impl.isInterface()) {
                throw new IllegalArgumentException("Cannot have type as an interface, it must be an implementation");
            }

            final Method[] implMethods = getMethods(impl);
            overwriteMethodsWithConnectionParam(implMethods, methods);


            // reflectASM
            //   doesn't work on android (set correctly by the serialization manager)
            //   can't get any method from the 'Object' object (we get from the interface, which is NOT 'Object')
            //   and it MUST be public (iFace is always public)
            if (asmEnabled) {
                implMethodAccess = getReflectAsmMethod(logger, impl);
            }
        }

        // reflectASM
        //   doesn't work on android (set correctly by the serialization manager)
        //   can't get any method from the 'Object' object (we get from the interface, which is NOT 'Object')
        //   and it MUST be public (iFace is always public)
        if (asmEnabled) {
            ifaceMethodAccess = getReflectAsmMethod(logger, iFace);
        }

        for (int i = 0; i < size; i++) {
            final Method method = methods[i];

            Class<?> declaringClass = method.getDeclaringClass();
            Class<?>[] parameterTypes = method.getParameterTypes();

            // copy because they can be overridden
            boolean overriddenMethod = false;
            MethodAccess tweakMethodAccess = ifaceMethodAccess;


            // this is how we detect if the method has been changed from the interface -> implementation + connection parameter
            if (declaringClass.equals(impl)) {
                tweakMethodAccess = implMethodAccess;
                overriddenMethod = true;

                if (logger.isTraceEnabled()) {
                    logger.trace("Overridden method: {}.{}", impl, method.getName());
                }
            }


            CachedMethod cachedMethod = null;
            // reflectAsm doesn't like "Object" class methods...
            if (tweakMethodAccess != null && method.getDeclaringClass() != Object.class) {
                try {
                    final int index = tweakMethodAccess.getIndex(method.getName(), parameterTypes);

                    AsmCachedMethod asmCachedMethod = new AsmCachedMethod();
                    asmCachedMethod.methodAccessIndex = index;
                    asmCachedMethod.methodAccess = tweakMethodAccess;
                    asmCachedMethod.name = method.getName();

                    if (overriddenMethod) {
                        // logger.error(tweakMethod.getName() + " " + Arrays.toString(parameterTypes) + " index: " + index +
                        //             " methodIndex: " + i + " classID: " + classId);

                        // This is because we have to store the serializer for each parameter, but ONLY for the ORIGINAL method, not the overridden one.
                        // this gets our parameters "back to the original" method. We do this to minimize the overhead of sending the args over
                        int length = parameterTypes.length;
                        if (length == 1) {
                            parameterTypes = new Class<?>[0];
                        }
                        else {
                            length--;
                            Class<?>[] newArgs = new Class<?>[length];
                            System.arraycopy(parameterTypes, 1, newArgs, 0, length);
                            parameterTypes = newArgs;
                        }
                    }

                    cachedMethod = asmCachedMethod;
                } catch (Exception e) {
                    logger.trace("Unable to use ReflectAsm for {}.{} (using java reflection instead)", declaringClass, method.getName(), e);
                }
            }

            if (cachedMethod == null) {
                cachedMethod = new CachedMethod();
            }

            cachedMethod.overriddenMethod = overriddenMethod;
            cachedMethod.methodClassID = classId;

            // we ALSO have to setup "normal" reflection access to these methods
            cachedMethod.method = method;
            cachedMethod.methodIndex = i;

            // Store the serializer for each final parameter.
            // ONLY for the ORIGINAL method, not the overridden one.
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

    // NOTE: does not null check
    /**
     * This will overwrite an original (iface based) method with a method from the implementation ONLY if there is the extra 'Connection' parameter (as per above)
     *
     * @param implMethods methods from the implementation
     * @param origMethods methods from the interface
     */
    private static
    void overwriteMethodsWithConnectionParam(final Method[] implMethods, final Method[] origMethods) {
        for (int i = 0, origMethodsSize = origMethods.length; i < origMethodsSize; i++) {
            final Method origMethod = origMethods[i];

            String origName = origMethod.getName();
            Class<?>[] origTypes = origMethod.getParameterTypes();
            int origLength = origTypes.length + 1;

            for (Method implMethod : implMethods) {
                String implName = implMethod.getName();
                Class<?>[] implTypes = implMethod.getParameterTypes();
                int implLength = implTypes.length;

                if (origLength != implLength || !(origName.equals(implName))) {
                    continue;
                }

                // checkLength > 0
                Class<?> shouldBeConnectionType = implTypes[0];
                if (ClassHelper.hasInterface(Connection.class, shouldBeConnectionType)) {
                    // now we check to see if our "check" method is equal to our "cached" method + Connection
                    if (implLength == 1) {
                        // we only have "Connection" as a parameter
                        origMethods[i] = implMethod;
                        break;
                    }
                    else {
                        boolean found = true;
                        for (int k = 1; k < implLength; k++) {
                            if (origTypes[k - 1] != implTypes[k]) {
                                // make sure all the parameters match. Cannot use arrays.equals(*), because one will have "Connection" as
                                // a parameter - so we check that the rest match
                                found = false;
                                break;
                            }
                        }

                        if (found) {
                            origMethods[i] = implMethod;
                            break;
                        }
                    }
                }
            }
        }
    }

    /**
     * This will methods from an interface (for RMI), and from an implementation (for "connection" overriding the method signature).
     *
     * @return an array list of all found methods for this class
     */
    private static
    Method[] getMethods(final Class<?> type) {
        final ArrayList<Method> allMethods = new ArrayList<Method>();
        final Map<String, ArrayList<Class[]>> accessibleMethods = new HashMap<String, ArrayList<Class[]>>();

        LinkedList<Class<?>> classes = new LinkedList<Class<?>>();
        classes.add(type);

        // explicitly add Object.class because that can always be called, because it is common to 100% of all java objects (and it's methods
        // are not added via parentClass.getMethods()
        classes.add(Object.class);

        Class<?> nextClass;
        while (!classes.isEmpty()) {
            nextClass = classes.removeFirst();

            Method[] methods = nextClass.getMethods();
            for (int i = 0; i < methods.length; i++) {
                final Method method = methods[i];

                // static and private methods cannot be called via RMI.
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

                // methods that have been over-ridden by another method cannot be called.
                // the first one in the map, is the "highest" level method, and is what can be called.
                String name = method.getName();
                Class<?>[] types = method.getParameterTypes();  // length 0 if there are no parameters

                ArrayList<Class[]> existingTypes = accessibleMethods.get(name);
                if (existingTypes != null) {
                    boolean found = false;
                    for (Class[] existingType : existingTypes) {
                        if (Arrays.equals(types, existingType)) {
                            found = true;
                            break;
                        }
                    }

                    if (found) {
                        // the method is overridden, so it should not be called.
                        continue;
                    }
                }

                if (existingTypes == null) {
                    existingTypes = new ArrayList<Class[]>();
                }
                existingTypes.add(types);

                // add to the map for checking later
                accessibleMethods.put(name, existingTypes);

                // safe to add this method to the list of recognized methods
                allMethods.add(method);
            }

            // add all interfaces from our class (if any)
            classes.addAll(Arrays.asList(nextClass.getInterfaces()));

            // If we are an interface, one CANNOT call any methods NOT defined by the interface!
            // also, interfaces don't have a super-class.
            Class<?> superclass = nextClass.getSuperclass();
            if (superclass != null) {
                classes.add(superclass);
            }
        }

        accessibleMethods.clear();

        Collections.sort(allMethods, METHOD_COMPARATOR);
        Method[] methodsArray = new Method[allMethods.size()];

        allMethods.toArray(methodsArray);

        return methodsArray;
    }
}
