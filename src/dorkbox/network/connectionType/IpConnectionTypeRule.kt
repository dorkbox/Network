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
package dorkbox.network.connectionType

import java.net.InetSocketAddress

/**
 * Implement this interface to create new rules.
 */
interface IpConnectionTypeRule {
    /**
     * @return This method should return true if remoteAddress is valid according to your criteria. False otherwise.
     */
    fun matches(remoteAddress: InetSocketAddress): Boolean

    /**
     * @return This method should return [IpFilterRuleType.ACCEPT] if all
     * [IpConnectionTypeRule.matches] for which [.matches]
     * returns true should the accepted. If you want to exclude all of those IP addresses then
     * [IpFilterRuleType.REJECT] should be returned.
     */
    fun ruleType(): ConnectionProperties
}
