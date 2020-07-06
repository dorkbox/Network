package dorkbox.network.other;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dorkbox.util.OS;

/**
 * Network Utilities. MAC, IP, NameSpace, etc
 */
@SuppressWarnings("unused")
public
class NetworkUtil {
    private static final Logger logger = LoggerFactory.getLogger(NetworkUtil.class.getSimpleName());


    public static final String LOCALHOST = "localhost";
    public static final String LOOPBACK = "127.0.0.1";

    public static final String WILDCARD_IPV4;
    static {
        if (OS.isWindows()) {
            // silly windows can't work with 0.0.0.0, BUT we can't use loopback because we might need to reach this machine from a different host
            String IP = LOOPBACK;
            try {
                Socket socket = new Socket();
                socket.connect(new InetSocketAddress("1.1.1.1", 80));
                IP = socket.getLocalAddress().getHostAddress();
            } catch (Exception e) {
                logger.error("Unable to determine outbound traffic local address. Using loopback instead.", e);
            }

            WILDCARD_IPV4 = IP;
        } else {
            // NetworkUtil.EXTERNAL_IPV4
            WILDCARD_IPV4 = "0.0.0.0";
        }
    }


    public static
    class MAC {
        public static final int MAC_ADDRESS_LENGTH = 6;
        private static final Random random = new Random();
        private static Set<String> fakeMacsInUse = new HashSet<>();
        private static final Pattern MAC_ADDRESS_PATTERN = Pattern.compile("^([a-fA-F0-9]{2}[:\\\\.-]?){5}[a-fA-F0-9]{2}$");

        public
        enum MacDelimiter {
            COLON(":"), PERIOD("."), SPACE(" ");

            private final String delimiter;

            MacDelimiter(String delimiter) {
                this.delimiter = delimiter;
            }

            String getDelimiter() {
                return delimiter;
            }
        }

        static {
            synchronized (fakeMacsInUse) {
                // String mac = getMacAddress(Args_Interfaces.BRIDGE_IP.getAsString(), null);

                // fakeMacsInUse.add(mac);
            }
        }

        public static
        String getMacAddress(String ip, Logger logger) {
            try {
                byte[] mac = getMacAddressByte(ip, logger);
                if (mac == null) {
                    if (logger != null) {
                        logger.error("Unable to get MAC address for IP '{}'", ip);
                    }
                    return "";
                }

                StringBuilder s = new StringBuilder(18);
                for (byte b : mac) {
                    if (s.length() > 0) {
                        s.append(':');
                    }
                    s.append(String.format("%02x", b));
                }

                return s.toString();
            } catch (Exception e) {
                if (logger != null) {
                    logger.error("Unable to get MAC address for IP '{}'", ip, e);
                }
                else {
                    e.printStackTrace();
                }
            }

            if (logger != null) {
                logger.error("Unable to get MAC address for IP '{}'", ip);
            }
            return "";
        }

        public static
        byte[] getMacAddressByte(String ip, Logger logger) {
            try {
                InetAddress addr = InetAddress.getByName(ip);
                NetworkInterface networkInterface = NetworkInterface.getByInetAddress(addr);
                if (networkInterface == null) {
                    if (logger != null) {
                        logger.error("Unable to get MAC address for IP '{}'", ip);
                    }
                    return null;
                }

                byte[] mac = networkInterface.getHardwareAddress();
                if (mac == null) {
                    if (logger != null) {
                        logger.error("Unable to get MAC address for IP '{}'", ip);
                    }
                    return null;
                }

                return mac;
            } catch (Exception e) {
                if (logger != null) {
                    logger.error("Unable to get MAC address for IP '{}'", ip, e);
                }
                else {
                    e.printStackTrace();
                }
            }

            return null;
        }

        /**
         * will also make sure that this mac doesn't already exist
         *
         * @return a unique MAC that can be used for VPN devices + setup. This is a LOWERCASE string!
         */
        public static
        String getFakeMac() {
            synchronized (fakeMacsInUse) {
                String mac = fakeVpnMac();

                // gotta make sure it doesn't already exist
                while (fakeMacsInUse.contains(mac)) {
                    mac = fakeVpnMac();
                }

                fakeMacsInUse.add(mac);

                return mac;
            }
        }

        /**
         * will also make sure that this mac doesn't already exist
         *
         * @return a unique MAC that can be used for VPN devices + setup. This is a LOWERCASE string!
         */
        public static
        String getFakeDockerMac() {
            synchronized (fakeMacsInUse) {
                String mac = fakeDockerMac();

                // gotta make sure it doesn't already exist
                while (fakeMacsInUse.contains(mac)) {
                    mac = fakeDockerMac();
                }

                fakeMacsInUse.add(mac);

                return mac;
            }
        }

        /**
         * Removes a mac that was in use, to free it's use later on
         */
        public static
        void freeFakeMac(String fakeMac) {
            synchronized (fakeMacsInUse) {
                fakeMacsInUse.remove(fakeMac);
            }
        }

        /**
         * Removes a mac that was in use, to free it's use later on
         */
        public static
        void freeFakeMac(Collection<String> fakeMacs) {
            synchronized (fakeMacsInUse) {
                fakeMacsInUse.removeAll(fakeMacs);
            }
        }

        /**
         * http://serverfault.com/questions/40712/what-range-of-mac-addresses-can-i-safely-use-for-my-virtual-machines
         *
         * @return a mac that is safe to use for fake interfaces. THIS IS LOWERCASE!
         */
        public static
        String fakeVpnMac() {
            String vpnID = randHex();
            while (vpnID.equals("d0")) {
                // this is what is used for docker. NEVER assign it to a VPN mac!!
                vpnID = randHex();
            }
            return "02:" + vpnID + ":" + randHex() + ":" + randHex() + ":" + randHex() + ":" + randHex();
        }

