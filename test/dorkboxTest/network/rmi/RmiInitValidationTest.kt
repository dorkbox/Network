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
package dorkboxTest.network.rmi

import dorkbox.network.Client
import dorkbox.network.Configuration
import dorkbox.network.Server
import dorkbox.network.connection.Connection
import dorkbox.network.serialization.NetworkSerializationManager
import dorkbox.util.exceptions.SecurityException
import dorkboxTest.network.BaseTest
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.IOException

class RmiInitValidationTest : BaseTest() {
    @Test
    @Throws(SecurityException::class, IOException::class)
    fun rmiNetwork() {
        rmi()
    }

    @Test
    @Throws(SecurityException::class, IOException::class)
    fun rmiLocal() {
//        rmi(object : Config() {
//            fun apply(configuration: Configuration) {
//                configuration.localChannelName = EndPoint.LOCAL_CHANNEL
//            }
//        })
    }

    private fun register(serialization: NetworkSerializationManager) {
        serialization.register(Command1::class.java)
        serialization.register(Command2::class.java)
        serialization.register(Command3::class.java)
        serialization.register(Command4::class.java)
        serialization.register(Command5::class.java)
        serialization.register(Command6::class.java)
        serialization.register(Command7::class.java)
        serialization.register(Command8::class.java)
        serialization.register(Command9::class.java)
        serialization.register(Command10::class.java)
        serialization.register(Command20::class.java)
        serialization.register(Command30::class.java)
        serialization.register(Command40::class.java)
        serialization.register(Command50::class.java)
        serialization.register(Command60::class.java)
        serialization.register(Command70::class.java)
        serialization.register(Command80::class.java)
        serialization.register(Command90::class.java)
        serialization.register(Command11::class.java)
        serialization.register(Command12::class.java)
        serialization.register(Command13::class.java)
        serialization.register(Command14::class.java)
        serialization.register(Command15::class.java)
        serialization.register(Command16::class.java)
        serialization.register(Command17::class.java)
        serialization.register(Command18::class.java)
        serialization.register(Command19::class.java)
        serialization.register(Command21::class.java)
        serialization.register(Command22::class.java)
        serialization.register(Command23::class.java)
        serialization.register(Command24::class.java)
        serialization.register(Command25::class.java)
        serialization.register(Command26::class.java)
        serialization.register(Command27::class.java)
        serialization.register(Command28::class.java)
        serialization.register(Command29::class.java)
        serialization.register(Command31::class.java)
        serialization.register(Command32::class.java)
        serialization.register(Command33::class.java)
        serialization.register(Command34::class.java)
        serialization.register(Command35::class.java)
        serialization.register(Command36::class.java)
        serialization.register(Command37::class.java)
        serialization.register(Command38::class.java)
        serialization.register(Command39::class.java)
        serialization.register(Command41::class.java)
        serialization.register(Command42::class.java)
        serialization.register(Command43::class.java)
        serialization.register(Command44::class.java)
        serialization.register(Command45::class.java)
        serialization.register(Command46::class.java)
        serialization.register(Command47::class.java)
        serialization.register(Command48::class.java)
        serialization.register(Command49::class.java)
        serialization.register(Command51::class.java)
        serialization.register(Command52::class.java)
        serialization.register(Command53::class.java)
        serialization.register(Command54::class.java)
        serialization.register(Command55::class.java)
        serialization.register(Command56::class.java)
        serialization.register(Command57::class.java)
        serialization.register(Command58::class.java)
        serialization.register(Command59::class.java)
        serialization.register(Command61::class.java)
        serialization.register(Command62::class.java)
        serialization.register(Command63::class.java)
        serialization.register(Command64::class.java)
        serialization.register(Command65::class.java)
        serialization.register(Command66::class.java)
        serialization.register(Command67::class.java)
        serialization.register(Command68::class.java)
        serialization.register(Command69::class.java)
        serialization.register(Command71::class.java)
        serialization.register(Command72::class.java)
        serialization.register(Command73::class.java)
        serialization.register(Command74::class.java)
        serialization.register(Command75::class.java)
        serialization.register(Command76::class.java)
        serialization.register(Command77::class.java)
        serialization.register(Command78::class.java)
        serialization.register(Command79::class.java)
        serialization.register(Command81::class.java)
        serialization.register(Command82::class.java)
        serialization.register(Command83::class.java)
        serialization.register(Command84::class.java)
        serialization.register(Command85::class.java)
        serialization.register(Command86::class.java)
        serialization.register(Command87::class.java)
        serialization.register(Command88::class.java)
        serialization.register(Command89::class.java)
        serialization.register(Command91::class.java)
        serialization.register(Command92::class.java)
        serialization.register(Command93::class.java)
        serialization.register(Command94::class.java)
        serialization.register(Command95::class.java)
        serialization.register(Command96::class.java)
        serialization.register(Command97::class.java)
        serialization.register(Command98::class.java)
        serialization.register(Command99::class.java)
        serialization.register(Command100::class.java)
        serialization.register(Command101::class.java)
        serialization.register(Command102::class.java)
        serialization.register(Command103::class.java)
        serialization.register(Command104::class.java)
        serialization.register(Command105::class.java)
        serialization.register(Command106::class.java)
        serialization.register(Command107::class.java)
        serialization.register(Command108::class.java)
        serialization.register(Command109::class.java)
        serialization.register(Command110::class.java)
        serialization.register(Command120::class.java)
        serialization.register(Command130::class.java)
        serialization.register(Command140::class.java)
        serialization.register(Command150::class.java)
        serialization.register(Command160::class.java)
        serialization.register(Command170::class.java)
        serialization.register(Command180::class.java)
        serialization.register(Command190::class.java)
        serialization.register(Command111::class.java)
        serialization.register(Command112::class.java)
        serialization.register(Command113::class.java)
        serialization.register(Command114::class.java)
        serialization.register(Command115::class.java)
        serialization.register(Command116::class.java)
        serialization.register(Command117::class.java)
        serialization.register(Command118::class.java)
        serialization.register(Command119::class.java)
        serialization.register(Command121::class.java)
        serialization.register(Command122::class.java)
        serialization.register(Command123::class.java)
        serialization.register(Command124::class.java)
        serialization.register(Command125::class.java)
        serialization.register(Command126::class.java)
        serialization.register(Command127::class.java)
        serialization.register(Command128::class.java)
        serialization.register(Command129::class.java)
        serialization.register(Command131::class.java)
        serialization.register(Command132::class.java)
        serialization.register(Command133::class.java)
        serialization.register(Command134::class.java)
        serialization.register(Command135::class.java)
        serialization.register(Command136::class.java)
        serialization.register(Command137::class.java)
        serialization.register(Command138::class.java)
        serialization.register(Command139::class.java)
        serialization.register(Command141::class.java)
        serialization.register(Command142::class.java)
        serialization.register(Command143::class.java)
        serialization.register(Command144::class.java)
        serialization.register(Command145::class.java)
        serialization.register(Command146::class.java)
        serialization.register(Command147::class.java)
        serialization.register(Command148::class.java)
        serialization.register(Command149::class.java)
        serialization.register(Command151::class.java)
        serialization.register(Command152::class.java)
        serialization.register(Command153::class.java)
        serialization.register(Command154::class.java)
        serialization.register(Command155::class.java)
        serialization.register(Command156::class.java)
        serialization.register(Command157::class.java)
        serialization.register(Command158::class.java)
        serialization.register(Command159::class.java)
        serialization.register(Command161::class.java)
        serialization.register(Command162::class.java)
        serialization.register(Command163::class.java)
        serialization.register(Command164::class.java)
        serialization.register(Command165::class.java)
        serialization.register(Command166::class.java)
        serialization.register(Command167::class.java)
        serialization.register(Command168::class.java)
        serialization.register(Command169::class.java)
        serialization.register(Command171::class.java)
        serialization.register(Command172::class.java)
        serialization.register(Command173::class.java)
        serialization.register(Command174::class.java)
        serialization.register(Command175::class.java)
        serialization.register(Command176::class.java)
        serialization.register(Command177::class.java)
        serialization.register(Command178::class.java)
        serialization.register(Command179::class.java)
        serialization.register(Command181::class.java)
        serialization.register(Command182::class.java)
        serialization.register(Command183::class.java)
        serialization.register(Command184::class.java)
        serialization.register(Command185::class.java)
        serialization.register(Command186::class.java)
        serialization.register(Command187::class.java)
        serialization.register(Command188::class.java)
        serialization.register(Command189::class.java)
        serialization.register(Command191::class.java)
        serialization.register(Command192::class.java)
        serialization.register(Command193::class.java)
        serialization.register(Command194::class.java)
        serialization.register(Command195::class.java)
        serialization.register(Command196::class.java)
        serialization.register(Command197::class.java)
        serialization.register(Command198::class.java)
        serialization.register(Command199::class.java)
        serialization.register(FinishedCommand::class.java)
    }

