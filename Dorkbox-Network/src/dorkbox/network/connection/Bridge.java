package dorkbox.network.connection;

import dorkbox.network.connection.wrapper.ChannelWrapper;

public class Bridge {

    final ChannelWrapper channelWrapper;
    final ISessionManager sessionManager;

    Bridge(ChannelWrapper channelWrapper, ISessionManager sessionManager) {
        this.channelWrapper = channelWrapper;
        this.sessionManager = sessionManager;
    }
}
