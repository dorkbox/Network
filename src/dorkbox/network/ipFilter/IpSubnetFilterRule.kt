/*
 * Copyright 2014 The Netty Project
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
package dorkbox.network.ipFilter

import dorkbox.netUtil.IPv4
import dorkbox.netUtil.IPv6
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Use this class to create rules that group IP addresses into subnets.
 *
 * Supports both IPv4 and IPv6.
 */
internal class IpSubnetFilterRule : IpFilterRule {
    private val filterRule: IpFilterRule

    constructor(ipAddress: String, cidrPrefix: Int) {
        filterRule = when {
            IPv4.isValid(ipAddress) -> Ip4SubnetFilterRule(IPv4.toAddressUnsafe(ipAddress), cidrPrefix)
            IPv6.isValid(ipAddress) -> Ip6SubnetFilterRule(IPv6.toAddress(ipAddress)!!, cidrPrefix)
            else -> throw IllegalArgumentException("IpAddress format is invalid. Only IPv4 and IPv6 addresses are supported.")
        }
    }

    constructor(ipAddress: InetAddress, cidrPrefix: Int) {
        filterRule = when (ipAddress) {
            is Inet4Address -> Ip4SubnetFilterRule(ipAddress, cidrPrefix)
            is Inet6Address -> Ip6SubnetFilterRule(ipAddress, cidrPrefix)
            else -> throw IllegalArgumentException("Only IPv4 and IPv6 addresses are supported")
        }
    }

    override fun matches(remoteAddress: InetAddress): Boolean {
        return filterRule.matches(remoteAddress)
    }
}
