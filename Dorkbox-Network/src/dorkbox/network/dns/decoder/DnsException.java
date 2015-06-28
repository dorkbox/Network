/*
 * Copyright (c) 2013 The Netty Project
 * ------------------------------------
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Apache License v2.0 which accompanies this distribution.
 *
 *     The Eclipse Public License is available at
 *     http://www.eclipse.org/legal/epl-v10.html
 *
 *     The Apache License v2.0 is available at
 *     http://www.opensource.org/licenses/apache2.0.php
 *
 * You may elect to redistribute this code under either of these licenses.
 */
package dorkbox.network.dns.decoder;

import io.netty.handler.codec.dns.DnsResponseCode;

/**
 * Exception which is used to notify the promise if the DNS query fails.
 */
public final class DnsException extends Exception {
    private static final long serialVersionUID = 1310161373613598975L;

    private DnsResponseCode errorCode;

    public DnsException(DnsResponseCode errorCode) {
        if (errorCode == null) {
            throw new NullPointerException("errorCode");
        }
        this.errorCode = errorCode;
    }

    public DnsResponseCode errorCode() {
        return this.errorCode;
    }
}