        /**
         * http://serverfault.com/questions/40712/what-range-of-mac-addresses-can-i-safely-use-for-my-virtual-machines
         *
         * @return a mac that is safe to use for fake interfaces. THIS IS LOWERCASE!
         */
        public static
        String fakeDockerMac() {
            return "02:" + "d0" + ":" + randHex() + ":" + randHex() + ":" + randHex() + ":" + randHex();
        }

        public static
        String randHex() {
            final int i = random.nextInt(255);
            if (i < 16) {
                return "0" + Integer.toHexString(i);
            }
            else {
                return Integer.toHexString(i);
            }
        }

        /**
         * will also make sure that this mac doesn't already exist
         *
         * @return a unique MAC that can be used for VPN devices + setup
         */
        public static
        String getFakeVpnMac(String vpnKeyDirForExistingVpnMacs) {
            synchronized (fakeMacsInUse) {
                String mac = fakeVpnMac();

                // gotta make sure it doesn't already exist
                while (fakeMacsInUse.contains(mac) || new File(vpnKeyDirForExistingVpnMacs, mac + ".crt").exists()) {
                    mac = fakeVpnMac();
                }

                fakeMacsInUse.add(mac);

                return mac;
            }
        }


        /**
         * Converts a long into a properly formatted lower-case string
         */
        public static
        String toStringLowerCase(final long mac) {
            // we only use the right-most 6 bytes.
            // byte[] macBytes = ByteUtils.fromLong(mac);

            // mac should have 6 bytes.
            final StringBuilder buf = new StringBuilder();
            // for (int i = 2; i < macBytes.length; i++) {
            //     final byte b = macBytes[i];
            //     if (buf.length() != 0) {
            //         buf.append(':');
            //     }
            //     if (b >= 0 && b < 16) {
            //         buf.append('0');
            //     }
            //     buf.append(Integer.toHexString((b < 0) ? b + 256 : b));
            //
            // }

            return buf.toString();
        }

        public static
        void writeStringLowerCase(final long mac, final Writer writer) throws Exception {
            // we only use the right-most 6 bytes.
            // byte[] macBytes = ByteUtils.fromLong(mac);
            //
            // // mac should have 6 bytes.
            // int bytesWritten = 0;
            // for (int i = 2; i < macBytes.length; i++) {
            //     final byte b = macBytes[i];
            //     if (bytesWritten != 0) {
            //         writer.write(':');
            //         bytesWritten++;
            //     }
            //     if (b >= 0 && b < 16) {
            //         writer.write('0');
            //     }
            //     writer.write(Integer.toHexString((b < 0) ? b + 256 : b));
            //     bytesWritten += 2;
            // }
        }

        public static
        String toStringLowerCase(final byte[] mac) {
            // mac should have 6 bytes.
            final StringBuilder buf = new StringBuilder();
            for (byte b : mac) {
                if (buf.length() != 0) {
                    buf.append(':');
                }
                if (b >= 0 && b < 16) {
                    buf.append('0');
                }
                buf.append(Integer.toHexString((b < 0) ? b + 256 : b));
            }

            return buf.toString();
        }

        public static
        String toStringUpperCase(final byte[] mac) {
            final StringBuilder buf = new StringBuilder();
            for (byte b : mac) {
                if (buf.length() != 0) {
                    buf.append(':');
                }
                if (b >= 0 && b < 16) {
                    buf.append('0');
                }
                buf.append(Integer.toHexString((b < 0) ? b + 256 : b)
                                  .toUpperCase());
            }

            return buf.toString();
        }

        public static
        byte[] toBytes(final String mac) {
            String s = mac.replaceAll(":", "");
            return new BigInteger(s, 16).toByteArray();
        }

        public static
        long toLong(final byte[] mac) {
            return ((long)mac[5] & 0xff)
                    + (((long)mac[4] & 0xff) << 8)
                    + (((long)mac[3] & 0xff) << 16)
                    + (((long)mac[2] & 0xff) << 24)
                    + (((long)mac[1] & 0xff) << 32)
                    + (((long)mac[0] & 0xff) << 40);
        }

        public static
        long toLong(final String mac) {
            return toLong(mac, MacDelimiter.COLON);
        }

        public static
        long toLong(final String mac, MacDelimiter delimiter) {
            byte[] addressInBytes = new byte[MAC_ADDRESS_LENGTH];

            try {
                String[] elements;
                if (delimiter != null) {
                    elements = mac.split(delimiter.getDelimiter());
                }
                else {
                    elements = new String[MAC_ADDRESS_LENGTH];
                    int index = 0;
                    int substringPos = 0;
                    while (index < MAC_ADDRESS_LENGTH) {
                        elements[index] = mac.substring(substringPos, substringPos+2);

                        index++;
                        substringPos += 2;
                    }
                }

                for (int i = 0; i < MAC_ADDRESS_LENGTH; i++) {
                    String element = elements[i];
                    addressInBytes[i] = (byte) Integer.parseInt(element, 16);
                }
            } catch (Exception e) {
                logger.error("Error parsing MAC address '{}'", mac, e);
            }

            return toLong(addressInBytes);
        }

