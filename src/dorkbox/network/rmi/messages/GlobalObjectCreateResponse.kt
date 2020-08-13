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
package dorkbox.network.rmi.messages


/**
 * These use packed IDs, because both are REALLY shorts, but the JVM deals better with ints.
 *
 * @param rmiId (LEFT) the Kryo interface class ID to create
 * @param callbackId (RIGHT) to know which callback to use when the object is created
 */
data class GlobalObjectCreateResponse(val packedIds: Int) : RmiMessage