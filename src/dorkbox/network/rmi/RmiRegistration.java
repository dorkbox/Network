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

/**
 * Message specifically to register a class implementation for RMI
 */
public
class RmiRegistration {
    public Object remoteObject;
    public String remoteImplementationClass;
    public boolean hasError;

    // this is used to get specific, GLOBAL rmi objects (objects that are not bound to a single connection)
    public int remoteObjectId;

    public
    RmiRegistration() {
        hasError = true;
    }

    public
    RmiRegistration(final String remoteImplementationClass) {
        this.remoteImplementationClass = remoteImplementationClass;
        hasError = false;
    }

    public
    RmiRegistration(final Object remoteObject) {
        this.remoteObject = remoteObject;
        hasError = false;
    }

    public
    RmiRegistration(final int remoteObjectId) {
        this.remoteObjectId = remoteObjectId;
        hasError = false;
    }
}