        public static
        boolean isValidMacAddress(String macAsString) {
            if(macAsString == null) {
                return false;
            }
            if(macAsString.isEmpty()) {
                return false;
            }

            // Check whether mac is separated by a colon, period, space, or nothing at all.
            // Must standardize to a colon.
            String normalizedMac = macAsString;
            if(normalizedMac.split(":").length != 6) {
                // Does not already use colons, must modify the mac to use colons.
                if (normalizedMac.contains(".")) {
                    // Period notation
                    normalizedMac = normalizedMac.replace(".", ":");
                }
                else if (normalizedMac.contains(" ")) {
                    // Space notation
                    normalizedMac = normalizedMac.replace(" ", ":");
                }
                else {
                    // No delimiter, manually add colons.
                    normalizedMac = "";
                    int index = 0;
                    int substringPos = 0;

                    // We ensure that the substring function will not exceed the length of the given string if the string is not at least
                    //(MAC_ADDRESS_LENGTH * 2) characters long.
                    while (index < MAC_ADDRESS_LENGTH && macAsString.length() >= substringPos+2) {
                        // Reconstruct the string, adding colons.
                        if(index != MAC_ADDRESS_LENGTH-1) {
                            normalizedMac = normalizedMac.concat(macAsString.substring(substringPos, substringPos+2).concat(":"));
                        }
                        else {
                            normalizedMac = normalizedMac.concat(macAsString.substring(substringPos, substringPos+2));
                        }

                        index++;
                        substringPos += 2;
                    }
                }
            }

            Matcher matcher = MAC_ADDRESS_PATTERN.matcher(normalizedMac);
            return matcher.matches();
        }

        /**
         * Returns the type of delmiter based on how a mac address is constructed. Returns null if no delimiter.
         * @return a MacDelimiter if there is one, or null if the mac address is one long string.
         */
        public static
        MacDelimiter getMacDelimiter(String macAsString) {
            if (macAsString.contains(":")) {
                return MacDelimiter.COLON;
            }
            else if (macAsString.contains(".")) {
                return MacDelimiter.PERIOD;
            }
            else if (macAsString.contains(" ")) {
                return MacDelimiter.SPACE;
            }

            return null;
        }

        public static
        void assign(final String interfaceName, final String macAddress) {
            // ShellExecutor.Companion.run("/sbin/ifconfig", interfaceName + " hw ether " + macAddress);
        }
    }


    public static
    class NameSpace {
        private static Map<String, Map<String, String>> nameSpaceToIifToIp = new HashMap<>();


        public static
        class Route {
            public static
            void flush(String nameSpace) {
                run(nameSpace, "/sbin/ip route flush cache");
            }
        }

        public static
        void add(String nameSpace) {
            // ShellExecutor.Companion.run("/sbin/ip", "netns add " + nameSpace);
        }

        public static
        void delete(String nameSpace) {
            // ShellExecutor.Companion.run("/sbin/ip", "netns del " + nameSpace);
        }

        public static
        void dhcpStart(String nameSpace, String id, String interfaceName) {
            dhcpStop(nameSpace, id, interfaceName);

            String dhcpPidFile = "/var/run/dhclient-" + id + ".pid";

            run(nameSpace, "/sbin/dhclient", "-pf", dhcpPidFile, interfaceName);
        }

        public static
        void dhcpStop(final String nameSpace, final String id, final String interfaceName) {
            String dhcpPidFile = "/var/run/dhclient-" + id + ".pid";

            // close the dhclient if it was already running (based on pid file), and delete the pid file
            run(nameSpace, "/sbin/dhclient", "-r -pf", dhcpPidFile, interfaceName);

            // short break
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

            new File(dhcpPidFile).delete();
        }

        public static
        void run(String nameSpace, String... args) {
            List<String> newArgs = new ArrayList<>(args.length + 3);
            newArgs.add("netns");
            newArgs.add("exec");
            newArgs.add(nameSpace);
            newArgs.addAll(Arrays.asList(args));

            // ShellExecutor.Companion.run("/sbin/ip", newArgs.toArray(new String[0]));
        }

        /**
         * On client disconnect, it will get the IP address for the interface from the cache, instead of from `ifconfig` (since the
         * interface
         * is down at this point)
         */
        public static
        String getIpFromIf(final String nameSpace, final String interfaceName, boolean isOnClientConnect) {
            if (isOnClientConnect) {
                String ifaceInfo = runWithOutput(nameSpace, "/sbin/ifconfig", interfaceName);

                String str = "inet addr:";
                int index = ifaceInfo.indexOf(str);
                if (index > -1) {
                    index += str.length();
                    String possibleAddr = ifaceInfo.substring(index, ifaceInfo.indexOf(" ", index));

                    logger.debug("Found on '{}' possible addr '{}' : ADD", interfaceName, possibleAddr);

                    synchronized (nameSpaceToIifToIp) {
                        Map<String, String> ifToIp = nameSpaceToIifToIp.get(nameSpace);
                        //noinspection Java8MapApi
                        if (ifToIp == null) {
                            ifToIp = new HashMap<>();
                            nameSpaceToIifToIp.put(nameSpace, ifToIp);
                        }

                        ifToIp.put(interfaceName, possibleAddr);
                    }

                    return possibleAddr;
                }
            }
            else {
                String possibleAddr = "";
                synchronized (nameSpaceToIifToIp) {
                    Map<String, String> ifToIp = nameSpaceToIifToIp.get(nameSpace);
                    if (ifToIp != null) {
                        possibleAddr = ifToIp.remove(interfaceName);
                    }
                }

                logger.debug("Found on '{}' possible addr '{}' : REMOVE", interfaceName, possibleAddr);
                if (possibleAddr != null) {
                    return possibleAddr;
                }
            }

            return "";
        }

        public static
        String runWithOutput(String nameSpace, String... args) {
            // List<String> newArgs = new ArrayList<>(args.length + 3);
            // newArgs.add("netns");
            // newArgs.add("exec");
            // newArgs.add(nameSpace);
            // newArgs.addAll(Arrays.asList(args));
            //
            // return ShellExecutor.Companion.runWithOutput("/sbin/ip", newArgs.toArray(new String[0]));
            throw new RuntimeException("NOT IMPL.");
        }

