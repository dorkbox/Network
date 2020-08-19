/*
 * Copyright 2020 dorkbox, llc
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
package dorkbox.network.connection.connectionType

import java.math.BigInteger
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 *
 */
class ConnectionRule(ipAddress: InetAddress, cidrPrefix: Int, ruleType: ConnectionProperties) {
    private val filterRule: IpConnectionTypeRule

    companion object {
        private fun selectFilterRule(ipAddress: InetAddress, cidrPrefix: Int, ruleType: ConnectionProperties): IpConnectionTypeRule {
            return when (ipAddress) {
                is Inet4Address -> {
                    Ip4SubnetFilterRule(ipAddress, cidrPrefix, ruleType)
                }
                is Inet6Address -> {
                    Ip6SubnetFilterRule(ipAddress, cidrPrefix, ruleType)
                }
                else -> {
                    throw IllegalArgumentException("Only IPv4 and IPv6 addresses are supported")
                }
            }
        }
    }

    init {
        filterRule = selectFilterRule(ipAddress, cidrPrefix, ruleType)
    }

    fun matches(remoteAddress: InetSocketAddress): Boolean {
        return filterRule.matches(remoteAddress)
    }

    fun ruleType(): ConnectionProperties {
        return filterRule.ruleType()
    }

    private class Ip4SubnetFilterRule(ipAddress: Inet4Address, cidrPrefix: Int, ruleType: ConnectionProperties) : IpConnectionTypeRule {

        companion object {
            private fun ipToInt(ipAddress: Inet4Address): Int {
                val octets = ipAddress.address
                assert(octets.size == 4)
                return (0xff and octets[0].toInt()) shl 24 or (
                        (0xff and octets[1].toInt()) shl 16) or (
                        (0xff and octets[2].toInt()) shl 8) or (
                        (0xff and octets[3].toInt()) )
            }


            private fun prefixToSubnetMask(cidrPrefix: Int): Int {
                /**
                 * Perform the shift on a long and downcast it to int afterwards.
                 * This is necessary to handle a cidrPrefix of zero correctly.
                 * The left shift operator on an int only uses the five least
                 * significant bits of the right-hand operand. Thus -1 << 32 evaluates
                 * to -1 instead of 0. The left shift operator applied on a long
                 * uses the six least significant bits.
                 *
                 * Also see https://github.com/netty/netty/issues/2767
                 */
                return (-1L shl 32 - cidrPrefix and -0x1).toInt()
            }
        }

        private val networkAddress: Int
        private val subnetMask: Int
        private val ruleType: ConnectionProperties

        init {
            require(!(cidrPrefix < 0 || cidrPrefix > 32)) {
                String.format("IPv4 requires the subnet prefix to be in range of " +
                        "[0,32]. The prefix was: %d", cidrPrefix)
            }
            subnetMask = prefixToSubnetMask(cidrPrefix)
            networkAddress = ipToInt(ipAddress) and subnetMask
            this.ruleType = ruleType
        }

        override fun matches(remoteAddress: InetSocketAddress): Boolean {
            val inetAddress = remoteAddress.address
            if (inetAddress is Inet4Address) {
                val ipAddress = ipToInt(inetAddress)
                return ipAddress and subnetMask == networkAddress
            }
            return false
        }

        override fun ruleType(): ConnectionProperties {
            return ruleType
        }
    }

    private class Ip6SubnetFilterRule(ipAddress: Inet6Address, cidrPrefix: Int, ruleType: ConnectionProperties) : IpConnectionTypeRule {

        companion object {
            private val MINUS_ONE = BigInteger.valueOf(-1)

            private fun ipToInt(ipAddress: Inet6Address): BigInteger {
                val octets = ipAddress.address
                assert(octets.size == 16)
                return BigInteger(octets)
            }

            private fun prefixToSubnetMask(cidrPrefix: Int): BigInteger {
                return MINUS_ONE.shiftLeft(128 - cidrPrefix)
            }
        }

        private val networkAddress: BigInteger
        private val subnetMask: BigInteger
        private val ruleType: ConnectionProperties

        init {
            require(!(cidrPrefix < 0 || cidrPrefix > 128)) {
                String.format("IPv6 requires the subnet prefix to be in range of " +
                        "[0,128]. The prefix was: %d", cidrPrefix)
            }
            subnetMask = prefixToSubnetMask(cidrPrefix)
            networkAddress = ipToInt(ipAddress).and(subnetMask)
            this.ruleType = ruleType
        }

        override fun matches(remoteAddress: InetSocketAddress): Boolean {
            val inetAddress = remoteAddress.address
            if (inetAddress is Inet6Address) {
                val ipAddress = ipToInt(inetAddress)
                return ipAddress.and(subnetMask) == networkAddress
            }
            return false
        }

        override fun ruleType(): ConnectionProperties {
            return ruleType
        }
    }
}
