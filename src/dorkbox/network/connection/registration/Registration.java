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
package dorkbox.network.connection.registration;

import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.bouncycastle.crypto.params.IESParameters;

/**
 * Internal message to handle the TCP/UDP registration process
 */
public
class Registration {
    // used to keep track and associate TCP/UDP/etc sessions. This is always defined by the server
    // a sessionId if '0', means we are still figuring it out.
    public int sessionID;

    public ECPublicKeyParameters publicKey;
    public IESParameters eccParameters;

    public byte[] payload;

    // true if we have more registrations to process, false if we are done
    public boolean hasMore;

    // true when we are ready to setup the connection (hasMore will always be false if this is true). False when we are ready to connect
    // ALSO used if there are fragmented frames for registration data (since we have to split it up to fit inside a single UDP packet without fragmentation)
    public boolean upgrade;

    // true when we are fully upgraded
    public boolean upgraded;

    private
    Registration() {
        // for serialization
    }

    public
    Registration(final int sessionID) {
        this.sessionID = sessionID;
    }
}
