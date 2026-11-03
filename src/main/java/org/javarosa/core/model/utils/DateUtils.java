package org.javarosa.core.model.utils;

import org.javarosa.core.services.locale.Localization;
import org.javarosa.core.util.DataUtil;
import org.javarosa.core.util.MathUtils;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Static utility methods for Dates in j2me
 *
 * @author Clayton Sims
 */

public class DateUtils {
    private static final int MONTH_OFFSET = (1 - Calendar.JANUARY);

    public static final int FORMAT_ISO8601 = 1;
    public static final int FORMAT_ISO8601_WALL_TIME = 10;
    public static final int FORMAT_HUMAN_READABLE_SHORT = 2;
    public static final int FORMAT_HUMAN_READABLE_DAYS_FROM_TODAY = 5;
    public static final int FORMAT_TIMESTAMP_SUFFIX = 7;
    /**
     * RFC 822 *
     */
    public static final int FORMAT_TIMESTAMP_HTTP = 9;

    private static CalendarStrings defaultCalendarStrings = new CalendarStrings();
    private static TimezoneProvider tzProvider = new TimezoneProvider();

    public static final long HOUR_IN_MS = TimeUnit.HOURS.toMillis(1);
    public static final long DAY_IN_MS = TimeUnit.DAYS.toMillis(1);

    private static final Date EPOCH_DATE = getDate(1970, 1, 1);

    private final static long EPOCH_TIME = roundDate(EPOCH_DATE).getTime();

    public static class CalendarStrings {
        public String[] monthNamesLong;
        public String[] monthNamesShort;
        public String[] dayNamesLong;
        public String[] dayNamesShort;

        public CalendarStrings(String[] monthNamesLong, String[] monthNamesShort,
                               String[] dayNamesLong, String[] dayNamesShort) {
            this.monthNamesLong = monthNamesLong;
            this.monthNamesShort = monthNamesShort;
            this.dayNamesLong = dayNamesLong;
            this.dayNamesShort = dayNamesShort;

        }

        public CalendarStrings() {
            this(
                    new String[]{"January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"},
                    new String[]{"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"},
                    new String[]{"Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"},
                    new String[]{"Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"}
            );
        }
    }

    public static class DateFields {
        public DateFields() {
            year = 1970;
            month = 1;
            day = 1;
            hour = 0;
            minute = 0;
            second = 0;
            secTicks = 0;
            dow = 0;
            timezoneOffsetInMillis = 0;

            noValidation = false;

//            tzStr = "Z";
//            tzOffset = 0;
        }

        public int year;
        public int month; //1-12
        public int day; //1-31
        public int hour; //0-23
        public int minute; //0-59
        public int second; //0-59
        public int secTicks; //0-999 (ms)
        public int timezoneOffsetInMillis; //(ms)
        boolean noValidation = false; // true or false. Set to true when using foreign calendars

        /**
         * NOTE: CANNOT BE USED TO SPECIFY A DATE *
         */
        public int dow; //1-7;

//        public String tzStr;
//        public int tzOffset; //s ahead of UTC

        public boolean check() {
            return noValidation ||
                    ((inRange(month, 1, 12) && inRange(day, 1, daysInMonth(month - MONTH_OFFSET, year)) &&
                            inRange(hour, 0, 23) && inRange(minute, 0, 59) && inRange(second, 0, 59) && inRange(secTicks, 0, 999)));
        }
    }

    // Used by Formplayer
    public static void setTimezoneProvider(TimezoneProvider provider) {
        tzProvider = provider;
    }

    public static void resetTimezoneProvider() {
        tzProvider = new TimezoneProvider();
    }

    private static int timezoneOffset() {
        return tzProvider.getTimezoneOffsetMillis();
    }

    public static TimeZone timezone() {
        return tzProvider.getTimezone();
    }

    public static DateFields getFieldsForNonGregorianCalendar(int year, int monthOfYear, int dayOfMonth) {
        DateFields nonGregorian = new DateFields();
        nonGregorian.year = year;
        nonGregorian.month = monthOfYear;
        nonGregorian.day = dayOfMonth;
        return nonGregorian;
    }

