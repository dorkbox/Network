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
 * Represents an SRV record.
 * <p/>
 * This is the location (or hostname and port), of servers for specified services. For example, a service "http"
 * may be running on the server "example.com" on port 80.
 */
public
class ServiceRecord {

    private final int priority;
    private final int weight;
    private final int port;
    private final String name;
    private final String protocol;
    private final String service;
    private final String target;

    /**
     * @param fullPath the name first read in the SRV record which contains the
     *                 service, the protocol, and the name of the server being
     *                 queried. The format is {@code _service._protocol.hostname}, or
     *                 for example {@code _http._tcp.example.com}
     * @param priority relative priority of this service, range 0-65535 (lower is
     *                 higher priority)
     * @param weight   determines how often multiple services will be used in the
     *                 event they have the same priority (greater weight means
     *                 service is used more often)
     * @param port     the port for the service
     * @param target   the name of the host for the service
     */
    public
    ServiceRecord(String fullPath, int priority, int weight, int port, String target) {
        String[] parts = fullPath.split("\\.", 3);
        this.service = parts[0];
        this.protocol = parts[1];
        this.name = parts[2];
        this.priority = priority;
        this.weight = weight;
        this.port = port;
        this.target = target;
    }

    public
    int priority() {
        return this.priority;
    }

    public
    int weight() {
        return this.weight;
    }

    public
    int port() {
        return this.port;
    }

    public
    String name() {
        return this.name;
    }

    public
    String protocol() {
        return this.protocol;
    }

    /**
     * @return the service's name (i.e. "_http").
     */
    public
    String service() {
        return this.service;
    }

    /**
     * @return he name of the host for the service.
     */
    public
    String target() {
        return this.target;
    }
}