    /**
     * In this test the server has two objects in an object space. The client
     * uses the first remote object to get the second remote object.
     */
    fun rmi(config: (Configuration) -> Unit = {}) {
        run {
            val configuration = serverConfig()
            config(configuration)
            register(configuration.serialization)

            val server = Server<Connection>(configuration)
            addEndPoint(server)

            server.onMessage<FinishedCommand> { connection, message ->
                stopEndPoints()
            }

            runBlocking {
                server.bind(false)
            }
        }


        run {
            val configuration = clientConfig()
            config(configuration)
            register(configuration.serialization)

            val client = Client<Connection>(configuration)
            addEndPoint(client)

            client.onConnect { connection ->
                connection.send(FinishedCommand())
            }


            runBlocking {
                client.connect(LOOPBACK)
            }
        }

//        waitForThreads()
        waitForThreads(99999999)
    }

    private class Command1
    private class Command2
    private class Command3
    private class Command4
    private class Command5
    private class Command6
    private class Command7
    private class Command8
    private class Command9
    private class Command10
    private class Command20
    private class Command30
    private class Command40
    private class Command50
    private class Command60
    private class Command70
    private class Command80
    private class Command90
    private class Command11
    private class Command12
    private class Command13
    private class Command14
    private class Command15
    private class Command16
    private class Command17
    private class Command18
    private class Command19
    private class Command21
    private class Command22
    private class Command23
    private class Command24
    private class Command25
    private class Command26
    private class Command27
    private class Command28
    private class Command29
    private class Command31
    private class Command32
    private class Command33
    private class Command34
    private class Command35
    private class Command36
    private class Command37
    private class Command38
    private class Command39
    private class Command41
    private class Command42
    private class Command43
    private class Command44
    private class Command45
    private class Command46
    private class Command47
    private class Command48
    private class Command49
    private class Command51
    private class Command52
    private class Command53
    private class Command54
    private class Command55
    private class Command56
    private class Command57
    private class Command58
    private class Command59
    private class Command61
    private class Command62
    private class Command63
    private class Command64
    private class Command65
    private class Command66
    private class Command67
    private class Command68
    private class Command69
    private class Command71
    private class Command72
    private class Command73
    private class Command74
    private class Command75
    private class Command76
    private class Command77
    private class Command78
    private class Command79
    private class Command81
    private class Command82
    private class Command83
    private class Command84
    private class Command85
    private class Command86
    private class Command87
    private class Command88
    private class Command89
    private class Command91
    private class Command92
    private class Command93
    private class Command94
    private class Command95
    private class Command96
    private class Command97
    private class Command98
    private class Command99
    private class Command100
    private class Command101
    private class Command102
    private class Command103
    private class Command104
    private class Command105
    private class Command106
    private class Command107
    private class Command108
    private class Command109
    private class Command110
    private class Command120
    private class Command130
    private class Command140
    private class Command150
    private class Command160
    private class Command170
    private class Command180
    private class Command190
    private class Command111
    private class Command112
    private class Command113
    private class Command114
    private class Command115
    private class Command116
    private class Command117
    private class Command118
    private class Command119
    private class Command121
    private class Command122
    private class Command123
    private class Command124
    private class Command125
    private class Command126
    private class Command127
    private class Command128
    private class Command129
    private class Command131
    private class Command132
    private class Command133
    private class Command134
    private class Command135
    private class Command136
    private class Command137
    private class Command138
    private class Command139
    private class Command141
    private class Command142
    private class Command143
    private class Command144
    private class Command145
    private class Command146
    private class Command147
    private class Command148
    private class Command149
    private class Command151
    private class Command152
    private class Command153
    private class Command154
    private class Command155
    private class Command156
    private class Command157
    private class Command158
    private class Command159
    private class Command161
    private class Command162
    private class Command163
    private class Command164
    private class Command165
    private class Command166
    private class Command167
    private class Command168
    private class Command169
    private class Command171
    private class Command172
    private class Command173
    private class Command174
    private class Command175
    private class Command176
    private class Command177
    private class Command178
    private class Command179
    private class Command181
    private class Command182
    private class Command183
    private class Command184
    private class Command185
    private class Command186
    private class Command187
    private class Command188
    private class Command189
    private class Command191
    private class Command192
    private class Command193
    private class Command194
    private class Command195
    private class Command196
    private class Command197
    private class Command198
    private class Command199
    private class FinishedCommand
}