    public static DateFields getFields(Date d) {
        return getFields(d, null);
    }

    public static DateFields getFields(Date d, String timezone) {
        Calendar cd = Calendar.getInstance();
        cd.setTime(d);
        if (timezone != null) {
            cd.setTimeZone(TimeZone.getTimeZone(timezone));
        } else if (timezone() != null) {
            cd.setTimeZone(timezone());
        } else if (timezoneOffset() != -1) {
            return getFields(d, timezoneOffset());
        }
        return getFields(cd, cd.getTimeZone().getOffset(d.getTime()));
    }

    private static DateFields getFields(Date d, int timezoneOffset) {
        Calendar cd = Calendar.getInstance();
        cd.setTimeZone(TimeZone.getTimeZone("UTC"));
        cd.setTime(d);
        cd.add(Calendar.MILLISECOND, timezoneOffset);
        return getFields(cd, timezoneOffset);
    }

    private static DateFields getFields(Calendar cal, int timezoneOffset) {
        DateFields fields = new DateFields();
        fields.year = cal.get(Calendar.YEAR);
        fields.month = cal.get(Calendar.MONTH) + MONTH_OFFSET;
        fields.day = cal.get(Calendar.DAY_OF_MONTH);
        fields.hour = cal.get(Calendar.HOUR_OF_DAY);
        fields.minute = cal.get(Calendar.MINUTE);
        fields.second = cal.get(Calendar.SECOND);
        fields.secTicks = cal.get(Calendar.MILLISECOND);
        fields.dow = cal.get(Calendar.DAY_OF_WEEK);
        fields.timezoneOffsetInMillis = timezoneOffset;
        return fields;
    }

    /**
     * Turn year, month, date into Date object.
     *
     * @return Date or null, depending if arguments are in the valid date range
     */
    public static Date getDate(int year, int month, int day) {
        DateFields f = new DateFields();
        f.year = year;
        f.month = month;
        f.day = day;
        return (f.check() ? getDate(f) : null);
    }

    /**
     * Turn DateField information into Date object, using default
     * timezone.
     *
     * @param df representation of a datetime
     * @return Date interpretation of DateFields at given default timezone
     */
    public static Date getDate(DateFields df) {
        return getDate(df, null);
    }

    /**
     * Turn DateField information into Date object, taking default or inputted
     * timezone into account.
     *
     * @param df       representation of a datetime
     * @param timezone use this timezone, but if null, use default timezone
     * @return Date interpretation of DateFields at given timezone
     */
    private static Date getDate(DateFields df, String timezone) {
        Calendar cd = Calendar.getInstance();

        if (timezone != null) {
            cd.setTimeZone(TimeZone.getTimeZone(timezone));
        } else if (timezone() != null) {
            cd.setTimeZone(timezone());
        } else if (timezoneOffset() != -1) {
            return getDate(df, timezoneOffset());
        }

        cd.set(Calendar.YEAR, df.year);
        cd.set(Calendar.MONTH, df.month - MONTH_OFFSET);
        cd.set(Calendar.DAY_OF_MONTH, df.day);
        cd.set(Calendar.HOUR_OF_DAY, df.hour);
        cd.set(Calendar.MINUTE, df.minute);
        cd.set(Calendar.SECOND, df.second);
        cd.set(Calendar.MILLISECOND, df.secTicks);

        return cd.getTime();
    }

    private static Date getDate(DateFields df, int timezoneOffset) {
        Calendar cd = Calendar.getInstance();
        cd.setTimeZone(TimeZone.getTimeZone("UTC"));

        cd.set(Calendar.YEAR, df.year);
        cd.set(Calendar.MONTH, df.month - MONTH_OFFSET);
        cd.set(Calendar.DAY_OF_MONTH, df.day);
        cd.set(Calendar.HOUR_OF_DAY, df.hour);
        cd.set(Calendar.MINUTE, df.minute);
        cd.set(Calendar.SECOND, df.second);
        cd.set(Calendar.MILLISECOND, df.secTicks);

        cd.add(Calendar.MILLISECOND, -1 * timezoneOffset);

        return cd.getTime();
    }

