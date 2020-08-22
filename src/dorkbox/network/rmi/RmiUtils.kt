/*
 * Copyright 2020 dorkbox, llc
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
package dorkbox.network.rmi

import com.esotericsoftware.kryo.Kryo
import com.esotericsoftware.kryo.Serializer
import com.esotericsoftware.reflectasm.MethodAccess
import dorkbox.network.connection.Connection
import dorkbox.util.classes.ClassHelper
import mu.KLogger
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.*
import kotlin.coroutines.Continuation

/**
 * Utility methods for creating a method cache for a class or interface.
 *
 * Additionally, this will override methods on the implementation so that methods can be called with a [Connection] parameter as the
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
 * Impl: foo(String x) -> not called
 * Impl: foo(Connection c, String x) -> this is called instead
 *
 * The implType (if it exists, with the same name, and with the same signature + connection parameter) will be called from the interface
 * instead of the method that would NORMALLY be called.
 */
object RmiUtils {
    private val METHOD_COMPARATOR = Comparator<Method> { o1, o2 -> // Methods are sorted so they can be represented as an index.
        val o1Name = o1.name
        val o2Name = o2.name

        var diff = o1Name.compareTo(o2Name)
        if (diff != 0) {
            return@Comparator diff
        }

        val argTypes1 = o1.parameterTypes
        val argTypes2 = o2.parameterTypes
        if (argTypes1.size > argTypes2.size) {
            return@Comparator 1
        }
        if (argTypes1.size < argTypes2.size) {
            return@Comparator -1
        }

        for (i in argTypes1.indices) {
            diff = argTypes1[i].name.compareTo(argTypes2[i].name)
            if (diff != 0) {
                return@Comparator diff
            }
        }

        throw RuntimeException("Two methods with same signature! ('$o1Name', '$o2Name'")
    }

    private fun getReflectAsmMethod(logger: KLogger, clazz: Class<*>): MethodAccess? {
        return try {
            val methodAccess = MethodAccess.get(clazz)

            if (methodAccess.methodNames.isEmpty() && methodAccess.parameterTypes.isEmpty() && methodAccess.returnTypes.isEmpty()) {
                // there was NOTHING that reflectASM found, so trying to use it doesn't do us any good
                null
            } else {
                methodAccess
            }
        } catch (e: Exception) {
            logger.error("Unable to create ReflectASM method access", e)
            null
        }
    }

