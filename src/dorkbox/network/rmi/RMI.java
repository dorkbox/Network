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
package dorkbox.network.rmi;

import java.lang.annotation.*;

/**
 * This specifies to the serializer, that this class contains an RMI object, and that a specific field is an RMI object. Both are
 * necessary.
 * <p/>
 * Additional behavior of RMI methods, is if there is another method (of the same name and signature), with the addition of a Connection
 * parameter in the first position, THAT method will be called instead, an will have the current connection object passed into the method.
 * <p/>
 * It is mandatory for the correct implementation (as per the interface guideline) to exist, and should return null.
 * <p/>
 * IE: foo(String something)...  ->  foo(Connection connection, String something)....
 */
@Retention(value = RetentionPolicy.RUNTIME)
@Inherited
@Target(value = {ElementType.TYPE, ElementType.FIELD})
public
@interface RMI {}
