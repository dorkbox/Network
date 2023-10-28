/*
 * Copyright 2023 dorkbox, llc
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

package dorkbox.network.connection

import dorkbox.netUtil.IPv4
import dorkbox.netUtil.IPv6
import dorkbox.network.ServerConfiguration
import dorkbox.network.connection.IpInfo.Companion.IpListenType.IPC
import dorkbox.network.connection.IpInfo.Companion.IpListenType.IPWildcard
import dorkbox.network.connection.IpInfo.Companion.IpListenType.IPv4Wildcard
import dorkbox.network.connection.IpInfo.Companion.IpListenType.IPv6Wildcard
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

internal class IpInfo(config: ServerConfiguration) {


    companion object {
        enum class IpListenType {
            IPv4, IPv6, IPv4Wildcard, IPv6Wildcard, IPWildcard, IPC
        }

        fun isLocalhost(ipAddress: String): Boolean {
            return when (ipAddress.lowercase()) {
                "loopback", "localhost", "lo", "127.0.0.1", "::1" -> true
                else -> false
            }
        }
        fun isWildcard(ipAddress: String): Boolean {
            return when (ipAddress) {
                // this is the "wildcard" address. Windows has problems with this.
                "0", "::", "0.0.0.0", "*" -> true
                else -> false
            }
        }

        fun isWildcard(ipAddress: InetAddress): Boolean {
            return when (ipAddress) {
                // this is the "wildcard" address. Windows has problems with this.
                IPv4.WILDCARD, IPv6.WILDCARD -> true
                else -> false
            }
        }

        fun getWildcard(ipAddress: InetAddress, ipAddressString: String, shouldBeIpv4: Boolean): String {
            return if (isWildcard(ipAddress)) {
                if (shouldBeIpv4) {
                    IPv4.WILDCARD_STRING
                } else {
                    IPv6.WILDCARD_STRING
                }
            } else {
                ipAddressString
            }
        }

        fun formatCommonAddress(ipAddress: String, isIpv4: Boolean, elseAction: () -> InetAddress?): InetAddress? {
            return if (isLocalhost(ipAddress)) {
                if (isIpv4) { IPv4.LOCALHOST } else { IPv6.LOCALHOST }
            } else if (isWildcard(ipAddress)) {
                if (isIpv4) { IPv4.WILDCARD } else { IPv6.WILDCARD }
            } else if (IPv4.isValid(ipAddress)) {
                IPv4.toAddress(ipAddress)!!
            } else if (IPv6.isValid(ipAddress)) {
                IPv6.toAddress(ipAddress)!!
            } else {
                elseAction()
            }
        }

        fun formatCommonAddressString(ipAddress: String, isIpv4: Boolean, elseAction: () -> String = { ipAddress }): String {
            return if (isLocalhost(ipAddress)) {
                if (isIpv4) { IPv4.LOCALHOST_STRING } else { IPv6.LOCALHOST_STRING }
            } else if (isWildcard(ipAddress)) {
                if (isIpv4) { IPv4.WILDCARD_STRING } else { IPv6.WILDCARD_STRING }
            } else if (IPv4.isValid(ipAddress)) {
                ipAddress
            } else if (IPv6.isValid(ipAddress)) {
                ipAddress
            } else {
                elseAction()
            }
        }
    }




    val ipType: IpListenType


    val listenAddress: InetAddress?
    val listenAddressString: String
    val formattedListenAddressString: String
    val listenAddressStringPretty: String
    val isReliable = config.isReliable

    val isIpv4: Boolean

    init {
        val canUseIPv4 = config.enableIPv4 && IPv4.isAvailable
        val canUseIPv6 = config.enableIPv6 && IPv6.isAvailable

        // localhost/loopback IP might not always be 127.0.0.1 or ::1
        // We want to listen on BOTH IPv4 and IPv6 (config option lets us configure this) we listen in IPv6 WILDCARD
        var listenAddress: InetAddress?
        var ip46Wildcard = false

        when {
            canUseIPv4 && canUseIPv6 ->  {
                // if it's not a valid IP, the lambda will return null
                listenAddress = formatCommonAddress(config.listenIpAddress, false) { null }

                if (listenAddress == null) {
                    listenAddress = formatCommonAddress(config.listenIpAddress, true) { null }
                } else {
                    ip46Wildcard = true
                }
            }
            canUseIPv4 -> {
                // if it's not a valid IP, the lambda will return null
                listenAddress = formatCommonAddress(config.listenIpAddress, true) { null }
            }
            canUseIPv6 -> {
                // if it's not a valid IP, the lambda will return null
                listenAddress = formatCommonAddress(config.listenIpAddress, false) { null }
            }
            else -> {
                listenAddress = null
            }
        }

        this.listenAddress = listenAddress


        isIpv4 = listenAddress is Inet4Address

        // if we are IPv6 WILDCARD -- then our listen-address must ALSO be IPv6, even if our connection is via IPv4
        when (listenAddress) {
            IPv6.WILDCARD -> {
                ipType = if (ip46Wildcard) {
                    IPWildcard
                } else {
                    IPv6Wildcard
                }

                listenAddressString = IPv6.WILDCARD_STRING
                formattedListenAddressString = if (listenAddressString[0] == '[') {
                    listenAddressString
                } else {
                    // there MUST be [] surrounding the IPv6 address for aeron to like it!
                    "[$listenAddressString]"
                }
            }
            IPv4.WILDCARD -> {
                ipType = IPv4Wildcard
                listenAddressString = IPv4.WILDCARD_STRING
                formattedListenAddressString = listenAddressString
            }
            is Inet6Address -> {
                ipType = IpListenType.IPv6
                listenAddressString = IPv6.toString(listenAddress)
                formattedListenAddressString = if (listenAddressString[0] == '[') {
                    listenAddressString
                } else {
                    // there MUST be [] surrounding the IPv6 address for aeron to like it!
                    "[$listenAddressString]"
                }
            }
            is Inet4Address -> {
                ipType = IpListenType.IPv4
                listenAddressString = IPv4.toString(listenAddress)
                formattedListenAddressString = listenAddressString
            }
            else -> {
                ipType = IPC
                listenAddressString = EndPoint.IPC_NAME
                formattedListenAddressString = listenAddressString
            }
        }

        listenAddressStringPretty = when (listenAddress) {
            IPv4.WILDCARD -> listenAddressString
            IPv6.WILDCARD -> IPv4.WILDCARD.hostAddress + "/" + listenAddressString
            else -> listenAddressString
        }
    }

    /**
     * if we are listening on :: (ipv6), and a connection via ipv4 arrives, aeron MUST publish on the IPv4 version
     */
    fun getAeronPubAddress(remoteIpv4: Boolean): String {
        return if (remoteIpv4) {
            when (ipType) {
                IPWildcard -> IPv4.WILDCARD_STRING
                else -> formattedListenAddressString
            }
        } else {
            formattedListenAddressString
        }
    }

    /**
     * if we are listening on :: (ipv6), and a connection via ipv4 arrives, aeron MUST publish on the IPv6 version
     */
    fun getAeronSubAddress(remoteIpv4: Boolean): String {
        return if (remoteIpv4) {
            when (ipType) {
                IPWildcard -> IPv4.WILDCARD_STRING
                else -> formattedListenAddressString
            }
        } else {
            formattedListenAddressString
        }
    }
}
