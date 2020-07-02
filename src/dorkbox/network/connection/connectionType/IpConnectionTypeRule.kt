package dorkbox.network.connection.connectionType

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
