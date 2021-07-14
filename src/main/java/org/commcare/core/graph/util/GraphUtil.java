package org.commcare.core.graph.util;

/**
 * Constants used by graphing
 *
 * @author jschweers
 */
public class GraphUtil {
    public static final String TYPE_XY = "xy";
    public static final String TYPE_BAR = "bar";
    public static final String TYPE_BUBBLE = "bubble";
    public static final String TYPE_TIME = "time";

    private static int charLimit = -1;

    public static int getLabelCharacterLimit() {
        return charLimit;
    }

    public static void setLabelCharacterLimit(int limit) {
        charLimit = limit;
    }
}