        public static
        void addLoopback(final String nameSpace) {
            run(nameSpace, "/sbin/ip link set dev lo up");
            run(nameSpace, "/sbin/ip addr add 127.0.0.1 dev lo");
        }
    }


    public static
    class VirtualEth {
        public static
        void add(String host, String guest) {
            // ShellExecutor.Companion.run("/sbin/ip", "link add name " + host + " type veth peer name " + guest);
        }

        public static
        void delete(String host) {
            // ShellExecutor.Companion.run("/sbin/ip", "link del " + host);
        }

        public static
        void assignNameSpace(final String nameSpace, final String guest) {
            // ShellExecutor.Companion.run("/sbin/ip", "link set " + guest + " netns " + nameSpace);
        }
    }


    public static
    class IP {
        private static final Map<String, String> ifToIp = new HashMap<>();

        public static
        void dhcpStart(String nameSpace, String id, String interfaceName) {
            dhcpStop(nameSpace, id, interfaceName);

            String dhcpPidFile = "/var/run/dhclient-" + id + ".pid";

            // ShellExecutor.Companion.run("/sbin/dhclient", "-pf", dhcpPidFile, interfaceName);
        }

        public static
        void dhcpStop(final String nameSpace, final String id, final String interfaceName) {
            String dhcpPidFile = "/var/run/dhclient-" + id + ".pid";

            // close the dhclient if it was already running (based on pid file), and delete the pid file
            // ShellExecutor.Companion.run("/sbin/dhclient", "-r -pf", dhcpPidFile, interfaceName);

            // short break
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

            new File(dhcpPidFile).delete();
        }

        /**
         * On disconnect, it will get the IP address for the interface from the cache, instead of from `ifconfig` (since the interface
         * is down at this point)
         */
        @SuppressWarnings("Duplicates")
        public static
        String getIpFromIf(final String interfaceName, boolean isOnClientConnect) {
            if (isOnClientConnect) {
                // String ifaceInfo = ShellExecutor.Companion.runWithOutput("/sbin/ifconfig", interfaceName);
                //
                // String str = "inet addr:";
                // int index = ifaceInfo.indexOf(str);
                // if (index > -1) {
                //     index += str.length();
                //     String possibleAddr = ifaceInfo.substring(index, ifaceInfo.indexOf(" ", index));
                //
                //     logger.debug("Found on '{}' possible addr '{}' : ADD", interfaceName, possibleAddr);
                //     synchronized (ifToIp) {
                //         ifToIp.put(interfaceName, possibleAddr);
                //     }
                //     return possibleAddr;
                // }
            }
            else {
                String possibleAddr;
                synchronized (ifToIp) {
                    possibleAddr = ifToIp.remove(interfaceName);
                }

                logger.debug("Found on '{}' possible addr '{}' : REMOVE", interfaceName, possibleAddr);
                if (possibleAddr != null) {
                    return possibleAddr;
                }
            }

            return "";
        }

        public static
        void down(final String interfaceName) {
            // ShellExecutor.Companion.run("/sbin/ip", "link set dev", interfaceName, "down");
        }

        public static
        void up(final String interfaceName) {
            // ShellExecutor.Companion.run("/sbin/ip", "link set dev", interfaceName, "up");
        }

        public static
        void setMac(final String interfaceName, String interfaceMac) {
            // ShellExecutor.Companion.run("/sbin/ip", "link set dev", interfaceName, "address", interfaceMac);
        }

        public static
        void assignCIDR(final String interfaceName, final int cidr) {
            // ShellExecutor.Companion.run("/sbin/ifconfig", interfaceName + " 0.0.0.0/" + cidr + " up");
        }

        public static
        void addLoopback() {
            // ShellExecutor.Companion.run("/sbin/ip", "link set dev lo up");
            // ShellExecutor.Companion.run("/sbin/ip", "addr add 127.0.0.1 dev lo");
        }

        public static
        int toInt(final byte[] ipBytes) {
            int val = 0;

            for (int i = 0; i < ipBytes.length; i++) {
                val <<= 8;
                val |= ipBytes[i] & 0xFF;
            }
            return val;
        }

        public static
        String toString(final int ipAddress) {
            StringBuilder ipString = new StringBuilder(15);

            ipString.append(((ipAddress >> 24) & 0x000000FF));
            ipString.append('.');
            ipString.append(((ipAddress >> 16) & 0x000000FF));
            ipString.append('.');
            ipString.append(((ipAddress >> 8) & 0x000000FF));
            ipString.append('.');
            ipString.append((ipAddress & 0x000000FF));

            return ipString.toString();
        }

        public static
        void writeString(final int ipAddress, final Writer writer) throws Exception {
            writer.write(Integer.toString((ipAddress >> 24) & 0x000000FF));
            writer.write('.');
            writer.write(Integer.toString((ipAddress >> 16) & 0x000000FF));
            writer.write('.');
            writer.write(Integer.toString((ipAddress >> 8) & 0x000000FF));
            writer.write('.');
            writer.write(Integer.toString(ipAddress & 0x000000FF));
        }

        public static
        String toString(final long ipAddress) {
            StringBuilder ipString = new StringBuilder(15);

            ipString.append(((ipAddress >> 24) & 0x000000FF));
            ipString.append('.');
            ipString.append(((ipAddress >> 16) & 0x000000FF));
            ipString.append('.');
            ipString.append(((ipAddress >> 8) & 0x000000FF));
            ipString.append('.');
            ipString.append((ipAddress & 0x000000FF));

            return ipString.toString();
        }

