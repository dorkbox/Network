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

package dorkboxTest.network.rmi
import junit.framework.TestCase
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
// https://gist.github.com/hiperbou/283e6531fb2b71b324d5064f820df889
// https://github.com/Kotlin/kotlinx.coroutines/pull/1667

class SuspendProxyTest : TestCase() {
    interface Adder{
        suspend fun add(a:Int, b:Int):Int
        fun addSync(a:Int, b:Int):Int
    }

    class SuspendHandler(private val delegate:Adder): InvocationHandler {
        override fun invoke(proxy: Any, method: Method, arguments: Array<Any>): Any {
            val suspendCoroutineObject = arguments.lastOrNull()
            return if (suspendCoroutineObject is Continuation<*>) {
                val parameters = arguments.copyOf(arguments.size - 1)
                @Suppress("UNCHECKED_CAST")
                val continuation = suspendCoroutineObject as Continuation<Any?>
                val retVal = method.invoke(delegate, *parameters, Continuation<Any?>(EmptyCoroutineContext) {
                    val continuationResult = it.getOrNull()
                    continuation.resume(continuationResult)
                })

                retVal
            } else {
                method.invoke(delegate, *arguments)
            }
        }
    }

    fun testCacheSimple() = runBlocking {
        val delegate = object:Adder {
            override suspend fun add(a:Int, b:Int):Int {
                println("delay 1")
                delay(1000)
                println("delay 2")
                delay(1000)
                println("delay 3")
                delay(1000)
                return a + b
            }

            override fun addSync(a:Int, b:Int):Int {
                return a + b
            }
        }

        val adder = Proxy.newProxyInstance(
                Adder::class.java.classLoader,
                arrayOf(Adder::class.java),
                SuspendHandler(delegate)
        ) as Adder

        println("starting")
        val result = adder.add(1,3)
        println("done 1")
        val result2 = adder.addSync(1,3)
        println("done 2")

        println(result)
        println(result2)
    }
}
