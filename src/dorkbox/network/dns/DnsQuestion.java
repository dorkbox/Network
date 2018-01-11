package dorkbox.network.dns;

import static io.netty.util.internal.ObjectUtil.checkNotNull;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Locale;

import dorkbox.network.dns.constants.DnsClass;
import dorkbox.network.dns.constants.DnsOpCode;
import dorkbox.network.dns.constants.DnsRecordType;
import dorkbox.network.dns.constants.DnsSection;
import dorkbox.network.dns.constants.Flags;
import dorkbox.network.dns.records.DnsMessage;
import dorkbox.network.dns.records.DnsRecord;
import io.netty.channel.AddressedEnvelope;
import io.netty.util.internal.StringUtil;

/**
 *
 */
public
class DnsQuestion extends DnsMessage implements AddressedEnvelope<DnsQuestion, InetSocketAddress> {
    private InetSocketAddress recipient;
    private final boolean isResolveQuestion;

    /**
     * Creates a new instance.
     *
     * @param isResolveQuestion true if it's a resolve question, which means we ALSO are going to keep resolving names until we get an IP
     * address.
     */
    private
    DnsQuestion(final boolean isResolveQuestion) {
        this.isResolveQuestion = isResolveQuestion;
        this.recipient = null;
    }

    public
    boolean isResolveQuestion() {
        return isResolveQuestion;
    }



    public static
    DnsQuestion newResolveQuestion(final String inetHost, final int type, final boolean isRecursionDesired) {
        return newQuestion(inetHost, type, isRecursionDesired, true);
    }

    public static
    DnsQuestion newQuery(final String inetHost, final int type, final boolean isRecursionDesired) {
        return newQuestion(inetHost, type, isRecursionDesired, false);
    }


    private static
    DnsQuestion newQuestion(final String inetHost, final int type, final boolean isRecursionDesired, boolean isResolveQuestion) {

        // Convert to ASCII which will also check that the length is not too big. Throws null pointer if null.
        // See:
        //   - https://github.com/netty/netty/issues/4937
        //   - https://github.com/netty/netty/issues/4935
        String hostName = hostNameAsciiFix(checkNotNull(inetHost, "hostname"));

        hostName = hostName.toLowerCase(Locale.US);


        // NOTE: have to make sure that the hostname is a FQDN name
        hostName = DnsRecordType.ensureFQDN(type, hostName);

        Name name;
        try {
            name = Name.fromString(hostName);
        } catch (Exception e) {
            // Name.fromString may throw a TextParseException if it fails to parse
            return null;
        }

        try {
            DnsRecord questionRecord = DnsRecord.newRecord(name, type, DnsClass.IN);
            DnsQuestion question = new DnsQuestion(isResolveQuestion);
            question.getHeader()
                    .setOpcode(DnsOpCode.QUERY);

            if (isRecursionDesired) {
                question.getHeader()
                        .setFlag(Flags.RD);
            }
            question.addRecord(questionRecord, DnsSection.QUESTION);

            // keep the question around so we can compare the response to it.
            question.retain();

            return question;
        } catch (Exception e) {
            e.printStackTrace();
        }

        return null;
    }

    public static
    String hostNameAsciiFix(String inetHost) {
        try {
            String hostName = java.net.IDN.toASCII(inetHost);

            // Check for http://bugs.java.com/bugdatabase/view_bug.do?bug_id=6894622
            if (StringUtil.endsWith(inetHost, '.') && !StringUtil.endsWith(hostName, '.')) {
                return hostName + '.';
            }

            return hostName;
        } catch (Exception e) {
            // java.net.IDN.toASCII(...) may throw an IllegalArgumentException if it fails to parse the hostname
        }

        return null;
    }

    public
    void init(int id, InetSocketAddress recipient) {
        getHeader().setID(id);
        this.recipient = recipient;
    }

    @Override
    public
    DnsQuestion content() {
        return this;
    }

    @Override
    public
    InetSocketAddress sender() {
        return null;
    }

    @Override
    public
    InetSocketAddress recipient() {
        return recipient;
    }



    @Override
    public
    DnsQuestion touch() {
        return (DnsQuestion) super.touch();
    }

    @Override
    public
    DnsQuestion touch(Object hint) {
        return (DnsQuestion) super.touch(hint);
    }

    @Override
    public
    DnsQuestion retain() {
        return (DnsQuestion) super.retain();
    }

    @Override
    public
    DnsQuestion retain(int increment) {
        return (DnsQuestion) super.retain(increment);
    }

    @Override
    public
    boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }

        if (!super.equals(obj)) {
            return false;
        }

        if (!(obj instanceof AddressedEnvelope)) {
            return false;
        }

        @SuppressWarnings("unchecked")
        final AddressedEnvelope<?, SocketAddress> that = (AddressedEnvelope<?, SocketAddress>) obj;
        if (sender() == null) {
            if (that.sender() != null) {
                return false;
            }
        }
        else if (!sender().equals(that.sender())) {
            return false;
        }

        if (recipient() == null) {
            if (that.recipient() != null) {
                return false;
            }
        }
        else if (!recipient().equals(that.recipient())) {
            return false;
        }

        return true;
    }

    @Override
    public
    int hashCode() {
        int hashCode = super.hashCode();
        if (sender() != null) {
            hashCode = hashCode * 31 + sender().hashCode();
        }
        if (recipient() != null) {
            hashCode = hashCode * 31 + recipient().hashCode();
        }
        return hashCode;
    }
}