        public static
        byte[] toBytes(int bytes) {
            return new byte[] {(byte) ((bytes >>> 24) & 0xFF),
                               (byte) ((bytes >>> 16) & 0xFF),
                               (byte) ((bytes >>> 8) & 0xFF),
                               (byte) ((bytes) & 0xFF)};
        }

        public static
        byte[] toBytes(final String ipAsString) {
            byte[] address = new byte[4];

            long tmp = 0;
            int current = 0;

            int len = ipAsString.length();
            for (int i = 0; i < len; i++) {
                char c = ipAsString.charAt(i);

                if (c == '.') {
                    address[current++] = (byte) (tmp & 0xff);
                    tmp = 0;
                } else {
                    int digit = Character.digit(c, 10);
                    tmp *= 10;
                    tmp += digit;
                }
            }

            return address;
        }

        public static
        int toInt(final String ipAsString) {
            int address = 0;

            try {
                byte[] bytes = toBytes(ipAsString);
                address |= bytes[0] << 24;
                address |= bytes[1] << 16;
                address |= bytes[2] << 8;
                address |= bytes[3];
            } catch (Exception e) {
                logger.error("Error processing IP address '{}'", ipAsString, e);
            }

            return address;
        }

        private static final int[] CIDR2MASK = new int[] {0x00000000, 0x80000000, 0xC0000000, 0xE0000000, 0xF0000000, 0xF8000000, 0xFC000000,
                                                          0xFE000000, 0xFF000000, 0xFF800000, 0xFFC00000, 0xFFE00000, 0xFFF00000, 0xFFF80000,
                                                          0xFFFC0000, 0xFFFE0000, 0xFFFF0000, 0xFFFF8000, 0xFFFFC000, 0xFFFFE000, 0xFFFFF000,
                                                          0xFFFFF800, 0xFFFFFC00, 0xFFFFFE00, 0xFFFFFF00, 0xFFFFFF80, 0xFFFFFFC0, 0xFFFFFFE0,
                                                          0xFFFFFFF0, 0xFFFFFFF8, 0xFFFFFFFC, 0xFFFFFFFE, 0xFFFFFFFF};

        public static
        List<String> range2Cidr(final String startIp, final String endIp) {
            long start = NetworkUtil.IP.toInt(startIp);
            long end = NetworkUtil.IP.toInt(endIp);

            List<String> pairs = new ArrayList<>();
            while (end >= start) {
                byte maxsize = (byte) 32;
                while (maxsize > 0) {
                    long mask = CIDR2MASK[maxsize - 1];
                    long maskedBase = start & mask;

                    if (maskedBase != start) {
                        break;
                    }

                    maxsize--;
                }

                double x = Math.log(end - start + 1) / Math.log(2);

                //noinspection NumericCastThatLosesPrecision
                byte maxDiff = (byte) (32 - Math.floor(x));
                if (maxsize < maxDiff) {
                    maxsize = maxDiff;
                }
                String ip = NetworkUtil.IP.toString(start);
                pairs.add(ip + "/" + maxsize);
                start += (long) Math.pow(2, 32 - maxsize);
            }

            return pairs;
        }

          /*
     ^                                # START OF STRING
       (?=\d+\.\d+\.\d+\.\d+(\/d+)?$)   # LOOKAHEAD, require this format: number.number.number.number (optional /number) END OF STRING

       (?:                              # Start non-capture group (number 0-255 + optional dot)
         (?:                              # Start non-capture group (number 0-255)
           25[0-5]                          # 250-255
           |                                # OR
           2[0-4][0-9]                      # 200-249
           |                                # OR
           1[0-9]{2}                        # 100-199
           |                                # OR
           [1-9][0-9]                       # 10-99
           |                                # OR
           [0-9]                            # 0-9
         )                                # End non-capture group
         \.?                              # Optional dot (enforced in correct positions by lookahead)
       ){4}                             # End non-capture group (number + optional dot), repeat 4 times

       (?:                              # Start OPTIONAL non-capture group (/ + number 0-32)
         \/                               # /
         [0-9]                            # 0-9
         |                                # OR
         1[0-9]                           # 10-19
         |                                #  OR
         2[0-9]                           # 20-29
         |                                #  OR
         3[0-2]                           # 30-32
       )?                               # End OPTIONAL non-capture group (/ + number)
     $                                # END OF STRING
     */

          // these MUST stay private. use `isValidIP` for things instead
        private static final String IP_ADDRESS_ONLY_PATTERN_STRING =
                  "^(?=\\d+\\.\\d+\\.\\d+\\.\\d+$)" +
                  "(?:(?:25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9][0-9]|[0-9])\\.?){4}$";

        private static final String IP_ADDRESS_CIDR_PATTERN_STRING =
                "^(?=\\d+\\.\\d+\\.\\d+\\.\\d+(\\/\\d+)?$)" +
                "(?:(?:25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9][0-9]|[0-9])\\.?){4}" +
                "(?:\\/(?:[0-9]|1[0-9]|2[0-9]|3[0-2]))?$";

        private static final Pattern IP_ADDRESS_ONLY_PATTERN = Pattern.compile(IP_ADDRESS_ONLY_PATTERN_STRING, Pattern.CASE_INSENSITIVE);
        private static final Pattern IP_ADDRESS_CIDR_PATTERN = Pattern.compile(IP_ADDRESS_CIDR_PATTERN_STRING, Pattern.CASE_INSENSITIVE);


