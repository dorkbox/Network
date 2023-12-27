/*
 * Copyright 2023 dorkbox, llc
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

package dorkbox.network.aeron

import dorkbox.jna.ClassUtils
import dorkbox.os.OS
import javassist.ClassPool
import javassist.CtNewMethod



object FixTransportPoller {
    // allow access to sun.nio.ch.SelectorImpl without causing reflection or JPMS module issues
    fun init() {
        if (OS.javaVersion <= 11) {
            // older versions of java don't need to worry about rewriting anything
            return
        }


        try {
            val pool = ClassPool.getDefault()

            run {
                val dynamicClass = pool.makeClass("sun.nio.ch.SelectorImplAccessory")
                val method = CtNewMethod.make(
                    ("public static java.lang.reflect.Field getKey(java.lang.String fieldName) { " +
                            "java.lang.reflect.Field field = Class.forName(\"sun.nio.ch.SelectorImpl\").getDeclaredField( fieldName );" +
                            "field.setAccessible( true );" +
                            "return field;" +
                        "}"), dynamicClass
                )
                dynamicClass.addMethod(method)

                val dynamicClassBytes = dynamicClass.toBytecode()
                ClassUtils.defineClass(null, dynamicClassBytes)
            }

            // have to trampoline off this to get around module access
            run {
                val dynamicClass = pool.makeClass("java.lang.SelectorImplAccessory")
                val method = CtNewMethod.make(
                    ("public static java.lang.reflect.Field getKey(java.lang.String fieldName) { " +
                            "return sun.nio.ch.SelectorImplAccessory.getKey(fieldName);" +
                            "}"), dynamicClass
                )
                dynamicClass.addMethod(method)

                val dynamicClassBytes = dynamicClass.toBytecode()
                ClassUtils.defineClass(null, dynamicClassBytes)
            }

            run {
                val dynamicClass = pool.getCtClass("org.agrona.nio.TransportPoller")

                // Get the static initializer
                val staticInitializer = dynamicClass.classInitializer

                // Remove the existing static initializer
                dynamicClass.removeConstructor(staticInitializer)

                val initializer = dynamicClass.makeClassInitializer()
                initializer.insertAfter(
                    "java.lang.System.err.println(\"updating TransportPoller!\");" +
                       "java.lang.reflect.Field selectKeysField = null;\n" +
                       "java.lang.reflect.Field publicSelectKeysField = null;\n" +
                       "try {\n" +
                       "    java.nio.channels.Selector selector = java.nio.channels.Selector.open();\n" +
                       "    Throwable var3 = null;\n" + "\n" +
                       "    try {\n" +
                       "        Class clazz = Class.forName(\"sun.nio.ch.SelectorImpl\", false, ClassLoader.getSystemClassLoader());\n" +
                       "        if (clazz.isAssignableFrom(selector.getClass())) {\n" +
                       "            selectKeysField = java.lang.SelectorImplAccessory.getKey(\"selectedKeys\");\n" +
                       "            publicSelectKeysField = java.lang.SelectorImplAccessory.getKey(\"publicSelectedKeys\");\n" +
                       "        }\n" +
                       "    } catch (Throwable var21) {\n" +
                       "        var3 = var21;\n" +
                       "        throw var21;\n" +
                       "    } finally {\n" +
                       "        if (selector != null) {\n" +
                       "            if (var3 != null) {\n" +
                       "                try {\n" +
                       "                    selector.close();\n" +
                       "                } catch (Throwable var20) {\n" +
                       "                    var3.addSuppressed(var20);\n" +
                       "                }\n" +
                       "            } else {\n" +
                       "                selector.close();\n" +
                       "            }\n" +
                       "       }\n" +
                       "    }\n" +
                       "} catch (Exception var23) {\n" +
                       "    org.agrona.LangUtil.rethrowUnchecked(var23);\n" +
                       "} finally {\n" +
                       "    org.agrona.nio.TransportPoller.SELECTED_KEYS_FIELD = selectKeysField;\n" +
                       "    org.agrona.nio.TransportPoller.PUBLIC_SELECTED_KEYS_FIELD = publicSelectKeysField;\n" +
                       "}"
                )


                // perform pre-verification for the modified method
                initializer.methodInfo.rebuildStackMapForME(pool)

                val dynamicClassBytes = dynamicClass.toBytecode()
                ClassUtils.defineClass(ClassLoader.getSystemClassLoader(), dynamicClassBytes)
            }

        } catch (e: Exception) {
            throw RuntimeException("Could not fix Aeron TransportPoller", e)
        }
    }
}
