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
 * Internal message to handle the TCP/UDP/UDT registration process
 */
public
class Registration {
    public static final byte notAdroid = (byte) 0;
    public static final byte android = (byte) 1;


    // signals which serialization is possible. If they match, then UNSAFE can be used (except android. it always must use ASM)
    public byte connectionType;

    public ECPublicKeyParameters publicKey;
    public IESParameters eccParameters;
    public byte[] aesKey;
    public byte[] aesIV;

    public byte[] payload;
}
