package dorkbox.network.connection.connectionType;

import java.net.InetSocketAddress;

import io.netty.handler.ipfilter.IpFilterRuleType;

/**
 * Implement this interface to create new rules.
 */
public interface IpConnectionTypeRule {
    /**
     * @return This method should return true if remoteAddress is valid according to your criteria. False otherwise.
     */
    boolean matches(InetSocketAddress remoteAddress);

    /**
     * @return This method should return {@link IpFilterRuleType#ACCEPT} if all
     * {@link IpConnectionTypeRule#matches(InetSocketAddress)} for which {@link #matches(InetSocketAddress)}
     * returns true should the accepted. If you want to exclude all of those IP addresses then
     * {@link IpFilterRuleType#REJECT} should be returned.
     */
    ConnectionType ruleType();
}