        /**
         * Determine whether a given string is a valid IP address. Accepts 1.2.3.4
         *
         * @param ipAsString The string that will be checked.
         *
         * @return return true if the string is a valid IP address, false if it is not.
         */
        public static
        boolean isValidIp(final String ipAsString) {
            if (ipAsString == null || ipAsString.isEmpty()) {
                return false;
            }

            Matcher m = IP_ADDRESS_ONLY_PATTERN.matcher(ipAsString);
            return m.matches();
        }

        /**
         * Determine whether a given string is a valid CIDR IP address. Accepts 1.2.3.4 and 1.2.3.4/24
         *
         * @param ipAsString The string that will be checked.
         *
         * @return return true if the string is a valid IP address, false if it is not.
         */
        public static
        boolean isValidCidrIp(final String ipAsString) {
            if (ipAsString == null || ipAsString.isEmpty()) {
                return false;
            }

            Matcher m = IP_ADDRESS_CIDR_PATTERN.matcher(ipAsString);
            return m.matches();
        }

        /* Mask to convert unsigned int to a long (i.e. keep 32 bits) */
        private static final long UNSIGNED_INT_MASK = 0x0FFFFFFFFL;

        /**
         * Check if the IP address is in the range of a specific IP/CIDR
         *
         * a prefix of 0 will ALWAYS return true
         *
         * @param address the address to check
         * @param networkAddress the network address that will have the other address checked against
         * @param networkPrefix 0-32 the network prefix (subnet) to use for the network address
         *
         * @return true if it is in range
         */
        public static boolean isInRange(int address, int networkAddress, int networkPrefix) {
            // System.err.println(" ip: " + IP.toString(address));
            // System.err.println(" networkAddress: " + IP.toString(networkAddress));
            // System.err.println(" networkSubnetPrefix: " + networkPrefix);

            if (networkPrefix == 0) {
                // a prefix of 0 means it is always true (even though the broadcast address is '-1'). So we short-cut it here
                return true;
            }

            //noinspection PointlessBitwiseExpression
            int netmask = ~((1 << (32 - networkPrefix)) - 1);

            // Calculate base network address
            long network = (networkAddress & netmask) & UNSIGNED_INT_MASK;
            // System.err.println("   network " + IP.toString(network));

            //  Calculate broadcast address
            long broadcast = network | ~(netmask) & UNSIGNED_INT_MASK;
            // System.err.println("   broadcast " + IP.toString(broadcast));

            long addressLong = address & UNSIGNED_INT_MASK;

            return network <= addressLong && addressLong <= broadcast;
        }

        public static void main(String args[]) {
            // try {
            //     String secret = "e8b7b40e031300000000da247441226a";
            //     String ivString = "987185c4436764b6e27a72f200da2201.e";
            //     String cipherText = "U2FsdGVkX1+dXqCQz3g/Fltl5Jls5ayO0TynHaGM72PHXSxgBf5F5cwljMJsl4jf";
            //
            //     byte[] cipherData = Base64.decode(cipherText);
            //     byte[] saltData = Arrays.copyOfRange(cipherData, 8, 16);
            //
            //     byte[] key = secret.getBytes();
            //     byte[] iv = ivString.getBytes();
            //
            //     byte[] decryptAes = CryptoAES.decrypt(new GCMBlockCipher(new AESFastEngine()), key, iv, cipherData, logger);
            //
            //     System.out.println(new String(decryptAes, StandardCharsets.UTF_8));
            // } catch (Exception e) {
            //     e.printStackTrace();
            // }
            // System.out.println(IP.toInt("10.106.6.119"));
            System.out.println(MAC.toLong("c8:9c:dc:3d:86:b4"));

            // System.err.println("in range true " + IP.isInRange(IP.toInt("10.10.10.5"), IP.toInt("10.10.10.10"), 24));
            // System.err.println("in range true " + IP.isInRange(IP.toInt("10.0.0.5"), IP.toInt("10.10.10.10"), 8));
            // System.err.println("in range false " + IP.isInRange(IP.toInt("11.0.0.5"), IP.toInt("10.10.10.10"), 8));
            // System.err.println("in range true " + IP.isInRange(IP.toInt("11.0.0.5"), IP.toInt("10.10.10.10"), 1));
            // System.err.println("in range true " + IP.isInRange(IP.toInt("11.0.0.5"), IP.toInt("10.10.10.10"), 0));
            // System.err.println("in range false " + IP.isInRange(IP.toInt("11.0.0.5"), IP.toInt("10.10.10.10"), 32));
            // System.err.println("in range true " + IP.isInRange(IP.toInt("10.10.10.10"), IP.toInt("10.10.10.10"), 32));
            // System.err.println("in range true " + IP.isInRange(IP.toInt("10.10.10.10"), IP.toInt("10.10.10.10"), 31));
            // System.err.println("in range true " + IP.isInRange(IP.toInt("10.10.10.10"), IP.toInt("10.10.10.10"), 30));
            // System.err.println("in range true " + IP.isInRange(IP.toInt("192.168.42.14"), IP.toInt("192.168.0.0"), 16));
            // System.err.println("in range true " + IP.isInRange(IP.toInt("192.168.0.0"), IP.toInt("192.168.0.0"), 16));
        }
    }


    public static
    class ARP {
        // Now setup ARP Proxy for this interface (so ARP requests are answered correctly)
        public static
        void proxyAdd(final String interfaceName) {
            File file = new File("/proc/sys/net/ipv4/conf/" + interfaceName + "/proxy_arp");

            // String read = FileUtil.INSTANCE.read(file);
            // if (read == null || !read.contains("1")) {
            //     FileUtil.INSTANCE.append(file, "1");
            // }
        }

        public static
        void proxyDel(final String interfaceName) {
            File file = new File("/proc/sys/net/ipv4/conf/" + interfaceName + "/proxy_arp");

            file.delete();
        }

