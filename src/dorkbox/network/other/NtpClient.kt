/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dorkbox.network.other

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.time.Duration
import java.util.*

/**
 * Simple SNTP client class for retrieving network time.
 *
 * Sample usage:
 * <pre>SntpClient client = new SntpClient();
 * if (client.requestTime("time.foo.com")) {
 * long now = client.getNtpTime() + SystemClock.elapsedRealtime() - client.getNtpTimeReference();
 * }
</pre> *
 */
class NtpClient {
    companion object {
        private const val TAG = "SntpClient"
        private const val DBG = true
        private const val REFERENCE_TIME_OFFSET = 16
        private const val ORIGINATE_TIME_OFFSET = 24
        private const val RECEIVE_TIME_OFFSET = 32
        private const val TRANSMIT_TIME_OFFSET = 40
        private const val NTP_PACKET_SIZE = 48
        private const val NTP_PORT = 123
        private const val NTP_MODE_CLIENT = 3
        private const val NTP_MODE_SERVER = 4
        private const val NTP_MODE_BROADCAST = 5
        private const val NTP_VERSION = 3
        private const val NTP_LEAP_NOSYNC = 3
        private const val NTP_STRATUM_DEATH = 0
        private const val NTP_STRATUM_MAX = 15

        // Number of seconds between Jan 1, 1900 and Jan 1, 1970
        // 70 years plus 17 leap days
        private const val OFFSET_1900_TO_1970 = (365L * 70L + 17L) * 24L * 60L * 60L

        @Throws(InvalidServerReplyException::class)
        private fun checkValidServerReply(
                leap: Int, mode: Int, stratum: Int, transmitTime: Long) {
            if (leap == NTP_LEAP_NOSYNC) {
                throw InvalidServerReplyException("unsynchronized server")
            }
            if (mode != NTP_MODE_SERVER && mode != NTP_MODE_BROADCAST) {
                throw InvalidServerReplyException("untrusted mode: $mode")
            }
            if (stratum == NTP_STRATUM_DEATH || stratum > NTP_STRATUM_MAX) {
                throw InvalidServerReplyException("untrusted stratum: $stratum")
            }
            if (transmitTime == 0L) {
                throw InvalidServerReplyException("zero transmitTime")
            }
        }
    }

    /**
     * Returns the time computed from the NTP transaction.
     *
     * @return time value computed from NTP server response.
     */
    // system time computed from NTP server response
    var ntpTime: Long = 0
        private set

    /**
     * Returns the reference clock value (value of SystemClock.elapsedRealtime())
     * corresponding to the NTP time.
     *
     * @return reference clock corresponding to the NTP time.
     */
    // value of SystemClock.elapsedRealtime() corresponding to mNtpTime
    var ntpTimeReference: Long = 0
        private set

    /**
     * Returns the round trip time of the NTP transaction
     *
     * @return round trip time in milliseconds.
     */
    // round trip time in milliseconds
    var roundTripTime: Long = 0
        private set

    private class InvalidServerReplyException(message: String) : Exception(message)