    /* ==== FORMATTING DATES/TIMES TO STANDARD STRINGS ==== */

    public static String formatDateTime(Date d, int format) {
        if (d == null) {
            return "";
        }

        DateFields fields = getFields(d, format == FORMAT_TIMESTAMP_HTTP ? "UTC" : null);

        String delim;
        switch (format) {
            case FORMAT_ISO8601:
                delim = "T";
                break;
            case FORMAT_TIMESTAMP_SUFFIX:
                delim = "";
                break;
            case FORMAT_TIMESTAMP_HTTP:
                delim = " ";
                break;
            default:
                delim = " ";
                break;
        }

        return formatDate(fields, format) + delim + formatTime(fields, format);
    }

    public static String formatDate(Date d, int format) {
        return (d == null ? "" : formatDate(getFields(d, format == FORMAT_TIMESTAMP_HTTP ? "UTC" : null), format));
    }

    public static String formatTime(Date d, int format) {
        return (d == null ? "" : formatTime(getFields(d, format == FORMAT_TIMESTAMP_HTTP ? "UTC" : null), format));
    }

    private static String formatDate(DateFields f, int format) {
        switch (format) {
            case FORMAT_ISO8601:
                return formatDateISO8601(f);
            case FORMAT_HUMAN_READABLE_SHORT:
                return formatDateColloquial(f);
            case FORMAT_HUMAN_READABLE_DAYS_FROM_TODAY:
                return formatDaysFromToday(f);
            case FORMAT_TIMESTAMP_SUFFIX:
                return formatDateSuffix(f);
            case FORMAT_TIMESTAMP_HTTP:
                return formatDateHttp(f);
            default:
                return null;
        }
    }

    private static String formatTime(DateFields f, int format) {
        switch (format) {
            case FORMAT_ISO8601:
                return formatTimeISO8601(f);
            case FORMAT_ISO8601_WALL_TIME:
                return formatTimeISO8601(f, true);
            case FORMAT_HUMAN_READABLE_SHORT:
                return formatTimeColloquial(f);
            case FORMAT_TIMESTAMP_SUFFIX:
                return formatTimeSuffix(f);
            case FORMAT_TIMESTAMP_HTTP:
                return formatTimeHttp(f);
            default:
                return null;
        }
    }

    /**
     * RFC 822 *
     */
    private static String formatDateHttp(DateFields f) {
        return format(f, "%a, %d %b %Y");
    }

    /**
     * RFC 822 *
     */
    private static String formatTimeHttp(DateFields f) {
        return format(f, "%H:%M:%S GMT");
    }

    private static String formatDateISO8601(DateFields f) {
        return f.year + "-" + intPad(f.month, 2) + "-" + intPad(f.day, 2);
    }

    private static String formatDateColloquial(DateFields f) {
        String year = Integer.valueOf(f.year).toString();

        //Normal Date
        if (year.length() == 4) {
            year = year.substring(2, 4);
        }
        //Otherwise we have an old or bizzarre date, don't try to do anything

        return intPad(f.day, 2) + "/" + intPad(f.month, 2) + "/" + year;
    }

    private static String formatDateSuffix(DateFields f) {
        return f.year + intPad(f.month, 2) + intPad(f.day, 2);
    }

    private static String formatTimeISO8601(DateFields f) {
        return formatTimeISO8601(f, false);
    }

