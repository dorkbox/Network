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

package dorkbox.network.serialization

/**
 * These class registrations are so that we can ADD functionality to the system without having upstream implementations CHANGE
 * their registration IDs.
 *
 * This permits us to have version A on one side of the connection, and version B (with a "new" class) on the other side.
 *
 * The only issue is that the new feature will NOT WORK until both sides have the same version. By running different versions, the upstream
 * implementation will not break -- just the network feature will not work until both sides have it
 */
class Reserved0 {}
class Reserved1 {}
class Reserved2 {}
class Reserved3 {}
class Reserved4 {}
class Reserved5 {}
class Reserved6 {}
class Reserved7 {}
class Reserved8 {}
class Reserved9 {}
