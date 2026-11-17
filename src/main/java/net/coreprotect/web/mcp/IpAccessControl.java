package net.coreprotect.web.mcp;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/**
 * /mcp への接続元アドレスを判定する。
 */
public final class IpAccessControl {
    private IpAccessControl() {
    }

    public static boolean isAllowed(InetAddress address, boolean allowPrivateOnly, List<String> allowedCidrs) {
        if (!allowPrivateOnly) {
            return true;
        }

        if (address == null) {
            return false;
        }

        if (isPrivate(address)) {
            return true;
        }

        for (String cidr : allowedCidrs) {
            if (matchesCidr(address, cidr)) {
                return true;
            }
        }

        return false;
    }

    private static boolean isPrivate(InetAddress address) {
        if (address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress()) {
            return true;
        }

        // IPv6 ULA (fc00::/7)
        byte[] bytes = address.getAddress();
        if (bytes.length == 16) {
            int firstByte = bytes[0] & 0xFF;
            return (firstByte & 0xFE) == 0xFC;
        }

        return false;
    }

    private static boolean matchesCidr(InetAddress address, String cidr) {
        if (cidr == null || cidr.trim().isEmpty()) {
            return false;
        }

        String trimmed = cidr.trim();
        int slash = trimmed.indexOf('/');
        String hostPart = slash >= 0 ? trimmed.substring(0, slash) : trimmed;

        try {
            InetAddress network = InetAddress.getByName(hostPart);
            byte[] networkBytes = network.getAddress();
            byte[] addressBytes = address.getAddress();

            if (networkBytes.length != addressBytes.length) {
                return false;
            }

            int prefixLength = slash >= 0
                    ? Integer.parseInt(trimmed.substring(slash + 1))
                    : networkBytes.length * 8;

            return matchesPrefix(networkBytes, addressBytes, prefixLength);
        } catch (UnknownHostException | NumberFormatException e) {
            return false;
        }
    }

    private static boolean matchesPrefix(byte[] networkBytes, byte[] addressBytes, int prefixLength) {
        if (prefixLength < 0 || prefixLength > networkBytes.length * 8) {
            return false;
        }

        int fullBytes = prefixLength / 8;
        for (int i = 0; i < fullBytes; i++) {
            if (networkBytes[i] != addressBytes[i]) {
                return false;
            }
        }

        int remainingBits = prefixLength % 8;
        if (remainingBits == 0) {
            return true;
        }

        int mask = (0xFF << (8 - remainingBits)) & 0xFF;
        return (networkBytes[fullBytes] & mask) == (addressBytes[fullBytes] & mask);
    }
}
