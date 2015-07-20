/*
 * Copyright 2013 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package dorkbox.network.dns.record;

/**
 * Represents an MX record.
 */
public
class MailExchangerRecord {

    private final int priority;
    private final String name;

    /**
     * @param priority lower is more preferred
     * @param name     e-mail address in the format admin.example.com, which
     *                 represents admin@example.com
     */
    public
    MailExchangerRecord(int priority, String name) {
        this.priority = priority;
        this.name = name;
    }

    public
    int priority() {
        return this.priority;
    }

    /**
     * Returns the MX (an e-mail address) in the format
     * admin.example.com, which represents admin@example.com.
     */
    public
    String name() {
        return this.name;
    }
}
