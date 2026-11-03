package org.javarosa.core.model.utils.test;

import org.javarosa.core.model.utils.DateUtils;
import org.javarosa.core.model.utils.DateUtils.DateFields;
import org.javarosa.test_utils.MockTimezoneProvider;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.TimeZone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class DateUtilsTests {
    private static Date currentTime;
    private static final int HOUR_IN_MILLIS = 60 * 60 * 1000;

    @BeforeClass
    public static void setUp() {
        currentTime = new Date();
    }

    /**
     * This test ensures that the Strings returned
     * by the getXMLStringValue function are in
     * the proper XML compliant format.
     */
    @Test
    public void testGetXMLStringValueFormat() {
        String currentDate = DateUtils.getXMLStringValue(currentTime);
        assertEquals("The date string was not of the proper length", currentDate.length(), "YYYY-MM-DD".length());
        assertEquals("The date string does not have proper year formatting", currentDate.indexOf("-"), "YYYY-".indexOf("-"));

        try {
            Integer.parseInt(currentDate.substring(0, 4));
        } catch (NumberFormatException e) {
            fail("The Year value was not a valid integer");
        }

        try {
            Integer.parseInt(currentDate.substring(5, 7));
        } catch (NumberFormatException e) {
            fail("The Month value was not a valid integer");
        }

        try {
            Integer.parseInt(currentDate.substring(8, 10));
        } catch (NumberFormatException e) {
            fail("The Day value was not a valid integer");
        }
    }

    @Test
    public void testTimeParses() {
        // This is all kind of tricky. We need to assume J2ME level compliance, so
        // dates won't ever be assumed to have an intrinsic timezone, they'll be
        // assumed to be in the phone's default timezone

        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

        testTime("10:00", 1000 * 60 * 60 * 10);
        testTime("10:00Z", 1000 * 60 * 60 * 10);

        testTime("10:00+02", 1000 * 60 * 60 * 8);
        testTime("10:00-02", 1000 * 60 * 60 * 12);

        testTime("10:00+02:30", 1000 * 60 * 6 * 75);
        testTime("10:00-02:30", 1000 * 60 * 6 * 125);

        TimeZone offsetTwoHours = TimeZone.getTimeZone("GMT+02");

        TimeZone.setDefault(offsetTwoHours);

        testTime("10:00", 1000 * 60 * 60 * 10);
        testTime("10:00Z", 1000 * 60 * 60 * 12);

        testTime("10:00+02", 1000 * 60 * 60 * 10);
        testTime("10:00-02", 1000 * 60 * 60 * 14);

        testTime("10:00+02:30", 1000 * 60 * 6 * 95);
        testTime("10:00-02:30", 1000 * 60 * 6 * 145);

        TimeZone offsetMinusTwoHours = TimeZone.getTimeZone("GMT-02");

        TimeZone.setDefault(offsetMinusTwoHours);

        testTime("14:00", 1000 * 60 * 60 * 14);
        testTime("14:00Z", 1000 * 60 * 60 * 12);

        testTime("14:00+02", 1000 * 60 * 60 * 10);
        testTime("14:00-02", 1000 * 60 * 60 * 14);

        testTime("14:00+02:30", 1000 * 60 * 6 * 95);
        testTime("14:00-02:30", 1000 * 60 * 6 * 145);


        TimeZone offsetPlusHalf = TimeZone.getTimeZone("GMT+0230");

        TimeZone.setDefault(offsetPlusHalf);

        testTime("14:00", 1000 * 60 * 6 * 140);
        testTime("14:00Z", 1000 * 60 * 6 * 165);

        testTime("14:00+02", 1000 * 60 * 6 * 145);
        testTime("14:00-02", 1000 * 60 * 6 * 185);

        testTime("14:00+02:30", 1000 * 60 * 6 * 140);
        testTime("14:00-02:30", 1000 * 60 * 6 * 190);

        testTime("14:00+04:00", 1000 * 60 * 6 * 125);

        TimeZone.setDefault(null);
    }

    private void testTime(String in, long test) {
        try {
            Date d = DateUtils.parseTime(in);

            // getTime here should always assume that it's in the UTC context, since that's the
            // only available mode for j2me 1.3 (IE: Dates will always come out flat). We'll
            // simulate that here by offsetting.
            long offset = getOffset();

            long value = d.getTime() + offset;

            assertEquals("Fail: " + in + "(" + TimeZone.getDefault().getDisplayName() + ")", test, value);
        } catch (Exception e) {
            e.printStackTrace();
            fail("Error: " + in + e.getMessage());
        }
    }

    @Test
    public void testDateTimeParsesAcrossDateBoundary() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        DateUtils.resetTimezoneProvider();

        Date usa_eastern = DateUtils.parseDateTime("2015-04-08T22:00:01.000000-05:00");
        Date same_in_utc = DateUtils.parseDateTime("2015-04-09T03:00:01.000000Z");

        assertEquals(DateUtils.formatDateTime(usa_eastern, DateUtils.FORMAT_ISO8601),
                DateUtils.formatDateTime(same_in_utc, DateUtils.FORMAT_ISO8601));

        TimeZone.setDefault(null);
    }

    @Test
    public void testDateTimeParsesBasicFormatOffset() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        DateUtils.resetTimezoneProvider();

        Date colon = DateUtils.parseDateTime("2015-04-08T12:00:01.000000-05:00");
        Date no_colon = DateUtils.parseDateTime("2015-04-08T12:00:01.000000-0500");

        assertEquals(DateUtils.formatDateTime(colon, DateUtils.FORMAT_ISO8601),
                DateUtils.formatDateTime(no_colon, DateUtils.FORMAT_ISO8601));

        TimeZone.setDefault(null);
    }

    private long getOffset() {
        DateFields df = new DateFields();
        Date d = DateUtils.getDate(df);

        return -d.getTime();
    }

    @Test
    public void testParity() {
        testCycle(new Date(1300139579000L));
        testCycle(new Date(0));

        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

        testCycle(new Date(1300139579000L));
        testCycle(new Date(0));

        TimeZone offsetTwoHours = TimeZone.getTimeZone("GMT+02");

        TimeZone.setDefault(offsetTwoHours);

        testCycle(new Date(1300139579000L));
        testCycle(new Date(0));


        TimeZone offTwoHalf = TimeZone.getTimeZone("GMT+0230");

        TimeZone.setDefault(offTwoHalf);

        testCycle(new Date(1300139579000L));
        testCycle(new Date(0));

        TimeZone offMinTwoHalf = TimeZone.getTimeZone("GMT-0230");

        TimeZone.setDefault(offMinTwoHalf);

        testCycle(new Date(1300139579000L));
        testCycle(new Date(0));
    }

    private void testCycle(Date in) {
        try {
            String formatted = DateUtils.formatDateTime(in, DateUtils.FORMAT_ISO8601);
            Date out = DateUtils.parseDateTime(formatted);
            assertEquals("Fail:", in.getTime(), out.getTime());
        } catch (Exception e) {
            e.printStackTrace();
            fail("Error: " + in + e.getMessage());
        }
    }

    @Test
    public void testFormat() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

        Calendar novFifth2016 = Calendar.getInstance();
        novFifth2016.set(2016, Calendar.NOVEMBER, 5);
        Date novFifthDate = novFifth2016.getTime();
        DateFields novFifth2016Fields = DateUtils.getFields(novFifthDate, null);
        HashMap<String, String> escapesResults = new HashMap<>();
        escapesResults.put("%a", "Sat");
        escapesResults.put("%A", "Saturday");
        escapesResults.put("%b", "Nov");
        escapesResults.put("%B", "November");
        escapesResults.put("%d", "05");
        escapesResults.put("%e", "5");
        escapesResults.put("%w", "6");
        escapesResults.put("%Z", "Z");

        for (String escape : escapesResults.keySet()) {
            String result = escapesResults.get(escape);
            String formatted = DateUtils.format(novFifth2016Fields, escape);
            assertEquals("Fail: '" + escape + "' rendered unexpectedly", result, formatted);
        }

        boolean didFail = false;
        try {
            DateUtils.format(novFifth2016Fields, "%c");
        } catch (RuntimeException e) {
            didFail = true;
        }
        assertTrue(didFail);

        TimeZone.setDefault(null);
    }

    @Test
    public void testFormatWithDefaultTimezone() {
        String format = "%Y-%m-%d %H:%M:%S%Z";

        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        c.set(2017, 0, 2, 2, 0, 0); // Jan 2, 2017 2:00 AM in UTC
        c.set(Calendar.MILLISECOND, 0);
        Date d = c.getTime();

        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        String expectedDateTime = "2017-01-02 02:00:00Z";
        assertEquals(expectedDateTime,
                DateUtils.format(d, format));

        TimeZone.setDefault(TimeZone.getTimeZone("EST"));
        expectedDateTime = "2017-01-01 21:00:00-05";
        assertEquals(expectedDateTime,
                DateUtils.format(d, format));

        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Katmandu"));
        expectedDateTime = "2017-01-02 07:45:00+05:45";
        assertEquals(expectedDateTime,
                DateUtils.format(d, format));

        TimeZone.setDefault(TimeZone.getTimeZone("IST"));
        expectedDateTime = "2017-01-02 07:30:00+05:30";
        assertEquals(expectedDateTime,
                DateUtils.format(d, format));

        TimeZone.setDefault(null);
    }

    @Test
    public void testFormatWithProviderTimezone() {
        String format = "%Y-%m-%d %H:%M:%S%Z";

        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        c.set(2017, 0, 2, 2, 0, 0); // Jan 2, 2017 2:00 AM in UTC
        c.set(Calendar.MILLISECOND, 0);
        Date d = c.getTime();

        MockTimezoneProvider tzProvider = new MockTimezoneProvider();
        DateUtils.setTimezoneProvider(tzProvider);

        tzProvider.setOffset(0);
        String expectedDateTime1HourAhead = "2017-01-02 02:00:00Z";
        assertEquals(expectedDateTime1HourAhead,
                DateUtils.format(d, format));

        tzProvider.setOffset(HOUR_IN_MILLIS);
        expectedDateTime1HourAhead = "2017-01-02 03:00:00+01";
        assertEquals(expectedDateTime1HourAhead,
                DateUtils.format(d, format));
    }

    @Test
    public void testFormatDateTimeWithOffset() {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        c.set(2017, 0, 2, 2, 0, 0); // Jan 2, 2017 2:00 AM in UTC
        c.set(Calendar.MILLISECOND, 0);
        Date d = c.getTime();

        MockTimezoneProvider tzProvider = new MockTimezoneProvider();
        DateUtils.setTimezoneProvider(tzProvider);

        tzProvider.setOffset(HOUR_IN_MILLIS);
        String expectedDateTime1HourAhead = "2017-01-02T03:00:00.000+01";
        assertEquals(expectedDateTime1HourAhead,
                DateUtils.formatDateTime(d, DateUtils.FORMAT_ISO8601));

        tzProvider.setOffset(-3 * HOUR_IN_MILLIS);
        String expectedDateTime3HoursBehind = "2017-01-01T23:00:00.000-03";
        assertEquals(expectedDateTime3HoursBehind,
                DateUtils.formatDateTime(d, DateUtils.FORMAT_ISO8601));

        tzProvider.setOffset(0);
        String expectedDateTimeUTC = "2017-01-02T02:00:00.000Z";
        assertEquals(expectedDateTimeUTC,
                DateUtils.formatDateTime(d, DateUtils.FORMAT_ISO8601));

        DateUtils.resetTimezoneProvider();
    }

    @Test
    public void testFormatDateWithOffset() {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        c.set(2017, 0, 2, 2, 0, 0); // Jan 2, 2017 2:00 AM in UTC
        c.set(Calendar.MILLISECOND, 0);
        Date d = c.getTime();

        MockTimezoneProvider tzProvider = new MockTimezoneProvider();
        DateUtils.setTimezoneProvider(tzProvider);

        tzProvider.setOffset(HOUR_IN_MILLIS);
        String expectedDate1HourAhead = "2017-01-02";
        assertEquals(expectedDate1HourAhead,
                DateUtils.formatDate(d, DateUtils.FORMAT_ISO8601));

        tzProvider.setOffset(-3 * HOUR_IN_MILLIS);
        String expectedDate3HoursBehind = "2017-01-01";
        assertEquals(expectedDate3HoursBehind,
                DateUtils.formatDate(d, DateUtils.FORMAT_ISO8601));

        tzProvider.setOffset(0);
        String expectedDateUTC = "2017-01-02";
        assertEquals(expectedDateUTC,
                DateUtils.formatDate(d, DateUtils.FORMAT_ISO8601));

        DateUtils.resetTimezoneProvider();
    }

    @Test
    public void testFormatTimeWithOffset() {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        c.set(2017, 0, 2, 2, 0, 0); // Jan 2, 2017 2:00 AM in UTC
        c.set(Calendar.MILLISECOND, 0);
        Date d = c.getTime();

        MockTimezoneProvider tzProvider = new MockTimezoneProvider();
        DateUtils.setTimezoneProvider(tzProvider);

        tzProvider.setOffset(HOUR_IN_MILLIS);
        String expectedTime1HourAhead = "03:00:00.000+01";
        assertEquals(expectedTime1HourAhead,
                DateUtils.formatTime(d, DateUtils.FORMAT_ISO8601));

        tzProvider.setOffset(-3 * HOUR_IN_MILLIS);
        String expectedTime3HoursBehind = "23:00:00.000-03";
        assertEquals(expectedTime3HoursBehind,
                DateUtils.formatTime(d, DateUtils.FORMAT_ISO8601));

        tzProvider.setOffset(0);
        String expectedTimeUTC = "02:00:00.000Z";
        assertEquals(expectedTimeUTC,
                DateUtils.formatTime(d, DateUtils.FORMAT_ISO8601));

        DateUtils.resetTimezoneProvider();
    }

    @Test
    public void testTimeParsingWithOffset() {
        testTimeParsingHelper("UTC");
        testTimeParsingHelper("EST");
        testTimeParsingHelper("Africa/Johannesburg");
        testTimeParsingHelper("Asia/Katmandu");
    }

    private static void testTimeParsingHelper(String timezoneId) {
        Calendar c = Calendar.getInstance();
        TimeZone tz = TimeZone.getTimeZone(timezoneId);
        c.setTimeZone(tz);
        c.set(1970, 0, 1, 22, 0, 0); // Jan 1, 1970 22:00
        c.set(Calendar.MILLISECOND, 0);
        Date expectedDate = c.getTime();

        MockTimezoneProvider tzProvider = new MockTimezoneProvider();
        DateUtils.setTimezoneProvider(tzProvider);
        tzProvider.setOffset(tz.getOffset(expectedDate.getTime()));

        assertEquals(expectedDate.getTime(), DateUtils.parseTime("22:00").getTime());
        DateUtils.resetTimezoneProvider();
    }

    @Test
    public void testDateTimeParsingWithOffset() {
        testDateTimeParsingHelper("UTC");
        testDateTimeParsingHelper("EST");
        testDateTimeParsingHelper("Africa/Johannesburg");
        testDateTimeParsingHelper("Asia/Katmandu");
    }

    private static void testDateTimeParsingHelper(String timezoneId) {
        Calendar c = Calendar.getInstance();
        TimeZone tz = TimeZone.getTimeZone(timezoneId);
        c.setTimeZone(tz);
        c.set(2017, 0, 2, 2, 0, 0); // Jan 2, 2017 02:00
        c.set(Calendar.MILLISECOND, 0);
        Date expectedDate = c.getTime();

        MockTimezoneProvider tzProvider = new MockTimezoneProvider();
        DateUtils.setTimezoneProvider(tzProvider);
        tzProvider.setOffset(tz.getOffset(expectedDate.getTime()));

        assertEquals(expectedDate.getTime(),
                DateUtils.parseDateTime("2017-01-02T02:00:00").getTime());
        DateUtils.resetTimezoneProvider();
    }

    @Test
    public void testGetOffsetString() {
        int offset = 6 * 60 * 60 * 1000;
        String expected = "+06";
        offsetStringTestHelper(offset, expected);

        offset = 0;
        expected = "Z";
        offsetStringTestHelper(offset, expected);

        offset = (int)(-5.5 * 60 * 60 * 1000);
        expected = "-05:30";
        offsetStringTestHelper(offset, expected);

        offset = (int)(5.75 * 60 * 60 * 1000);
        expected = "+05:45";
        offsetStringTestHelper(offset, expected);
    }

    private static void offsetStringTestHelper(int offsetInMillis, String expectedOffsetString) {
        assertEquals(expectedOffsetString, DateUtils.getOffsetInStandardFormat(offsetInMillis));
    }

}
