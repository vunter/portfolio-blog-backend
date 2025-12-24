package dev.catananti.util;

/**
 * Utility class for XML-related operations.
 */
public final class XmlUtil {

    private XmlUtil() {
        // Utility class — no instantiation
    }

    /**
     * Escape special XML characters in the given input string.
     *
     * @param input the raw string to escape
     * @return the escaped string safe for XML content, or empty string if input is null
     */
    public static String escapeXml(String input) {
        if (input == null) {
            return "";
        }
        return input
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
