package dev.catananti.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight user-agent parser. Extracts device type and browser family
 * without external dependencies.
 */
public final class DeviceParser {

    private DeviceParser() {}

    private static final Pattern MOBILE_PATTERN = Pattern.compile(
            "(?i)(android|iphone|ipod|windows phone|blackberry|opera mini|mobile)", Pattern.CASE_INSENSITIVE);
    private static final Pattern TABLET_PATTERN = Pattern.compile(
            "(?i)(ipad|android(?!.*mobile)|tablet|kindle|silk|playbook)", Pattern.CASE_INSENSITIVE);
    private static final Pattern BOT_PATTERN = Pattern.compile(
            "(?i)(bot|crawl|spider|slurp|mediapartners|wget|curl|python-requests|java|go-http|node-fetch)", Pattern.CASE_INSENSITIVE);

    private static final Pattern CHROME_PATTERN = Pattern.compile("(?:Chrome|CriOS)/(\\d+)");
    private static final Pattern FIREFOX_PATTERN = Pattern.compile("(?:Firefox|FxiOS)/(\\d+)");
    private static final Pattern SAFARI_PATTERN = Pattern.compile("Version/(\\d+).*Safari");
    private static final Pattern EDGE_PATTERN = Pattern.compile("Edg(?:e|A|iOS)?/(\\d+)");
    private static final Pattern OPERA_PATTERN = Pattern.compile("(?:OPR|Opera)/(\\d+)");
    private static final Pattern SAMSUNG_PATTERN = Pattern.compile("SamsungBrowser/(\\d+)");

    public static String parseDeviceType(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) return "UNKNOWN";
        if (BOT_PATTERN.matcher(userAgent).find()) return "BOT";
        if (TABLET_PATTERN.matcher(userAgent).find()) return "TABLET";
        if (MOBILE_PATTERN.matcher(userAgent).find()) return "MOBILE";
        return "DESKTOP";
    }

    public static String parseBrowserFamily(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) return "Unknown";
        if (BOT_PATTERN.matcher(userAgent).find()) return "Bot";

        // Order matters: check more specific browsers first
        if (EDGE_PATTERN.matcher(userAgent).find()) return "Edge";
        if (OPERA_PATTERN.matcher(userAgent).find()) return "Opera";
        if (SAMSUNG_PATTERN.matcher(userAgent).find()) return "Samsung Internet";
        if (CHROME_PATTERN.matcher(userAgent).find() && !userAgent.contains("Edg")) return "Chrome";
        if (FIREFOX_PATTERN.matcher(userAgent).find()) return "Firefox";
        if (SAFARI_PATTERN.matcher(userAgent).find()) return "Safari";

        if (userAgent.contains("MSIE") || userAgent.contains("Trident")) return "Internet Explorer";

        return "Other";
    }

    public static String parseOsFamily(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) return "Unknown";
        if (userAgent.contains("Windows NT")) return "Windows";
        if (userAgent.contains("Mac OS X") || userAgent.contains("Macintosh")) return "macOS";
        if (userAgent.contains("iPhone") || userAgent.contains("iPad")) return "iOS";
        if (userAgent.contains("Android")) return "Android";
        if (userAgent.contains("Linux")) return "Linux";
        if (userAgent.contains("CrOS")) return "Chrome OS";
        return "Other";
    }
}