    /**
     * @param iFace this is never null.
     * @param impl this is NULL on the rmi "client" side. This is NOT NULL on the "server" side (where the object lives)
     */
    fun getCachedMethods(logger: KLogger, kryo: Kryo, asmEnabled: Boolean, iFace: Class<*>, impl: Class<*>?, classId: Int): Array<CachedMethod> {
        var ifaceAsmMethodAccess: MethodAccess? = null
        var implAsmMethodAccess: MethodAccess? = null

        // RMI is **ALWAYS** based upon an interface, so we must always make sure to get the methods of the interface, instead of the
        // implementation, otherwise we will have the wrong order of methods, so invoking a method by it's index will fail.
        val methods = getMethods(iFace)
        val size = methods.size
        val cachedMethods = arrayOfNulls<CachedMethod>(size)

        val implMethods: Array<Method>?
        if (impl != null) {
            require(!impl.isInterface) { "Cannot have type as an interface, it must be an implementation" }
            implMethods = getMethods(impl)

            // reflectASM
            //   doesn't work on android (set correctly by the serialization manager)
            //   can't get any method from the 'Object' object (we get from the interface, which is NOT 'Object')
            //   and it MUST be public (iFace is always public)
            if (asmEnabled) {
                implAsmMethodAccess = getReflectAsmMethod(logger, impl)
            }
        } else {
            implMethods = null
        }

        // reflectASM
        //   doesn't work on android (set correctly by the serialization manager)
        //   can't get any method from the 'Object' object (we get from the interface, which is NOT 'Object')
        //   and it MUST be public (iFace is always public)
        if (asmEnabled) {
            ifaceAsmMethodAccess = getReflectAsmMethod(logger, iFace)
        }
        
        val hasConnectionOverrideMethods =  hasOverwriteMethodWithConnectionParam(implMethods)


        for (i in 0 until size) {
            val method = methods[i]
            val declaringClass = method.declaringClass
            val parameterTypes = method.parameterTypes

            // Store the serializer for each final parameter.
            // this is ONLY for the ORIGINAL method, not the overridden one.
            val serializers = arrayOfNulls<Serializer<*>>(parameterTypes.size)

            parameterTypes.forEachIndexed { index, parameterType ->
                val paramClazz = parameterTypes[index]
                if (kryo.isFinal(paramClazz) || paramClazz === Continuation::class.java) {
                    serializers[index] = kryo.getSerializer(parameterType)
                }
            }

            // copy because they can be overridden
            var cachedMethod: CachedMethod? = null

            @Suppress("LocalVariableName")
            var iface_OR_ImplMethodAccess = ifaceAsmMethodAccess

            // reflectAsm doesn't like "Object" class methods
            val canUseAsm = asmEnabled && method.declaringClass != Any::class.java
            var overwrittenMethod: Method? = null

            // this is how we detect if the method has been changed from the interface -> implementation + connection parameter
            if (implMethods != null && hasConnectionOverrideMethods) {
                overwrittenMethod = getOverwriteMethodWithConnectionParam(implMethods, method)

                if (overwrittenMethod != null) {
                    logger.trace {
                        "Overridden method: $impl.${method.name}"
                    }

                    // still might be null!
                    iface_OR_ImplMethodAccess = implAsmMethodAccess
                }
            }


            if (canUseAsm) {
                try {
                    val index = if (overwrittenMethod != null) {
                        // have to take into account the overwritten method's first parameter will ALWAYS be "Connection"
                        iface_OR_ImplMethodAccess!!.getIndex(method.name, *overwrittenMethod.parameterTypes)
                    } else {
                        iface_OR_ImplMethodAccess!!.getIndex(method.name, *parameterTypes)
                    }

                    cachedMethod = CachedAsmMethod(
                            methodAccessIndex = index,
                            methodAccess = iface_OR_ImplMethodAccess,
                            name = method.name,
                            method = method,
                            methodIndex = i,
                            methodClassId = classId,
                            serializers = serializers)
                } catch (e: Exception) {
                    logger.trace("Unable to use ReflectAsm for {}.{} (using java reflection instead)", declaringClass, method.name, e)
                }
            }

            if (cachedMethod == null) {
                cachedMethod = CachedMethod(
                        method = method,
                        methodIndex = i,
                        methodClassId = classId,
                        serializers = serializers)
            }

            // this MIGHT be null, but if it is not, this is the method we will invoke INSTEAD of the "normal" method
            cachedMethod.overriddenMethod = overwrittenMethod

            cachedMethods[i] = cachedMethod
        }

        // force the type, because we KNOW it is ok to do so
        @Suppress("UNCHECKED_CAST")
        return cachedMethods as Array<CachedMethod>
    }

    /**
     * Check to see if there are ANY methods in this class that start with a "Connection" parameter.
     */
    private fun hasOverwriteMethodWithConnectionParam(implMethods: Array<Method>?): Boolean {
        if (implMethods == null) {
            return false
        }

        // maybe there is a method that starts with a "Connection" parameter.
        for (implMethod in implMethods) {
            val implParameters = implMethod.parameterTypes

            // check if the FIRST parameter is "Connection"
            if (implParameters.isNotEmpty() && ClassHelper.hasInterface(Connection::class.java, implParameters[0])) {
                return true
            }
        }

        return false
    }

    /**
     * This will overwrite an original (iface based) method with a method from the implementation ONLY if there is the extra 'Connection' parameter (as per above)
     * NOTE: does not null check
     *
     * @param implMethods methods from the implementation
     * @param origMethods methods from the interface
     */
    private fun overwriteMethodsWithConnectionParam(implMethods: Array<Method>, origMethods: Array<Method>) {
        var i = 0
        val origMethodsSize = origMethods.size

        while (i < origMethodsSize) {
            val origMethod = origMethods[i]
            val overwriteMethodsWithConnectionParam = getOverwriteMethodWithConnectionParam(implMethods, origMethod)
            if (overwriteMethodsWithConnectionParam != null) {
                origMethods[i] = overwriteMethodsWithConnectionParam
            }

            i++
        }
    }

    /**
     * This will overwrite an original (iface based) method with a method from the implementation ONLY if there is the extra 'Connection' parameter (as per above)
     * NOTE: does not null check
     *
     * @param implMethods methods from the implementation
     * @param origMethod original method from the interface
     */
    private fun getOverwriteMethodWithConnectionParam(implMethods: Array<Method>, origMethod: Method): Method? {
        val origName = origMethod.name
        val origTypes = origMethod.parameterTypes
        val origLength = origTypes.size + 1

        for (implMethod in implMethods) {
            val implName = implMethod.name
            val implParameters = implMethod.parameterTypes
            val implLength = implParameters.size

            if (origLength != implLength || origName != implName) {
                continue
            }

            // check if the FIRST parameter is "Connection"
            if (ClassHelper.hasInterface(Connection::class.java, implParameters[0])) {
                // now we check to see if our "check" method is equal to our "cached" method + Connection
                if (implLength == 1) {
                    // we only have "Connection" as a parameter
                    return implMethod
                } else {
                    var found = true
                    for (k in 1 until implLength) {
                        if (origTypes[k - 1] != implParameters[k]) {
                            // make sure all the parameters match. Cannot use arrays.equals(*), because one will have "Connection" as
                            // a parameter - so we check that the rest match
                            found = false
                            break
                        }
                    }
                    if (found) {
                        return implMethod
                    }
                }
            }
        }
        return null
    }

