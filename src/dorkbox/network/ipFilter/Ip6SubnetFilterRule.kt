/*
 * Copyright 2021 dorkbox, llc
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

package dorkbox.network.ipFilter

import dorkbox.netUtil.IPv6
import java.math.BigInteger
import java.net.Inet6Address
import java.net.InetAddress

internal class Ip6SubnetFilterRule(ipAddress: Inet6Address, cidrPrefix: Int) : IpFilterRule {
    private val networkAddress: BigInteger
    private val subnetMask: BigInteger

    init {
        require(cidrPrefix in 0..128) { "IPv6 requires the subnet prefix to be in range of [0,128]. The prefix was: $cidrPrefix" }
        subnetMask = IPv6.cidrPrefixToSubnetMask(cidrPrefix)
        networkAddress = IPv6.toInt(ipAddress.address).and(subnetMask)
    }

    override fun matches(remoteAddress: InetAddress): Boolean {
        if (remoteAddress is Inet6Address) {
            val ipAddress = IPv6.toInt(remoteAddress.address)
            return ipAddress.and(subnetMask) == networkAddress
        }
        return false
    }
}
