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
package dorkbox.network.other.coroutines;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import kotlin.coroutines.Continuation;
import kotlin.jvm.functions.Function1;

/**
 * Class to access suspending invocation of methods from kotlin...
 *
 * ULTIMATELY, this is all java bytecode, and the bytecode signature here matches what kotlin expects. The generics type information is
 * discarded at compile time.
 */
public
class SuspendFunctionTrampoline {

    /**
     * trampoline so we can access suspend functions correctly using reflection
     */
    @SuppressWarnings("unchecked")
    @Nullable
    public static
    Object invoke(@NotNull final Continuation<?> continuation, @NotNull final Object suspendFunction) throws Throwable {
        Function1<? super Continuation<? super Object>, ?> suspendFunction1 = (Function1<? super Continuation<? super Object>, ?>) suspendFunction;
        return suspendFunction1.invoke((Continuation<? super Object>) continuation);
    }
}