    /**
     * This will methods from an interface (for RMI), and from an implementation (for "connection" overriding the method signature).
     *
     * @return an array list of all found methods for this class
     */
    fun getMethods(type: Class<*>): Array<Method> {
        val allMethods = ArrayList<Method>()
        val accessibleMethods: MutableMap<String, ArrayList<Array<Class<*>>>> = HashMap()
        val classes = LinkedList<Class<*>>()
        classes.add(type)

        // explicitly add Object.class because that can always be called, because it is common to 100% of all java objects (and it's methods
        // are not added via parentClass.getMethods()
        classes.add(Any::class.java)

        var nextClass: Class<*>
        while (!classes.isEmpty()) {
            nextClass = classes.removeFirst()
            val methods = nextClass.methods
            for (i in methods.indices) {
                val method = methods[i]

                // static and private methods cannot be called via RMI.
                val modifiers = method.modifiers
                if (Modifier.isStatic(modifiers)) {
                    continue
                }
                if (Modifier.isPrivate(modifiers)) {
                    continue
                }
                if (method.isSynthetic) {
                    continue
                }

                // methods that have been over-ridden by another method cannot be called.
                // the first one in the map, is the "highest" level method, and is what can be called.
                val name = method.name
                val types = method.parameterTypes // length 0 if there are no parameters
                var existingTypes = accessibleMethods[name]

                if (existingTypes != null) {
                    var found = false
                    for (existingType in existingTypes) {
                        if (Arrays.equals(types, existingType)) {
                            found = true
                            break
                        }
                    }
                    if (found) {
                        // the method is overridden, so it should not be called.
                        continue
                    }
                }

                if (existingTypes == null) {
                    existingTypes = ArrayList()
                }
                existingTypes.add(types)

                // add to the map for checking later
                accessibleMethods[name] = existingTypes

                // safe to add this method to the list of recognized methods
                allMethods.add(method)
            }

            // add all interfaces from our class (if any)
            classes.addAll(listOf(*nextClass.interfaces))

            // If we are an interface, one CANNOT call any methods NOT defined by the interface!
            // also, interfaces don't have a super-class.
            val superclass = nextClass.superclass
            if (superclass != null) {
                classes.add(superclass)
            }
        }
        accessibleMethods.clear()
        Collections.sort(allMethods, METHOD_COMPARATOR)

        return allMethods.toTypedArray()
    }

    fun resolveSerializerInstance(k: Kryo, superClass: Class<*>, serializerClass: Class<out Serializer<*>>): Serializer<*> {
        return try {
            try {
                serializerClass.getConstructor(Kryo::class.java, Class::class.java).newInstance(k, superClass)
            } catch (ex1: NoSuchMethodException) {
                try {
                    serializerClass.getConstructor(Kryo::class.java).newInstance(k)
                } catch (ex2: NoSuchMethodException) {
                    try {
                        serializerClass.getConstructor(Class::class.java).newInstance(superClass)
                    } catch (ex3: NoSuchMethodException) {
                        serializerClass.getDeclaredConstructor().newInstance()
                    }
                }
            }
        } catch (ex: Exception) {
            throw IllegalArgumentException(
                    "Unable to create serializer \"" + serializerClass.name + "\" for class: " + superClass.name, ex)
        }
    }

    fun getHierarchy(clazz: Class<*>): ArrayList<Class<*>> {
        val allClasses = ArrayList<Class<*>>()
        val parseClasses = LinkedList<Class<*>>()
        parseClasses.add(clazz)
        var nextClass: Class<*>
        while (!parseClasses.isEmpty()) {
            nextClass = parseClasses.removeFirst()
            allClasses.add(nextClass)

            // add all interfaces from our class (if any)
            parseClasses.addAll(Arrays.asList(*nextClass.interfaces))
            val superclass = nextClass.superclass
            if (superclass != null) {
                parseClasses.add(superclass)
            }
        }

        // remove the first class, because we don't need it
        allClasses.remove(clazz)
        return allClasses
    }

    fun packShorts(left: Int, right: Int): Int {
        return left shl 16 or (right and 0xFFFF)
    }