    /**
     * Sends an SNTP request to the given host and processes the response.
     *
     * @param host host name of the server.
     * @param timeoutMS network timeout in milliseconds.
     * @return true if the transaction was successful.
     */
    fun requestTime(host: String, timeoutMS: Int): Boolean {
        var socket: DatagramSocket? = null
        var address: InetAddress? = null

        try {
            socket = DatagramSocket()
            socket.soTimeout = timeoutMS
            address = InetAddress.getByName(host)

            val buffer = ByteArray(NTP_PACKET_SIZE)
            val request = DatagramPacket(buffer, buffer.size, address, NTP_PORT)

            // set mode = 3 (client) and version = 3
            // mode is in low 3 bits of first byte
            // version is in bits 3-5 of first byte
            buffer[0] = (NTP_MODE_CLIENT or (NTP_VERSION shl 3)).toByte()


            // get current time and write it to the request packet
            val requestTime = System.currentTimeMillis()
            writeTimeStamp(buffer, TRANSMIT_TIME_OFFSET, requestTime)
            val requestTicks = System.nanoTime()


            socket.send(request)

            // read the response
            val response = DatagramPacket(buffer, buffer.size)
            socket.receive(response)

            val responseTicks = System.nanoTime()
            val ticksDurationMS = Duration.ofNanos(responseTicks - requestTicks).toMillis()
            val responseTime = requestTime + ticksDurationMS

            socket.close()


            // extract the results
            // extract the results
            val leap = buffer[0].toInt() shr 6 and 0x3
            val mode = buffer[0].toInt() and 0x7
            val stratum = buffer[1].toInt() and 0xff
            val originateTime = readTimeStamp(buffer, ORIGINATE_TIME_OFFSET)
            val receiveTime = readTimeStamp(buffer, RECEIVE_TIME_OFFSET)
            val transmitTime = readTimeStamp(buffer, TRANSMIT_TIME_OFFSET)

            /* do sanity check according to RFC */
            // TODO: validate originateTime == requestTime.
            checkValidServerReply(leap, mode, stratum, transmitTime)
            val roundTripTime = ticksDurationMS - (transmitTime - receiveTime)
            // receiveTime = originateTime + transit + skew
            // responseTime = transmitTime + transit - skew
            // clockOffset = ((receiveTime - originateTime) + (transmitTime - responseTime))/2
            //             = ((originateTime + transit + skew - originateTime) +
            //                (transmitTime - (transmitTime + transit - skew)))/2
            //             = ((transit + skew) + (transmitTime - transmitTime - transit + skew))/2
            //             = (transit + skew - transit + skew)/2
            //             = (2 * skew)/2 = skew
            val clockOffset = (receiveTime - originateTime + (transmitTime - responseTime)) / 2
            // if (Config.LOGD) Log.d(TAG, "round trip: " + roundTripTime + " ms");
            // if (Config.LOGD) Log.d(TAG, "clock offset: " + clockOffset + " ms");


            // save our results - use the times on this side of the network latency
            // (response rather than request time)
            ntpTime = responseTime + clockOffset
            ntpTimeReference = responseTicks
            this.roundTripTime = roundTripTime
        } catch (e: Exception) {
            System.err.println("Error with NTP to $address.  $e")
            return false
        } finally {
            socket?.close()
        }
        return true
    }

    /**
     * Reads an unsigned 32 bit big endian number from the given offset in the buffer.
     */
    private fun read32(buffer: ByteArray, offset: Int): Long {
        val b0 = buffer[offset].toInt()
        val b1 = buffer[offset + 1].toInt()
        val b2 = buffer[offset + 2].toInt()
        val b3 = buffer[offset + 3].toInt()

        // convert signed bytes to unsigned values
        val i0 = (if (b0 and 0x80 == 0x80) (b0 and 0x7F) + 0x80 else b0).toInt()
        val i1 = (if (b1 and 0x80 == 0x80) (b1 and 0x7F) + 0x80 else b1).toInt()
        val i2 = (if (b2 and 0x80 == 0x80) (b2 and 0x7F) + 0x80 else b2).toInt()
        val i3 = (if (b3 and 0x80 == 0x80) (b3 and 0x7F) + 0x80 else b3).toInt()
        return (i0.toLong() shl 24) + (i1.toLong() shl 16) + (i2.toLong() shl 8) + i3.toLong()
    }

    /**
     * Reads the NTP time stamp at the given offset in the buffer and returns
     * it as a system time (milliseconds since January 1, 1970).
     */
    private fun readTimeStamp(buffer: ByteArray, offset: Int): Long {
        val seconds = read32(buffer, offset)
        val fraction = read32(buffer, offset + 4)
        // Special case: zero means zero.
        return if (seconds == 0L && fraction == 0L) {
            0
        } else (seconds - OFFSET_1900_TO_1970) * 1000 + fraction * 1000L / 0x100000000L
    }

    /**
     * Writes system time (milliseconds since January 1, 1970) as an NTP time stamp
     * at the given offset in the buffer.
     */
    private fun writeTimeStamp(buffer: ByteArray, offset: Int, time: Long) {
        // Special case: zero means zero.
        var offset = offset
        if (time == 0L) {
            Arrays.fill(buffer, offset, offset + 8, 0x00.toByte())
            return
        }


        var seconds = time / 1000L
        val milliseconds = time - seconds * 1000L
        seconds += OFFSET_1900_TO_1970

        // write seconds in big endian format
        buffer[offset++] = (seconds shr 24).toByte()
        buffer[offset++] = (seconds shr 16).toByte()
        buffer[offset++] = (seconds shr 8).toByte()
        buffer[offset++] = (seconds shr 0).toByte()
        val fraction = milliseconds * 0x100000000L / 1000L
        // write fraction in big endian format
        buffer[offset++] = (fraction shr 24).toByte()
        buffer[offset++] = (fraction shr 16).toByte()
        buffer[offset++] = (fraction shr 8).toByte()
        // low order bits should be random data
        buffer[offset++] = (Math.random() * 255.0).toByte()
    }
}