        public static
        void add(final String interfaceName, final String targetIpAddress, final String targetMacAddress) {
            // have to make sure that the host interface will answer ARP for the "target" ip (normally, it will not for veth interfaces)
            // ShellExecutor.Companion.run("/usr/sbin/arp", "-i", interfaceName, "-s", targetIpAddress, targetMacAddress);
        }
    }


    public static
    class Route {
        public static
        void flush() {
            // ShellExecutor.Companion.run("/sbin/ip", "route flush cache");
        }

        public static
        void add(final String targetIpAndCidr, final String hostIP, final String hostInterface) {
            // ShellExecutor.Companion.run("/sbin/ip", "route add", targetIpAndCidr + " via " + hostIP + " dev " + hostInterface);
        }
    }


    public static
    class IfConfig {
        public static
        void assignMac(final String interfaceName, final String interfaceMac) {
            // ShellExecutor.Companion.run("/sbin/ifconfig", interfaceName + " hw ether " + interfaceMac);
        }

        public static
        void up(final String interfaceName, final String interfaceCIDR) {
            up(interfaceName, "0.0.0.0", interfaceCIDR);
        }

        public static
        void up(final String interfaceName, final String interfaceIP, final String interfaceCIDR) {
            // ShellExecutor.Companion.run("/sbin/ifconfig", interfaceName + " " + interfaceIP + "/" + interfaceCIDR + " up");
        }
    }

    public static
    class IpRoute {
        private static final StringBuilder reservedTable = new StringBuilder(2048);
        private static final Map<Integer, String> tableNames = new HashMap<>(256);

        static {
            // reservedTable.append("#").append(FileUtil.INSTANCE.getLINE_SEPARATOR());
            // reservedTable.append("# reserved values").append(FileUtil.INSTANCE.getLINE_SEPARATOR());
            // reservedTable.append("#").append(FileUtil.INSTANCE.getLINE_SEPARATOR());
            // reservedTable.append("255   local").append(FileUtil.INSTANCE.getLINE_SEPARATOR());
            // reservedTable.append("254   main").append(FileUtil.INSTANCE.getLINE_SEPARATOR());
            // reservedTable.append("253   default").append(FileUtil.INSTANCE.getLINE_SEPARATOR());
            // reservedTable.append("0 unspec").append(FileUtil.INSTANCE.getLINE_SEPARATOR());
            // reservedTable.append("#").append(FileUtil.INSTANCE.getLINE_SEPARATOR());
        }

        public static
        void addRtTables(Map<Integer, String> tableNames) {
            for (Map.Entry<Integer, String> entry : tableNames.entrySet()) {
                Integer tableNumber = entry.getKey();
                String tableName = entry.getValue();

                if (tableNumber == 0 || tableNumber == 253 || tableNumber == 254 || tableNumber == 255) {
                    logger.error("Trying to add table with same number as reserved value. Skipping.");
                    continue;
                }

                if (!IpRoute.tableNames.containsKey(tableNumber)) {
                    IpRoute.tableNames.put(tableNumber, tableName);
                }
                else {
                    if (!IpRoute.tableNames.get(tableNumber).equals(tableName)) {
                        logger.error("Trying to add table with the same number as another table. Skipping");
                    }
                }
            }

            final StringBuilder table = new StringBuilder(2048);

            for (Map.Entry<Integer, String> entry : IpRoute.tableNames.entrySet()) {
                Integer tableNumber = entry.getKey();
                String tableName = entry.getValue();

                // table.append(tableNumber).append("  ").append(tableName).append(FileUtil.INSTANCE.getLINE_SEPARATOR());
            }

            File policyRouteFile = new File("/etc/iproute2/rt_tables").getAbsoluteFile();

            if (!policyRouteFile.canRead()) {
                // SmtpProcessor.INSTANCE.sendErrorEmail("Policy Routing Error", "Unable to initialize policy routing tables. Something is SERIOUSLY wrong, aborting startup!");
                throw new RuntimeException("Unable to initialize policy routing tables. Something is SERIOUSLY wrong, aborting startup!");
            }

            try (BufferedWriter writer = new BufferedWriter(new FileWriter(policyRouteFile))) {
                writer.write(reservedTable.toString());
                writer.write(table.toString());
                writer.flush();
            } catch (IOException e) {
                logger.error("Error saving routing table file: {}", policyRouteFile, e);
            }
        }
    }

    public static
    class DNS {
        public static
        void setDNSServers(final String dnsServersString) {
            // if (Args.INSTANCE.isLocalMode()) {
            //     return;
            // }
            //
            // String[] dnsServers = dnsServersString.split(",");
            //
            // File dnsFile = new File("/etc/resolvconf/resolv.conf.d/head");
            //
            // if (!dnsFile.canRead()) {
            //     SmtpProcessor.INSTANCE.sendErrorEmail("DNS File Error", "Unable to initialize dns server file. Some is SERIOUSLY wrong.");
            //     throw new RuntimeException("Unable to initialize dns server file. Something is SERIOUSLY wrong");
            // }
            //
            // try (BufferedWriter writer = new BufferedWriter(new FileWriter(dnsFile))) {
            //     writer.write("# File location: /etc/resolvconf/resolv.conf.d/head\n");
            //     writer.write("# Dynamic resolv.conf(5) file for glibc resolver(3) generated by resolvconf(8)\n");
            //
            //     for (String dns : dnsServers) {
            //         writer.write("nameserver " + dns + '\n');
            //     }
            //
            //     writer.flush();
            // }
            // catch (IOException e) {
            //     logger.error("Error saving dns server file: {}", dnsServers, e);
            // }
            //
            // ShellExecutor.Companion.run("resolvconf", "-u");
        }
    }