    fun unpackLeft(packedInt: Int): Int {
        return packedInt shr 16
    }

    fun unpackRight(packedInt: Int): Int {
        return packedInt.toShort().toInt()
    }

    fun unpackUnsignedRight(packedInt: Int): Int {
        return packedInt.toUShort().toInt()
    }

    fun makeFancyMethodName(cachedMethod: CachedMethod): String {
        val parameterTypes = cachedMethod.method.parameterTypes
        val size = parameterTypes.size
        val args: String = if (size == 0 || parameterTypes[size - 1] == Continuation::class.java) {
            ""
        } else {
            parameterTypes.joinToString { it.simpleName }
        }

        return "${cachedMethod.method.declaringClass.name}.${cachedMethod.method.name}($args)"
    }

    fun makeFancyMethodName(method: Method): String {
        val parameterTypes = method.parameterTypes
        val size = parameterTypes.size
        val args: String = if (size != 0 || parameterTypes[size - 1] == Continuation::class.java) {
            ""
        } else {
            parameterTypes.joinToString { it.simpleName }
        }

        return "${method.declaringClass.name}.${method.name}($args)"
    }



    /**
     * Remove from the stacktrace the "slice" of stack that relates to "dorkbox.network." package
     *
     * Then remove from the last occurrence of "dorkbox.network." ALL "kotlinx.coroutines." and "kotlin.coroutines." stacks
     *
     * We do this because these stack frames are not useful in resolving exception handling from a users perspective, and only clutter the stacktrace.
     */
    fun cleanStackTraceForProxy(localException: Exception, remoteException: Exception? = null) {
        val myClassName = RmiClient::class.java.name
        val stackTrace = localException.stackTrace
        var newStartIndex = 0
        var newEndIndex = stackTrace.size-1

        // step 1: Find the start of our method invocation
        for (element in stackTrace) {
            newStartIndex++

            if (element.className == myClassName && element.methodName == "invoke") {
                // we do this 1 more time, because we want to remove the proxy invocation off the stack as well.
                newStartIndex++
                break
            }
        }

        // step 2: now we have to find the END index, since a proxy invocation ALWAYS happens starting from our network stack
        for (i in stackTrace.size-1 downTo 0) {
            newEndIndex--

            val stackClassName = stackTrace[i].className
            if (stackClassName.startsWith("dorkbox.network.rmi.")) {
                break
            }
        }

        if (remoteException == null) {
            // no remote exception, just cleanup our own callstack
            localException.stackTrace = stackTrace.copyOfRange(newStartIndex, newEndIndex)
        } else {
            // merge this info into the remote exception, so we can get the correct call stack info
            val newStack = Array<StackTraceElement>(remoteException.stackTrace.size + newEndIndex - newStartIndex) { stackTrace[0] }
            remoteException.stackTrace.copyInto(newStack)
            stackTrace.copyInto(newStack, remoteException.stackTrace.size, newStartIndex, newEndIndex)

            remoteException.stackTrace = newStack
        }
    }

    /**
     * Remove from the stacktrace (going in reverse), kotlin coroutine info + dorkbox network call stack.
     *
     * Neither of these are useful in resolving exception handling from a users perspective, and only clutter the stacktrace.
     */
    fun cleanStackTraceForImpl(exception: Exception, isSuspendFunction: Boolean) {
        val packageName = RmiUtils::class.java.packageName

        val stackTrace = exception.stackTrace
        var newEndIndex = -1 // because we index by size, but access from 0

        // step 1: starting at 0, find the start of our RMI method invocation
        for (element in stackTrace) {
            if (element.className.startsWith(packageName)) {
                break
            } else {
                newEndIndex++
            }
        }


        // step 2: starting at newEndIndex -> 0, find the start of reflection information (we are java11+ ONLY, so this is easy)
        for (i in newEndIndex downTo 0) {
            // this will be either JAVA reflection or ReflectASM reflection
            val stackModule = stackTrace[i].moduleName
            if (stackModule == "java.base") {
                newEndIndex--
            } else {
                break
            }
        }

        newEndIndex++ // have to add 1 back, because a copy must be by size (and we access from 0)

        if (newEndIndex > 0) {
            // if we are Java reflection, we are correct.
            // if we are ReflectASM reflection, there is ONE stack frame extra we have to remove
            if (stackTrace[newEndIndex].className == CachedAsmMethod::class.java.name) {
                newEndIndex--
            }

            // if we are a KOTLIN suspend function, there is ONE stack frame extra we have to remove
            if (isSuspendFunction) {
                newEndIndex--
            }

            exception.stackTrace = stackTrace.copyOfRange(0, newEndIndex)
        }
    }
}