    private static String formatTimeISO8601(DateFields f, boolean suppressTimezone) {
        String time = intPad(f.hour, 2) + ":" + intPad(f.minute, 2) + ":" + intPad(f.second, 2) + "." + intPad(f.secTicks, 3);
        if (suppressTimezone) {
            return time;
        }

        int offset;
        if (timezoneOffset() != -1) {
            offset = timezoneOffset();
        } else {
            //Time Zone ops (1 in the first field corresponds to 'CE' ERA)
            offset = TimeZone.getDefault().getOffset(1, f.year, f.month - 1, f.day, f.dow, 0);
        }

        //NOTE: offset is in millis
        if (offset == 0) {
            time += "Z";
        } else {

            //Start with sign
            String offsetSign = offset > 0 ? "+" : "-";

            int value = Math.abs(offset) / 1000 / 60;

            String hrs = intPad(value / 60, 2);
            String mins = value % 60 != 0 ? ":" + intPad(value % 60, 2) : "";

            time += offsetSign + hrs + mins;
        }
        return time;
    }

    private static String formatTimeColloquial(DateFields f) {
        return intPad(f.hour, 2) + ":" + intPad(f.minute, 2);
    }

    private static String formatTimeSuffix(DateFields f) {
        return intPad(f.hour, 2) + intPad(f.minute, 2) + intPad(f.second, 2);
    }

    public static String format(Date d, String format) {
        return format(getFields(d), format);
    }

    public static String format(DateFields f, String format) {
        return format(f, format, defaultCalendarStrings);
    }

    public static String format(DateFields f, String format, CalendarStrings stringsSource) {
        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < format.length(); i++) {
            char c = format.charAt(i);

            if (c == '%') {
                i++;
                if (i >= format.length()) {
                    throw new RuntimeException("date format string ends with %");
                } else {
                    c = format.charAt(i);
                }

                if (c == '%') {            //literal '%'
                    sb.append("%");
                } else if (c == 'Y') {    //4-digit year
                    sb.append(intPad(f.year, 4));
                } else if (c == 'y') {    //2-digit year
                    sb.append(intPad(f.year, 4).substring(2));
                } else if (c == 'm') {    //0-padded month
                    sb.append(intPad(f.month, 2));
                } else if (c == 'n') {    //numeric month
                    sb.append(f.month);
                } else if (c == 'B') {    //long text month
                    sb.append(stringsSource.monthNamesLong[f.month - 1]);
                } else if (c == 'b') {    //short text month
                    sb.append(stringsSource.monthNamesShort[f.month - 1]);
                } else if (c == 'd') {    //0-padded day of month
                    sb.append(intPad(f.day, 2));
                } else if (c == 'e') {    //day of month
                    sb.append(f.day);
                } else if (c == 'H') {    //0-padded hour (24-hr time)
                    sb.append(intPad(f.hour, 2));
                } else if (c == 'h') {    //hour (24-hr time)
                    sb.append(f.hour);
                } else if (c == 'M') {    //0-padded minute
                    sb.append(intPad(f.minute, 2));
                } else if (c == 'S') {    //0-padded second
                    sb.append(intPad(f.second, 2));
                } else if (c == '3') {    //0-padded millisecond ticks (000-999)
                    sb.append(intPad(f.secTicks, 3));
                } else if (c == 'A') {    //long text day
                    sb.append(stringsSource.dayNamesLong[f.dow - 1]);
                } else if (c == 'a') {    //Three letter short text day
                    sb.append(stringsSource.dayNamesShort[f.dow - 1]);
                } else if (c == 'w') {    //Day of the week (0 through 6), Sunday being 0.
                    sb.append(f.dow - 1);
                } else if (c == 'Z') {
                    sb.append(getOffsetInStandardFormat(f.timezoneOffsetInMillis));
                } else if (Arrays.asList('c', 'C', 'D', 'F', 'g', 'G', 'I', 'j', 'k', 'l', 'p', 'P', 'r', 'R', 's', 't', 'T', 'u', 'U', 'V', 'W', 'x', 'X', 'z').contains(c)) {
                    // Format specifiers supported by libc's strftime: https://www.gnu.org/software/libc/manual/html_node/Formatting-Calendar-Time.html
                    throw new RuntimeException("unsupported escape in date format string [%" + c + "]");
                } else {
                    throw new RuntimeException("unrecognized escape in date format string [%" + c + "]");
                }
            } else {
                sb.append(c);
            }
        }