    public static
    byte[] findFreeSubnet24() {
        logger.info("Scanning for available cidr...");

        // have to find a free cidr
        // start with 10.x.x.x /24 and just march through starting at 0 -> 200 for each, ping to see if there is a gateway (should be)
        // and go from there.


        // on linux, PING has the setuid bit set - so it runs "as root". isReachable() requires either java to have the setuid bit set
        // (ie: sudo setcap cap_net_raw=ep /usr/lib/jvm/jdk/bin/java) or it requires to be run as root. We run as root in production, so it
        // works.


        byte[] ip = new byte[] {10, 0, 0, 0};
        short subnet_24_counter = 0;

        while (true) {
            ip[3]++;

            if (ip[3] > 255) {
                ip[3] = 1;
                ip[2]++;
                subnet_24_counter = 0;
            }
            if (ip[2] > 255) {
                ip[2] = 0;
                ip[1]++;
            }
            if (ip[1] > 255) {
                logger.error("Exhausted all ip searches. FATAL ERROR.");
                return null;
            }

            try {
                InetAddress address = InetAddress.getByAddress(ip);
                boolean reachable = address.isReachable(100);

                if (!reachable) {
                    subnet_24_counter++;
                }

                if (subnet_24_counter == 250) {
                    // this means that we tried all /24 IPs, and ALL of them came back an "non-responsive". 100ms timeout is OK, because
                    // we are on a LAN, that should have MORE than one IP in the cidr, and it should be fairly responsive (ie: <10ms ping)

                    // we found an empty cidr
                    ip[3] = 1;
                    return ip;
                }
            } catch (IOException e) {
                e.printStackTrace();
                return null;
            }
        }
    }

    /**
     * Scans for existing IP addresses on the network.
     *
     * @param startingIp the IP address to start scanning at
     * @param numberOfHosts the number of hosts to scan for. A /28 is 14 hosts : 2^(32-28) - 2 = 14
     *
     * @return true if no hosts were reachable (pingable)
     */
    public static
    boolean scanHosts(final String startingIp, final int numberOfHosts) {
        logger.info("Scanning {} hosts, starting at IP {}.", numberOfHosts, startingIp);

        String[] split = startingIp.split("\\.");
        byte A = (byte) Integer.parseInt(split[0]);
        byte B = (byte) Integer.parseInt(split[1]);
        byte C = (byte) Integer.parseInt(split[2]);
        byte D = (byte) Integer.parseInt(split[3]);

        byte[] ip = new byte[] {A, B, C, D};
        int counter = numberOfHosts;

        while (counter >= 0) {
            counter--;

            ip[3]++;
            if (ip[3] > 255) {
                ip[3] = 1;
                ip[2]++;
            }
            if (ip[2] > 255) {
                ip[2] = 0;
                ip[1]++;
            }
            if (ip[1] > 255) {
                logger.error("Exhausted all ip searches. FATAL ERROR.");
                return false;
            }

            try {
                InetAddress address = InetAddress.getByAddress(ip);
                boolean reachable = address.isReachable(100);

                if (reachable) {
                    logger.error("IP address {} is already reachable on the network. Unable to continue.", address.getHostAddress());
                    return false;
                }
            } catch (IOException e) {
                logger.error("Error pinging the IP address", e);
                return false;
            }
        }

        return true;
    }

    /**
     * @param cidr the CIDR notation, ie: 24, 16, etc. That we want to convert into a netmask
     *
     * @return the netmask (as an int), or if there were errors, the default /24 netmask
     */
    public static
    int getCidrAsNetmask(final Integer cidr) {
        // SubnetUtils.SubnetInfo info = new SubnetUtils("1.1.1.1" + "/" + cidr).getInfo();
        // java.lang.reflect.Method method;
        // try {
        //     method = info.getClass()
        //                  .getDeclaredMethod("netmask", (Class<?>[])null);
        //     method.setAccessible(true);
        //     // try to load the actual netmask. This is harder than it should be....
        //     return (int) method.invoke(info);
        // } catch (Exception e) {
        //     logger.error("Error getting netmask info.", e);
        // }

        return 0xFFFFFF00; // 255.255.255.0
    }

    public static
    int getCidrFromMask(String mask) {
        switch (mask) {
            case "255.255.255.255": return 32;
            case "255.255.255.254": return 31;
            case "255.255.255.252": return 30;
            case "255.255.255.248": return 29;
            case "255.255.255.240": return 28;
            case "255.255.255.224": return 27;
            case "255.255.255.192": return 26;
            case "255.255.255.128": return 25;
            case "255.255.255.0": return 24;
            case "255.255.254.0": return 23;
            case "255.255.252.0": return 22;
            case "255.255.248.0": return 21;
            case "255.255.240.0": return 20;
            case "255.255.224.0": return 19;
            case "255.255.192.0": return 18;
            case "255.255.128.0": return 17;
            case "255.255.0.0": return 16;
            case "255.254.0.0": return 15;
            case "255.252.0.0": return 14;
            case "255.248.0.0": return 13;
            case "255.240.0.0": return 12;
            case "255.224.0.0": return 11;
            case "255.192.0.0": return 10;
            case "255.128.0.0": return 9;
            case "255.0.0.0": return 8;
            case "254.0.0.0": return 7;
            case "252.0.0.0": return 6;
            case "248.0.0.0": return 5;
            case "240.0.0.0": return 4;
            case "224.0.0.0": return 3;
            case "192.0.0.0": return 2;
            case "128.0.0.0": return 1;
            default: return 0;
        }
    }
}