        return sb.toString();
    }

    /* ==== PARSING DATES/TIMES FROM STANDARD STRINGS ==== */

    public static Date parseDateTime(String str) {
        DateFields fields = new DateFields();
        int i = str.indexOf("T");
        if (i != -1) {
            if (!parseDateAndStore(str.substring(0, i), fields) || !parseTimeAndStore(str.substring(i + 1), fields)) {
                return null;
            }
        } else {
            if (!parseDateAndStore(str, fields)) {
                return null;
            }
        }
        return getDate(fields);
    }

    public static Date parseDate(String str) {
        DateFields fields = new DateFields();
        if (!parseDateAndStore(str, fields)) {
            return null;
        }
        return getDate(fields);
    }

    /**
     * Parse string into date, save result to DateFields argument, and return
     * true if it was successfully parsed into a valid date.
     *
     * @return Was the string successfully parsed into a valid date
     */
    private static boolean parseDateAndStore(String dateStr, DateFields df) {
        String[] pieces = DataUtil.splitOnDash(dateStr);
        if (pieces.length != 3) {
            return false;
        }

        try {
            df.year = Integer.parseInt(pieces[0]);
            df.month = Integer.parseInt(pieces[1]);
            df.day = Integer.parseInt(pieces[2]);
        } catch (NumberFormatException nfe) {
            return false;
        }

        return df.check();
    }

    public static Date parseTime(String str) {
        return parseTime(str, false);
    }

    public static Date parseTime(String str, boolean ignoreTimezone) {
        if (!ignoreTimezone && (timezoneOffset() != -1 && !str.contains("+") && !str.contains("-") && !str.contains("Z"))) {
            str = str + getOffsetInStandardFormat(timezoneOffset());
        }

        DateFields fields = new DateFields();
        if (!parseTimeAndStore(str, fields)) {
            return null;
        }
        return getDate(fields);
    }

    public static String getOffsetInStandardFormat(int offsetInMillis) {
        int hours = offsetInMillis / 1000 / 60 / 60;
        String offsetStr;
        if (hours > 0) {
            offsetStr = "+" + intPad(hours, 2);
        } else if (hours == 0) {
            offsetStr = "Z";
        } else {
            offsetStr = "-" + intPad(Math.abs(hours), 2);
        }

        int totalMinutes = offsetInMillis / 1000 / 60;
        int remainderMinutes = Math.abs(totalMinutes) % 60;
        if (remainderMinutes != 0) {
            offsetStr += (":" + intPad(remainderMinutes, 2));
        }

        return offsetStr;
    }


    private static boolean parseTimeAndStore(String timeStr, DateFields df) {
        // get timezone information first. Make a Datefields set for the possible offset
        // NOTE: DO NOT DO DIRECT COMPUTATIONS AGAINST THIS. It's a holder for hour/minute
        // data only, but has data in other fields
        DateFields timeOffset = null;

        if (timeStr.charAt(timeStr.length() - 1) == 'Z') {
            // UTC!
            // Clean up string for later processing
            timeStr = timeStr.substring(0, timeStr.length() - 1);
            timeOffset = new DateFields();
        } else if (timeStr.contains("+") || timeStr.contains("-")) {
            timeOffset = new DateFields();

            String[] pieces = DataUtil.splitOnPlus(timeStr);

            // We're going to add the Offset straight up to get UTC
            // so we need to invert the sign on the offset string
            int offsetSign = -1;

            if (pieces.length > 1) {
                // offsetSign is already correct
            } else {
                pieces = DataUtil.splitOnDash(timeStr);
                offsetSign = 1;
            }

            timeStr = pieces[0];

            String offset = pieces[1];
            String hours = offset;
            if (offset.contains(":")) {
                String[] tzPieces = DataUtil.splitOnColon(offset);
                hours = tzPieces[0];
                int mins = Integer.parseInt(tzPieces[1]);
                timeOffset.minute = mins * offsetSign;
            }
            timeOffset.hour = Integer.parseInt(hours) * offsetSign;
        }

        // Do the actual parse for the real time values;
        if (!parseRawTime(timeStr, df)) {
            return false;
        }

        if (!(df.check())) {
            return false;
        }

        // Time is good, if there was no timezone info, just return that;
        if (timeOffset == null) {
            return true;
        }

        // Now apply any relevant offsets from the timezone.
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));

        c.setTime(new Date(DateUtils.getDate(df, "UTC").getTime() + (((60 * timeOffset.hour) + timeOffset.minute) * 60 * 1000)));

        // c is now in the timezone of the parsed value, so put
        // it in the local timezone.

        c.setTimeZone(TimeZone.getDefault());

        DateFields adjusted = getFields(c.getTime());

        df.hour = adjusted.hour;
        df.minute = adjusted.minute;
        df.second = adjusted.second;
        df.secTicks = adjusted.secTicks;

        return df.check();
    }

    /**
     * Parse the raw components of time (hh:mm:ss or hh:mm) with no timezone information
     *
     * @param timeStr expects to be a String representing time of format
     *                hh:mm:ss or hh:mm
     * @param df      where the parsed time information is stored
     * @return Was the string successfully interpreted as valid time?
     */
    private static boolean parseRawTime(String timeStr, DateFields df) {
        String[] pieces = DataUtil.splitOnColon(timeStr);

        if (pieces.length != 2 && pieces.length != 3) {
            return false;
        }

        try {
            df.hour = Integer.parseInt(pieces[0]);
            df.minute = Integer.parseInt(pieces[1]);

            // if seconds part present, parse it
            if (pieces.length == 3) {
                String secStr = pieces[2];
                int i;
                // only grab prefix of seconds piece that includes digits and decimal(s)
                for (i = 0; i < secStr.length(); i++) {
                    char c = secStr.charAt(i);
                    if (!Character.isDigit(c) && c != '.')
                        break;
                }
                secStr = secStr.substring(0, i);
                double fsec = Double.parseDouble(secStr);
                // split seconds into whole and decimal components
                df.second = (int)fsec;
                df.secTicks = (int)(1000.0 * (fsec - df.second));
            }
        } catch (NumberFormatException nfe) {
            return false;
        }

        return df.check();
    }


    /* ==== DATE UTILITY FUNCTIONS ==== */

    /**
     * @return new Date object with same date but time set to midnight (in current timezone)
     */
    public static Date roundDate(Date d) {
        DateFields f = getFields(d);
        return getDate(f.year, f.month, f.day);
    }

    public static Date today() {
        return roundDate(new Date());
    }

    /* ==== CALENDAR FUNCTIONS ==== */

    /**
     * Returns the number of days in the month given for
     * a given year.
     *
     * @param month The month to be tested
     * @param year  The year in which the month is to be tested
     * @return the number of days in the given month on the given
     * year.
     */
    public static int daysInMonth(int month, int year) {
        if (month == Calendar.APRIL ||
                month == Calendar.JUNE ||
                month == Calendar.SEPTEMBER ||
                month == Calendar.NOVEMBER) {
            return 30;
        } else if (month == Calendar.FEBRUARY) {
            return 28 + (isLeap(year) ? 1 : 0);
        } else {
            return 31;
        }
    }

    /**
     * Determines whether a year is a leap year in the
     * proleptic Gregorian calendar.
     *
     * @param year The year to be tested
     * @return True, if the year given is a leap year,
     * false otherwise.
     */
    public static boolean isLeap(int year) {
        return year % 4 == 0 && (year % 100 != 0 || year % 400 == 0);
    }


    /* ==== Parsing to Human Text ==== */

    /**
     * Provides text representing a span of time.
     *
     * @param f The fields for the date to be compared against the current date.
     * @return a string which is a human readable representation of the difference between
     * the provided date and the current date.
     */
    private static String formatDaysFromToday(DateFields f) {
        Date d = DateUtils.getDate(f);
        int daysAgo = DateUtils.daysSinceEpoch(new Date()) - DateUtils.daysSinceEpoch(d);

        if (daysAgo == 0) {
            return Localization.get("date.today");
        } else if (daysAgo == 1) {
            return Localization.get("date.yesterday");
        } else if (daysAgo == 2) {
            return Localization.get("date.twoago", new String[]{String.valueOf(daysAgo)});
        } else if (daysAgo > 2 && daysAgo <= 6) {
            return Localization.get("date.nago", new String[]{String.valueOf(daysAgo)});
        } else if (daysAgo == -1) {
            return Localization.get("date.tomorrow");
        } else if (daysAgo < -1 && daysAgo >= -6) {
            return Localization.get("date.nfromnow", new String[]{String.valueOf(-daysAgo)});
        } else {
            return DateUtils.formatDate(f, DateUtils.FORMAT_HUMAN_READABLE_SHORT);
        }
    }

    /* ==== DATE OPERATIONS ==== */

    /**
     * Creates a Date object representing the amount of time between the
     * reference date, and the given parameters.
     *
     * @param ref          The starting reference date
     * @param type         "week", or "month", representing the time period which is to be returned.
     * @param start        "sun", "mon", ... etc. representing the start of the time period.
     * @param beginning    true=return first day of period, false=return last day of period
     * @param includeToday Whether to include the current date in the returned calculation
     * @param nAgo         How many periods ago. 1=most recent period, 0=period in progress
     * @return a Date object representing the amount of time between the
     * reference date, and the given parameters.
     */
    public static Date getPastPeriodDate(Date ref, String type, String start, boolean beginning, boolean includeToday, int nAgo) {
        Date d = null;

        if (type.equals("week")) {
            // 1 week period
            // start: day of week that starts period
            // beginning: true=return first day of period, false=return last day of period
            // includeToday: whether today's date can count as the last day of the period
            // nAgo: how many periods ago; 1=most recent period, 0=period in progress

            int target_dow = -1, current_dow = -1, diff;
            int offset = (includeToday ? 1 : 0);

            if (start.equals("sun")) {
                target_dow = 0;
            } else if (start.equals("mon")) {
                target_dow = 1;
            } else if (start.equals("tue")) {
                target_dow = 2;
            } else if (start.equals("wed")) {
                target_dow = 3;
            } else if (start.equals("thu")) {
                target_dow = 4;
            } else if (start.equals("fri")) {
                target_dow = 5;
            } else if (start.equals("sat")) {
                target_dow = 6;
            }

            if (target_dow == -1) {
                throw new RuntimeException();
            }

            Calendar cd = Calendar.getInstance();
            cd.setTime(ref);

            switch (cd.get(Calendar.DAY_OF_WEEK)) {
                case Calendar.SUNDAY:
                    current_dow = 0;
                    break;
                case Calendar.MONDAY:
                    current_dow = 1;
                    break;
                case Calendar.TUESDAY:
                    current_dow = 2;
                    break;
                case Calendar.WEDNESDAY:
                    current_dow = 3;
                    break;
                case Calendar.THURSDAY:
                    current_dow = 4;
                    break;
                case Calendar.FRIDAY:
                    current_dow = 5;
                    break;
                case Calendar.SATURDAY:
                    current_dow = 6;
                    break;
                default:
                    throw new RuntimeException(); //something is wrong
            }

            diff = (((current_dow - target_dow) + (7 + offset)) % 7 - offset) + (7 * nAgo) - (beginning ? 0 : 6); //booyah
            d = new Date(ref.getTime() - diff * DAY_IN_MS);
        } else if (type.equals("month")) {
            //not supported
        } else {
            throw new IllegalArgumentException();
        }

        return d;
    }

    /**
     * Gets the number of months separating the two dates.
     *
     * @param earlierDate The earlier date, chronologically
     * @param laterDate   The later date, chronologically
     * @return the number of months separating the two dates.
     */
    public static int getMonthsDifference(Date earlierDate, Date laterDate) {
        Date span = new Date(laterDate.getTime() - earlierDate.getTime());
        Date firstDate = new Date(0);
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(firstDate);
        int firstYear = calendar.get(Calendar.YEAR);
        int firstMonth = calendar.get(Calendar.MONTH);

        calendar.setTime(span);
        int spanYear = calendar.get(Calendar.YEAR);
        int spanMonth = calendar.get(Calendar.MONTH);
        return (spanYear - firstYear) * 12 + (spanMonth - firstMonth);
    }

    /**
     * @param date the date object to be analyzed
     * @return The number of days (as a double precision floating point) since the Epoch
     */
    public static int daysSinceEpoch(Date date) {
        return (int)MathUtils.divLongNotSuck(roundDate(date).getTime() - EPOCH_TIME + DAY_IN_MS / 2, DAY_IN_MS);
    }


    public static Double fractionalDaysSinceEpoch(Date a) {
        //Even though EPOCH_DATE uses the device timezone, calendar Daylight savings time adjustments
        //are based on the instant of evaluation (the epoch), not today, so we need to manually
        //correct for any drift in the offsets. This can also present if timezone definitions
        //have drifted over time
        long timeZoneAdjust = (a.getTimezoneOffset() - EPOCH_DATE.getTimezoneOffset()) * 60* 1000;
        return Double.valueOf(((a.getTime() - EPOCH_DATE.getTime()) - timeZoneAdjust) / (double)DAY_IN_MS);
    }

    /**
     * add n days to date d
     */
    public static Date dateAdd(Date d, int n) {
        return roundDate(new Date(roundDate(d).getTime() + DAY_IN_MS * n + DAY_IN_MS / 2));
        //half-day offset is needed to handle differing DST offsets!
    }

    /**
     * return the number of days between a and b, positive if b is later than a
     *
     * @return # days difference
     */
    public static int dateDiff(Date a, Date b) {
        return (int)MathUtils.divLongNotSuck(roundDate(b).getTime() - roundDate(a).getTime() + DAY_IN_MS / 2, DAY_IN_MS);
        //half-day offset is needed to handle differing DST offsets!
    }

    /* ==== UTILITY ==== */

    /**
     * Converts an integer to a string, ensuring that the string
     * contains a certain number of digits
     *
     * @param n   The integer to be converted
     * @param pad The length of the string to be returned
     * @return A string representing n, which has pad - #digits(n)
     * 0's preceding the number.
     */
    public static String intPad(int n, int pad) {
        String s = String.valueOf(n);
        while (s.length() < pad)
            s = "0" + s;
        return s;
    }

    private static boolean inRange(int x, int min, int max) {
        return (x >= min && x <= max);
    }

    /* ==== GARBAGE (backward compatibility; too lazy to remove them now) ==== */

    public static String formatDateToTimeStamp(Date date) {
        return formatDateTime(date, FORMAT_ISO8601);
    }

    public static String getShortStringValue(Date val) {
        return formatDate(val, FORMAT_HUMAN_READABLE_SHORT);
    }

    public static String getXMLStringValue(Date val) {
        return formatDate(val, FORMAT_ISO8601);
    }

    public static String get24HourTimeFromDate(Date d) {
        return formatTime(d, FORMAT_HUMAN_READABLE_SHORT);
    }

    public static Date getDateFromString(String value) {
        return parseDate(value);
    }

    public static Date getDateTimeFromString(String value) {
        return parseDateTime(value);
    }

    public static boolean stringContains(String string, String substring) {
        if (string == null || substring == null) {
            return false;
        }
        return string.contains(substring);
    }

    // TODO: Move this method to DateUtils
    public static String convertTimeInMsToISO8601(long ms) {
        if (ms == 0) {
            return "";
        } else {
            DateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'");
            df.setTimeZone(TimeZone.getTimeZone("UTC"));
            return df.format(ms);
        }
    }

    public static boolean hasTimeComponent(Date date) {
        LocalTime time = date.toInstant()
                .atZone(ZoneId.systemDefault())
                .toLocalTime();

        return !time.equals(LocalTime.MIDNIGHT);
    }
}
