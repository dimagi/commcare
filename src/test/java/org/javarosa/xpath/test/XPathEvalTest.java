package org.javarosa.xpath.test;

import org.commcare.util.EncryptionUtils;
import org.javarosa.core.model.condition.EvaluationContext;
import org.javarosa.core.model.condition.IFunctionHandler;
import org.javarosa.core.model.data.IAnswerData;
import org.javarosa.core.model.data.IntegerData;
import org.javarosa.core.model.data.StringData;
import org.javarosa.core.model.instance.FormInstance;
import org.javarosa.core.model.instance.TreeElement;
import org.javarosa.core.model.instance.TreeReference;
import org.javarosa.core.model.utils.DateUtils;
import org.javarosa.model.xform.XPathReference;
import org.javarosa.xpath.IExprDataType;
import org.javarosa.xpath.XPathArityException;
import org.javarosa.xpath.XPathException;
import org.javarosa.xpath.XPathParseTool;
import org.javarosa.xpath.XPathTypeMismatchException;
import org.javarosa.xpath.XPathUnhandledException;
import org.javarosa.xpath.XPathUnsupportedException;
import org.javarosa.xpath.expr.XPathEqExpr;
import org.javarosa.xpath.expr.XPathExpression;
import org.javarosa.xpath.expr.XPathNumericLiteral;
import org.javarosa.xpath.expr.XPathPathExpr;
import org.javarosa.xpath.expr.FunctionUtils;
import org.javarosa.xpath.parser.XPathSyntaxException;
import org.junit.Assert;
import org.junit.Test;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Date;
import java.util.Vector;

import static org.junit.Assert.fail;

public class XPathEvalTest {
    public static final double DOUBLE_TOLERANCE = 1.0e-12;

    private void testEval(String expr, FormInstance model, EvaluationContext ec, Object expected) {
        XPathExpression xpe;
        boolean exceptionExpected = expected instanceof XPathException || expected instanceof XPathSyntaxException;
        if (ec == null) {
            ec = new EvaluationContext(model);
        }

        try {
            xpe = XPathParseTool.parseXPath(expr);
        } catch (XPathSyntaxException e) {
            assertExceptionExpected(exceptionExpected, expected, e);
            return;
        }

        if (xpe == null) {
            fail("Null expression or syntax error " + expr);
        }

        try {
            Object result = FunctionUtils.unpack(xpe.eval(model, ec));

            if (exceptionExpected) {
                fail("Expected exception, expression : " + expr);
            } else if ((result instanceof Double && expected instanceof Double)) {
                Double o = (Double)result;
                Double t = (Double)expected;
                if (Math.abs(o - t) > DOUBLE_TOLERANCE) {
                    fail("Doubles outside of tolerance [" + o + "," + t + " ]");
                } else if (Double.isNaN(o) && !Double.isNaN(t)) {
                    fail("Result was NaN when not expected");
                } else if (Double.isNaN(t) && !Double.isNaN(o)) {
                    fail("Result was supposed to be NaN, but got " + o);
                }
            } else if (!expected.equals(result)) {
                fail("Expected " + expected + ", got " + result);
            }
        } catch (XPathException xpex) {
            assertExceptionExpected(exceptionExpected, expected, xpex);
        }
    }

    private Object evalExpr(String expr, FormInstance model,
                            EvaluationContext ec) throws Exception {
        XPathExpression xpe = null;
        if (ec == null) {
            ec = new EvaluationContext(model);
        }

        xpe = XPathParseTool.parseXPath(expr);

        if (xpe == null) {
            fail("Null expression or syntax error " + expr);
        }

        return FunctionUtils.unpack(xpe.eval(model, ec));
    }

    private void assertExceptionExpected(boolean exceptionExpected, Object expected, Exception xpex) {
        if (!exceptionExpected) {
            xpex.printStackTrace();
            fail("Did not expect " + xpex.getClass() + " exception");
        } else if (xpex.getClass() != expected.getClass()) {
            xpex.printStackTrace();
            fail("Expected " + expected.getClass() +
                    " exception type but was provided " + xpex.getClass());
        }
    }

    @Test
    public void testTypeCoercion() {
        Object str = FunctionUtils.InferType("notadouble");
        Assert.assertTrue("'notadouble' coerced to the wrong type, "
                + str.getClass().toString(), str instanceof String);

        Object d = FunctionUtils.InferType("5.0");

        Assert.assertTrue("'5.0' coerced to the wrong type, "
                + d.getClass().toString(), d instanceof Double);
    }

    @Test
    public void sortTests() {
        // simple sort
        testEval("sort('commcare is the best tool ever', false())", null, null, "tool the is ever commcare best");

        // sort 2nd list by 1st
        testEval("sort-by('2222 5555 9999 1111', 'd b c a', true())", null, null, "1111 5555 9999 2222");
        testEval("sort-by('a b c d e f', '4 2 1 5 3 2', false())", null, null, "d a e f b c");
        testEval("sort-by('c c z f z f', '4 2 1 5 3 2', true())", null, null, "z c f z c f");

        // ascending bool not explicitly included
        testEval("sort-by('a b c d e f', '4 2 1 5 3 2')", null, null, "c b f e a d");
        testEval("sort('commcare is the best tool ever')", null, null, "best commcare ever is the tool");

        // uneven list sizes
        testEval("sort-by('a b c', '4 2 5 1', true())", null, null, new XPathTypeMismatchException());
    }

    @Test
    public void testRegex() {
        EvaluationContext ec = getFunctionHandlers();
        testEval("regex('12345','[0-9]+')", null, ec, Boolean.TRUE);
        testEval("regex('12345','[')", null, ec, new XPathException());
        testEval("regex('aaaabfooaaabgarplyaaabwackyb', 'a*b')", null, null, Boolean.TRUE);
        testEval("regex('photo', 'a*b')", null, null, Boolean.FALSE);
        testEval("regex('Is this right?', 'is')", null, null, Boolean.TRUE);
        testEval("regex('Is this right?', '^is')", null, null, Boolean.FALSE);
        testEval("regex('Is this right?', '^Is this right?$')", null, null, Boolean.FALSE);
        testEval("regex('Is this right?', '^Is this right\\?$')", null, null, Boolean.TRUE);
        testEval("regex('Dollar sign\ndoes not match newlines', 'sign$')", null, null, Boolean.FALSE);
        testEval("regex('Dollar sign\ndoes not match newlines', 'newlines$')", null, null, Boolean.TRUE);
        testEval("regex('cocotero', 'cocotero')", null, null,Boolean.TRUE);
        testEval("regex('cocotero', 'te')", null, null,Boolean.TRUE);
    }

    @Test
    public void doTests() {
        System.setProperty("user.timezone", "UTC");
        EvaluationContext ec = getFunctionHandlers();

        FormInstance instance = createTestInstance();

        /* unsupporteds */
        testEval("/union | /expr", null, null, new XPathUnsupportedException());
        testEval("/descendant::blah", null, null, new XPathUnsupportedException());
        testEval("/cant//support", null, null, new XPathUnsupportedException());
        testEval("/text()", null, null, new XPathUnsupportedException());
        testEval("/namespace:*", null, null, new XPathUnsupportedException());
        testEval("(filter-expr)[5]", instance, null, new XPathUnsupportedException());
        testEval("(filter-expr)/data", instance, null, new XPathUnsupportedException());
        /* numeric literals */
        testEval("5", null, null, new Double(5.0));
        testEval("555555.555", null, null, new Double(555555.555));
        testEval(".000555", null, null, new Double(0.000555));
        testEval("0", null, null, new Double(0.0));
        testEval("-5", null, null, new Double(-5.0));
        testEval("-0", null, null, new Double(-0.0));
        testEval("1230000000000000000000", null, null, new Double(1.23e21));
        testEval("0.00000000000000000123", null, null, new Double(1.23e-18));
        /* string literals */
        testEval("''", null, null, "");
        testEval("'\"'", null, null, "\"");
        testEval("\"test string\"", null, null, "test string");
        testEval("'   '", null, null, "   ");
        /* base type conversion functions */
        testEval("true()", null, null, Boolean.TRUE);
        testEval("false()", null, null, Boolean.FALSE);
        testEval("boolean(true())", null, null, Boolean.TRUE);
        testEval("boolean(false())", null, null, Boolean.FALSE);
        testEval("boolean(1)", null, null, Boolean.TRUE);
        testEval("boolean(-1)", null, null, Boolean.TRUE);
        testEval("boolean(0.0001)", null, null, Boolean.TRUE);
        testEval("boolean(0)", null, null, Boolean.FALSE);
        testEval("boolean(-0)", null, null, Boolean.FALSE);
        testEval("boolean(number('NaN'))", null, null, Boolean.FALSE);
        testEval("boolean(1 div 0)", null, null, Boolean.TRUE);
        testEval("boolean(-1 div 0)", null, null, Boolean.TRUE);
        testEval("boolean('')", null, null, Boolean.FALSE);
        testEval("boolean('asdf')", null, null, Boolean.TRUE);
        testEval("boolean('  ')", null, null, Boolean.TRUE);
        testEval("boolean('false')", null, null, Boolean.TRUE);
        testEval("boolean(date('2000-01-01'))", null, null, Boolean.TRUE);
        testEval("boolean(convertible())", null, ec, Boolean.TRUE);
        testEval("boolean(inconvertible())", null, ec, new XPathTypeMismatchException());
        testEval("number(true())", null, null, new Double(1.0));
        testEval("number(false())", null, null, new Double(0.0));
        testEval("number('100')", null, null, new Double(100.0));
        testEval("number('100.001')", null, null, new Double(100.001));
        testEval("number('.1001')", null, null, new Double(0.1001));
        testEval("number('1230000000000000000000')", null, null, new Double(1.23e21));
        testEval("number('0.00000000000000000123')", null, null, new Double(1.23e-18));
        testEval("number('0')", null, null, new Double(0.0));
        testEval("number('-0')", null, null, new Double(-0.0));
        testEval("number(' -12345.6789  ')", null, null, new Double(-12345.6789));
        testEval("number('NaN')", null, null, new Double(Double.NaN));
        testEval("number('not a number')", null, null, new Double(Double.NaN));
        testEval("number('- 17')", null, null, new Double(Double.NaN));
        testEval("number('  ')", null, null, new Double(Double.NaN));
        testEval("number('')", null, null, new Double(Double.NaN));
        testEval("number('Infinity')", null, null, new Double(Double.NaN));
        testEval("number('1.1e6')", null, null, new Double(Double.NaN));
        testEval("number('34.56.7')", null, null, new Double(Double.NaN));
        testEval("number(10)", null, null, new Double(10.0));
        testEval("number(0)", null, null, new Double(0.0));
        testEval("number(-0)", null, null, new Double(-0.0));
        testEval("number(-123.5)", null, null, new Double(-123.5));
        testEval("number(number('NaN'))", null, null, new Double(Double.NaN));
        testEval("number(1 div 0)", null, null, new Double(Double.POSITIVE_INFINITY));
        testEval("number(-1 div 0)", null, null, new Double(Double.NEGATIVE_INFINITY));
        testEval("number(date('1970-01-01'))", null, null, new Double(0.0));
        testEval("number(date('1970-01-02'))", null, null, new Double(1.0));
        testEval("number(date('1969-12-31'))", null, null, new Double(-1.0));
        testEval("number(date('2008-09-05'))", null, null, new Double(14127.0));
        testEval("number(date('1941-12-07'))", null, null, new Double(-10252.0));
        testEval("number('1970-01-01')", null, null, new Double(0.0));
        testEval("number('1970-01-02')", null, null, new Double(1.0));
        testEval("number('1969-12-31')", null, null, new Double(-1.0));
        testEval("number('2008-09-05')", null, null, new Double(14127.0));
        testEval("number('1941-12-07')", null, null, new Double(-10252.0));
        testEval("number('1970-01')", null, null, new Double(Double.NaN));
        testEval("number('-1970-01-02')", null, null, new Double(Double.NaN));
        testEval("number('12-31')", null, null, new Double(Double.NaN));
        testEval("number('2016-13-13')", null, null, new Double(Double.NaN));
        testEval("number('2017-01-45')", null, null, new Double(Double.NaN));

        testEval("number(convertible())", null, ec, new Double(5.0));
        testEval("number(inconvertible())", null, ec, new XPathTypeMismatchException());
        testEval("string(true())", null, null, "true");
        testEval("string(false())", null, null, "false");
        testEval("string(number('NaN'))", null, null, "NaN");
        testEval("string(1 div 0)", null, null, "Infinity");
        testEval("string(-1 div 0)", null, null, "-Infinity");
        testEval("string(0)", null, null, "0");
        testEval("string(-0)", null, null, "0");
        testEval("string(123456.0000)", null, null, "123456");
        testEval("string(-123456)", null, null, "-123456");
        testEval("string(1)", null, null, "1");
        testEval("string(-1)", null, null, "-1");
        testEval("string(.557586)", null, null, "0.557586");
        //broken: testEval("string(1230000000000000000000)", null, null, "1230000000000000000000");
        //broken: testEval("string(0.00000000000000000123)", null, null, "0.00000000000000000123");
        testEval("string('')", null, null, "");
        testEval("string('  ')", null, null, "  ");
        testEval("string('a string')", null, null, "a string");
        testEval("string(date('1989-11-09'))", null, null, "1989-11-09");
        testEval("string(convertible())", null, ec, "hi");
        testEval("string(inconvertible())", null, ec, new XPathTypeMismatchException());
        testEval("substr('hello',0)", null, null, "hello");
        testEval("substr('hello',0,5)", null, null, "hello");
        testEval("substr('hello',1)", null, null, "ello");
        testEval("substr('hello',1,5)", null, null, "ello");
        testEval("substr('hello',1,4)", null, null, "ell");
        testEval("substr('hello',-2)", null, null, "lo");
        testEval("substr('hello',0,-1)", null, null, "hell");
        testEval("substr('',0,1)", null, null, "");
        testEval("substr('hello',0,8)", null, null, "");
        testEval("date('2000-01-01')", null, null, DateUtils.getDate(2000, 1, 1));
        testEval("date('1945-04-26')", null, null, DateUtils.getDate(1945, 4, 26));
        testEval("date('1996-02-29')", null, null, DateUtils.getDate(1996, 2, 29));
        testEval("date('1983-09-31')", null, null, new XPathTypeMismatchException());
        testEval("date('not a date')", null, null, new XPathTypeMismatchException());
        testEval("date(0)", null, null, DateUtils.getDate(1970, 1, 1));
        testEval("date(6.5)", null, null, DateUtils.getDate(1970, 1, 7));
        testEval("date(1)", null, null, DateUtils.getDate(1970, 1, 2));
        testEval("date(-1)", null, null, DateUtils.getDate(1969, 12, 31));
        testEval("date(14127)", null, null, DateUtils.getDate(2008, 9, 5));
        testEval("date(-10252)", null, null, DateUtils.getDate(1941, 12, 7));
        testEval("date(date('1989-11-09'))", null, null, DateUtils.getDate(1989, 11, 9));
        testEval("date(true())", null, null, new XPathTypeMismatchException());
        testEval("date(convertible())", null, ec, new XPathTypeMismatchException());
        testEval("format-date-for-calendar('', 'ethiopian')", null, null, "");
        testEval("format-date-for-calendar(date('1970-01-01'), 'neverland')", null, null, new XPathUnsupportedException());

        testEval("format-date-for-calendar('2017-07-15', 'ethiopian', '%Y-%m-%d')", null, null, "2009-11-08");
        testEval("format-date-for-calendar('2017-07-15', 'nepali', '%Y-%m-%d')", null, null, "2074-03-31");


        //note: there are lots of time and timezone-like issues with dates that should be tested (particularly DST changes),
        //    but it's just too hard and client-dependent, so not doing it now
        //  basically:
        //        dates cannot reliably be compared/used across time zones (an issue with the code)
        //        any time-of-day or DST should be ignored when comparing/using a date (an issue with testing)
        /* other built-in functions */
        testEval("not(true())", null, null, Boolean.FALSE);
        testEval("not(false())", null, null, Boolean.TRUE);
        testEval("not('')", null, null, Boolean.TRUE);
        testEval("boolean-from-string('true')", null, null, Boolean.TRUE);
        testEval("boolean-from-string('false')", null, null, Boolean.FALSE);
        testEval("boolean-from-string('whatever')", null, null, Boolean.FALSE);
        testEval("boolean-from-string('1')", null, null, Boolean.TRUE);
        testEval("boolean-from-string('0')", null, null, Boolean.FALSE);
        testEval("boolean-from-string(1)", null, null, Boolean.TRUE);
        testEval("boolean-from-string(1.0)", null, null, Boolean.TRUE);
        testEval("boolean-from-string(1.0001)", null, null, Boolean.FALSE);
        testEval("boolean-from-string(true())", null, null, Boolean.TRUE);
        testEval("if(true())", null, null, new XPathSyntaxException());
        testEval("if(true(), 5, 'abc')", null, null, new Double(5.0));
        testEval("if(false(), 5, 'abc')", null, null, "abc");
        testEval("if(6 > 7, 5, 'abc')", null, null, "abc");
        testEval("if('', 5, 'abc')", null, null, "abc");
        testEval("selected('apple baby crimson', 'apple')", null, null, Boolean.TRUE);
        testEval("selected('apple baby crimson', 'baby')", null, null, Boolean.TRUE);
        testEval("selected('apple baby crimson', 'crimson')", null, null, Boolean.TRUE);
        testEval("selected('apple baby crimson', '  baby  ')", null, null, Boolean.TRUE);
        testEval("selected('apple baby crimson', 'babby')", null, null, Boolean.FALSE);
        testEval("selected('apple baby crimson', 'bab')", null, null, Boolean.FALSE);
        testEval("selected('apple', 'apple')", null, null, Boolean.TRUE);
        testEval("selected('apple', 'ovoid')", null, null, Boolean.FALSE);
        testEval("selected('', 'apple')", null, null, Boolean.FALSE);
        /* operators */

        testEval("min(5.5, 0.5)", null, null, new Double(0.5));
        testEval("min(5.5)", null, null, new Double(5.5));
        testEval("min(-2,-3)", null, null, new Double(-3));
        testEval("min(2,-3)", null, null, new Double(-3));
        testEval("date(min(date('2012-02-05'), date('2012-01-01')))", null, null, DateUtils.parseDate("2012-01-01"));

        testEval("max(5.5, 0.5)", null, null, new Double(5.5));
        testEval("max(0.5)", null, null, new Double(0.5));
        testEval("max(-2,-3)", null, null, new Double(-2));
        testEval("max(2,-3)", null, null, new Double(2));
        testEval("date(max(date('2012-02-05'), date('2012-01-01')))", null, null, DateUtils.parseDate("2012-02-05"));


        // Test that taking the min or max of date-strings works, but still fails properly for
        // numeric strings that are not dates
        testEval("min('2012-02-05', '2012-01-01', '2012-04-20')", null, null,
                new Double(DateUtils.daysSinceEpoch(DateUtils.parseDate("2012-01-01"))));
        testEval("max('2012-02-05', '2012-01-01', '2012-04-20')", null, null,
                new Double(DateUtils.daysSinceEpoch(DateUtils.parseDate("2012-04-20"))));
        testEval("max('-1-02-05', '2012-01-01', '2012-04-20')", null, null,
                new Double(Double.NaN));
        testEval("max('02-05', '2012-01-01', '2012-04-20')", null, null,
                new Double(Double.NaN));
        testEval("max('2012-14-05', '2012-01-01', '2012-04-20')", null, null,
                new Double(Double.NaN));


        testEval("5.5 + 5.5", null, null, new Double(11.0));
        testEval("0 + 0", null, null, new Double(0.0));
        testEval("6.1 - 7.8", null, null, new Double(-1.7));
        testEval("-3 + 4", null, null, new Double(1.0));
        testEval("3 + -4", null, null, new Double(-1.0));
        testEval("1 - 2 - 3", null, null, new Double(-4.0));
        testEval("1 - (2 - 3)", null, null, new Double(2.0));
        testEval("-(8*5)", null, null, new Double(-40.0));
        testEval("-'19'", null, null, new Double(-19.0));
        testEval("1.1 * -1.1", null, null, new Double(-1.21));
        testEval("-10 div -4", null, null, new Double(2.5));
        testEval("2 * 3 div 8 * 2", null, null, new Double(1.5));
        testEval("3 + 3 * 3", null, null, new Double(12.0));
        testEval("1 div 0", null, null, new Double(Double.POSITIVE_INFINITY));
        testEval("-1 div 0", null, null, new Double(Double.NEGATIVE_INFINITY));
        testEval("0 div 0", null, null, new Double(Double.NaN));
        testEval("3.1 mod 3.1", null, null, new Double(0.0));
        testEval("5 mod 3.1", null, null, new Double(1.9));
        testEval("2 mod 3.1", null, null, new Double(2.0));
        testEval("0 mod 3.1", null, null, new Double(0.0));
        testEval("5 mod -3", null, null, new Double(2.0));
        testEval("-5 mod 3", null, null, new Double(-2.0));
        testEval("-5 mod -3", null, null, new Double(-2.0));
        testEval("5 mod 0", null, null, new Double(Double.NaN));
        testEval("5 * (6 + 7)", null, null, new Double(65.0));
        testEval("'123' * '456'", null, null, new Double(56088.0));
        testEval("true() + 8", null, null, new Double(9.0));
        testEval("date('2008-09-08') - date('1983-10-06')", null, null, new Double(9104.0));
        testEval("true() and true()", null, null, Boolean.TRUE);
        testEval("true() and false()", null, null, Boolean.FALSE);
        testEval("false() and false()", null, null, Boolean.FALSE);
        testEval("true() or true()", null, null, Boolean.TRUE);
        testEval("true() or false()", null, null, Boolean.TRUE);
        testEval("false() or false()", null, null, Boolean.FALSE);
        testEval("true() or true() and false()", null, null, Boolean.TRUE);
        testEval("(true() or true()) and false()", null, null, Boolean.FALSE);
        testEval("true() or date('')", null, null, Boolean.TRUE); //short-circuiting
        testEval("false() and date('')", null, null, Boolean.FALSE); //short-circuiting
        testEval("'' or 17", null, null, Boolean.TRUE);
        testEval("false() or 0 + 2", null, null, Boolean.TRUE);
        testEval("(false() or 0) + 2", null, null, new Double(2.0));
        testEval("4 < 5", null, null, Boolean.TRUE);
        testEval("5 < 5", null, null, Boolean.FALSE);
        testEval("6 < 5", null, null, Boolean.FALSE);
        testEval("4 <= 5", null, null, Boolean.TRUE);
        testEval("5 <= 5", null, null, Boolean.TRUE);
        testEval("6 <= 5", null, null, Boolean.FALSE);
        testEval("4 > 5", null, null, Boolean.FALSE);
        testEval("5 > 5", null, null, Boolean.FALSE);
        testEval("6 > 5", null, null, Boolean.TRUE);
        testEval("4 >= 5", null, null, Boolean.FALSE);
        testEval("5 >= 5", null, null, Boolean.TRUE);
        testEval("6 >= 5", null, null, Boolean.TRUE);
        testEval("-3 > -6", null, null, Boolean.TRUE);
        testEval("true() > 0.9999", null, null, Boolean.TRUE);
        testEval("'-17' > '-172'", null, null, Boolean.TRUE); //no string comparison: converted to number
        testEval("'abc' < 'abcd'", null, null, Boolean.FALSE); //no string comparison: converted to NaN
        testEval("date('2001-12-26') > date('2001-12-25')", null, null, Boolean.TRUE);
        testEval("date('1969-07-20') < date('1969-07-21')", null, null, Boolean.TRUE);

        testEval("double(date('2004-05-01T05:00:00')) > double(date('2004-05-01T02:00:00'))", null, null, Boolean.TRUE);
        testEval("not(double(date('2004-05-01T05:00:00')) < double(date('2004-05-01T02:00:00')))", null, null, Boolean.TRUE);
        testEval("not(double(date('2004-05-01T05:00:00')) = double(date('2004-05-01T02:00:00')))", null, null, Boolean.TRUE);
        testEval("double(date('2004-05-01T04:00:00')) < double(date('2004-05-01T016:00:00'))", null, null, Boolean.TRUE);

        testEval("(double(date('2004-05-01T07:00:00')) - double(date('2004-05-01T03:00:00'))) < (6.0 div 24) ", null, null, Boolean.TRUE);
        testEval("(double(date('2004-05-01T07:00:00')) - double(date('2004-05-01T00:30:00'))) > (6.0 div 24) ", null, null, Boolean.TRUE);
        testEval("(double(date('2004-05-03T07:00:00')) - double(date('2004-05-01T03:00:00'))) > (6.0 div 24) ", null, null, Boolean.TRUE);

        testEval("abs(-3.5)", null, null, new Double(3.5));
        testEval("abs(2)", null, null, new Double(2.0));
        testEval("floor(-4.8)", null, null, new Double(-5.0));
        testEval("floor(100.2)", null, null, new Double(100.0));
        testEval("ceiling(-0.5)", null, null, new Double(0.0));
        testEval("ceiling(10.4)", null, null, new Double(11.0));
        testEval("round(1.5)", null, null, new Double(2.0));
        testEval("round(-1.5)", null, null, new Double(-1.0));
        testEval("round(1.455)", null, null, new Double(1.0));

        testEval("log(" + Math.E + ")", null, null, new Double(1.0));
        testEval("log(1)", null, null, new Double(0.0));
        testEval("log10(100)", null, null, new Double(2.0));
        testEval("log10(1)", null, null, new Double(0.0));


        testEval("pow(2, 2)", null, null, new Double(4.0));
        testEval("pow(2, 0)", null, null, new Double(1.0));
        testEval("pow(0, 4)", null, null, new Double(0.0));
        testEval("pow(2.5, 2)", null, null, new Double(6.25));
        testEval("pow(0.5, 2)", null, null, new Double(.25));

        testEval("pow(-1, 2)", null, null, new Double(1.0));
        testEval("pow(-1, 3)", null, null, new Double(-1.0));
        testEval("sin(0)", null, null, 0.0);
        testEval("cos(0)", null, null, 1.0);
        testEval("tan(0)", null, null, 0.0);
        testEval("asin(0)", null, null, 0.0);
        testEval("acos(1)", null, null, 0.0);
        testEval("atan(0)", null, null, 0.0);
        testEval("atan2(0, 0)", null, null, 0.0);
        testEval("sqrt(4)", null, null, 2.0);
        testEval("exp(1)", null, null, Math.E);
        testEval("pi()", null, null, Math.PI);

        //So raising things to decimal powers is.... very hard
        //to evaluated exactly due to double floating point
        //precision. We'll try for things with clean answers
        //testEval("pow(4, 0.5)", null, null, new Double(2.0), .001);
        //testEval("pow(16, 0.25)", null, null, new Double(2.0), .001);
        //CTS: We're going to skip trying to do any sort of hackery workaround
        //for this for now and go with "Integer powers only"

        testEval("false() and false() < true()", null, null, Boolean.FALSE);
        testEval("(false() and false()) < true()", null, null, Boolean.TRUE);
        testEval("6 < 7 - 4", null, null, Boolean.FALSE);
        testEval("(6 < 7) - 4", null, null, new Double(-3.0));
        testEval("3 < 4 < 5", null, null, Boolean.TRUE);
        testEval("3 < (4 < 5)", null, null, Boolean.FALSE);
        testEval("true() = true()", null, null, Boolean.TRUE);
        testEval("true() = false()", null, null, Boolean.FALSE);
        testEval("true() != true()", null, null, Boolean.FALSE);
        testEval("true() != false()", null, null, Boolean.TRUE);
        testEval("3 = 3", null, null, Boolean.TRUE);
        testEval("3 = 4", null, null, Boolean.FALSE);
        testEval("3 != 3", null, null, Boolean.FALSE);
        testEval("3 != 4", null, null, Boolean.TRUE);
        testEval("6.1 - 7.8 = -1.7", null, null, Boolean.TRUE); //handle floating point rounding
        testEval("'abc' = 'abc'", null, null, Boolean.TRUE);
        testEval("'abc' = 'def'", null, null, Boolean.FALSE);
        testEval("'abc' != 'abc'", null, null, Boolean.FALSE);
        testEval("'abc' != 'def'", null, null, Boolean.TRUE);
        testEval("'' = ''", null, null, Boolean.TRUE);
        testEval("true() = 17", null, null, Boolean.TRUE);
        testEval("0 = false()", null, null, Boolean.TRUE);
        testEval("true() = 'true'", null, null, Boolean.TRUE);
        testEval("17 = '17.0000000'", null, null, Boolean.TRUE);
        testEval("'0017.' = 17", null, null, Boolean.TRUE);
        testEval("'017.' = '17.000'", null, null, Boolean.FALSE);
        testEval("date('2004-05-01') = date('2004-05-01')", null, null, Boolean.TRUE);
        testEval("true() != date('1999-09-09')", null, null, Boolean.FALSE);
        testEval("false() and true() != true()", null, null, Boolean.FALSE);
        testEval("(false() and true()) != true()", null, null, Boolean.TRUE);
        testEval("-3 < 3 = 6 >= 6", null, null, Boolean.TRUE);
        /* functions, including custom function handlers */
        testEval("weighted-checklist(5)", null, null, new XPathArityException());
        testEval("weighted-checklist(5, 5, 5)", null, null, new XPathArityException());
        testEval("substr('hello')", null, null, new XPathArityException());
        testEval("join()", null, null, new XPathArityException());
        testEval("substring-before()", null, null, new XPathArityException());
        testEval("substring-after()", null, null, new XPathArityException());
        testEval("string-length('123')", null, null, 3.0);
        testEval("join(',', '1', '2')", null, null, "1,2");
        testEval("join-chunked('-', 3, 'AA', 'BBB', 'C')", null, null, "AAB-BBC");
        testEval("join-chunked('-', 3, 'AA', 'BBB', 'CC')", null, null, "AAB-BBC-C");
        testEval("join-chunked('-', 3, 'AA')", null, null, "AA");
        testEval("join-chunked('-', 3, 'AAA')", null, null, "AAA");
        testEval("depend()", null, null, new XPathArityException());
        testEval("depend('1', '2')", null, null, "1");
        testEval("uuid('1', '2')", null, null, new XPathArityException());
        testEval("max()", null, null, new XPathArityException());
        testEval("min()", null, null, new XPathArityException());
        testEval("true(5)", null, null, new XPathArityException());
        testEval("number()", null, null, new XPathArityException());
        testEval("string('too', 'many', 'args')", null, null, new XPathArityException());
        testEval("not-a-function()", null, null, new XPathUnhandledException());
        testEval("testfunc()", null, ec, Boolean.TRUE);
        testEval("add(3, 5)", null, ec, new Double(8.0));
        testEval("add('17', '-14')", null, ec, new Double(3.0));
        // proto not setup for 0 arguments. Note that Arity is a parse exception, so we expect this
        // to get wrapped
        testEval("proto()", null, ec, new XPathTypeMismatchException());
        testEval("proto(5, 5)", null, ec, "[Double:5.0,Double:5.0]");
        testEval("proto(6)", null, ec, "[Double:6.0]");
        testEval("proto('asdf')", null, ec, "[Double:NaN]");
        testEval("proto('7', '7')", null, ec, "[Double:7.0,Double:7.0]"); //note: args treated as doubles because
        //(double, double) prototype takes precedence and strings are convertible to doubles
        testEval("proto(1.1, 'asdf', true())", null, ec, "[Double:1.1,String:asdf,Boolean:true]");
        testEval("proto(false(), false(), false())", null, ec, "[Double:0.0,String:false,Boolean:false]");
        testEval("proto(1.1, 'asdf', inconvertible())", null, ec, new XPathTypeMismatchException());

        // proto not setup for 4 arguments. Note that Arity is a parse exception, so we expect this
        // to get wrapped
        testEval("proto(1.1, 'asdf', true(), 16)", null, ec, new XPathTypeMismatchException());

        testEval("position(1.1, 'asdf')", null, ec, new XPathArityException());
        testEval("sum(1)", null, ec, new XPathTypeMismatchException());

        testEval("raw()", null, ec, "[]");
        testEval("raw(5, 5)", null, ec, "[Double:5.0,Double:5.0]");
        testEval("raw('7', '7')", null, ec, "[String:7,String:7]");
        testEval("raw('1.1', 'asdf', 17)", null, ec, "[Double:1.1,String:asdf,Boolean:true]"); //convertible to prototype
        testEval("raw(get-custom(false()), get-custom(true()))", null, ec, "[CustomType:,CustomSubType:]");
        testEval("concat()", null, ec, "");
        testEval("concat('a')", null, ec, "a");
        testEval("concat('a','b','')", null, ec, "ab");
        testEval("concat('ab','cde','','fgh',1,false(),'ijklmnop')", null, ec, "abcdefgh1falseijklmnop");
        testEval("check-types(55, '55', false(), '1999-09-09', get-custom(false()))", null, ec, Boolean.TRUE);
        testEval("check-types(55, '55', false(), '1999-09-09', get-custom(true()))", null, ec, Boolean.TRUE);
        testEval("upper-case('SimpLY')", null, null, "SIMPLY");
        testEval("lower-case('rEd')", null, null, "red");
        testEval("contains('', 'stuff')", null, null, Boolean.FALSE);
        testEval("contains('stuff', '')", null, null, Boolean.TRUE);
        testEval("contains('know', 'now')", null, null, Boolean.TRUE);
        testEval("contains('now', 'know')", null, null, Boolean.FALSE);
        testEval("starts-with('finish', 'fin')", null, null, Boolean.TRUE);
        testEval("starts-with('keep', '')", null, null, Boolean.TRUE);
        testEval("starts-with('why', 'y')", null, null, Boolean.FALSE);
        testEval("ends-with('elements', 'nts')", null, null, Boolean.TRUE);
        testEval("ends-with('elements', 'xenon')", null, null, Boolean.FALSE);
        testEval("translate('aBcdE', 'xyz', 'qrs')", null, null, "aBcdE");
        testEval("translate('bosco', 'bos', 'sfo')", null, null, "sfocf");
        testEval("translate('ramp', 'mapp', 'nbqr')", null, null, "rbnq");
        testEval("translate('yellow', 'low', 'or')", null, null, "yeoor");
        testEval("translate('bora bora', 'a', 'bc')", null, null, "borb borb");
        testEval("translate('squash me', 'aeiou ', '')", null, null, "sqshm");
        testEval("replace('aaaabfooaaabgarplyaaabwackyb', 'a*b', '-')", null, null, "-foo-garply-wacky-");
        testEval("replace('abbc', 'a(.*)c', '$1')", null, null, "$1");
        testEval("replace('aaabb', '[ab][ab][ab]', '')", null, null, "bb");
        testEval("replace('12345','[', '')", null, ec, new XPathException());
        testEval("checklist('12345')", null, ec, new XPathArityException());
        testEval("weighted-checklist('12345')", null, ec, new XPathArityException());
        testEval("id-compress(0, 'CD','AB','ABCDE',1)", null, null, "AA");
        testEval("id-compress(9, 'CD','AB','ABCDE',1)", null, null, "BE");
        testEval("id-compress(10, 'CD','AB','ABCDE',1)", null, null, "DAA");

        testEval("id-compress(0, 'CD','','ABCDE',1)", null, ec, new XPathException());
        testEval("id-compress(0, 'CD','CD','ABCDE',1)", null, ec, new XPathException());

        testEval("checksum('verhoeff','41310785898')", null, null, "4");
        testEval("checksum('verhoeff','66671496237')", null, null, "3");
        testEval("checksum('verhoeff','*1310785898')", null, null, new XPathUnsupportedException());
        testEval("checksum('verhoefffff','41310785898')", null, null, new XPathUnsupportedException());

        //Variables
        EvaluationContext varContext = getVariableContext();
        testEval("$var_float_five", null, varContext, new Double(5.0));
        testEval("$var_string_five", null, varContext, "five");
        testEval("$var_int_five", null, varContext, new Double(5.0));
        testEval("$var_double_five", null, varContext, new Double(5.0));

        //Attribute XPath References
        //testEval("/@blah", null, null, new XPathUnsupportedException());
        //TODO: Need to test with model, probably in a different file

        String wildcardIndex = "index/*";
        String indexOne = "index/some_index";
        String indexTwo = "index/another_index";
        XPathPathExpr expr = XPathReference.getPathExpr(wildcardIndex);
        XPathPathExpr expr2 = XPathReference.getPathExpr(indexOne);
        XPathPathExpr expr3 = XPathReference.getPathExpr(indexTwo);
        if (!expr.matches(expr2)) {
            fail("Bad Matching: " + wildcardIndex + " should match " + indexOne);
        }
        if (!expr2.matches(expr)) {
            fail("Bad Matching: " + indexOne + " should match " + wildcardIndex);
        }
        if (expr2.matches(expr3)) {
            fail("Bad Matching: " + indexOne + " should  not match " + indexTwo);
        }


        try {
            testEval("null-proto()", null, ec, new XPathUnhandledException());
            fail("Did not get expected null pointer");
        } catch (NullPointerException npe) {
            //expected
        }

        ec.addFunctionHandler(read);
        ec.addFunctionHandler(write);

        read.val = "testing-read";
        testEval("read()", null, ec, "testing-read");

        testEval("write('testing-write')", null, ec, Boolean.TRUE);
        if (!"testing-write".equals(write.val)) {
            fail("Custom function handler did not successfully send data to external source");
        }

        addDataRef(instance, "/data/string", new StringData("string"));
        addDataRef(instance, "/data/int", new IntegerData(17));
        addDataRef(instance, "/data/int_two", new IntegerData(5));
        addDataRef(instance, "/data/string_two", new StringData("2"));
        addDataRef(instance, "/data/predtest[1]/@val", new StringData("2.0"));
        addDataRef(instance, "/data/predtest[2]/@val", new StringData("2"));
        addDataRef(instance, "/data/predtest[1]/@num", new StringData("2.0"));
        addDataRef(instance, "/data/predtest[2]/@num", new StringData("2"));
        addDataRef(instance, "/data/predtest[3]/@val", new StringData("string"));

        addDataRef(instance, "/data/strtest[1]/@val", new StringData("a"));
        addDataRef(instance, "/data/strtest[2]/@val", new StringData("b"));
        addDataRef(instance, "/data/strtest[3]/@val", new StringData("string"));

        addDataRef(instance, "/data/rangetest[1]/@num", new StringData("-2"));
        addDataRef(instance, "/data/rangetest[2]/@num", new StringData("3"));

        testEval("/data/string", instance, null, "string");
        testEval("/data/int", instance, null, new Double(17.0));

        testEval("min(/data/int, /data/int_two)", instance, null, new Double(5.0));

        testEval("count(/data/predtest[@val = /data/string_two])", instance, null, new Double(2));
        testEval("count(/data/predtest[@val = 2])", instance, null, new Double(2));
        testEval("count(/data/predtest[2 = @val])", instance, null, new Double(2));

        testEval("count(/data/strtest[@val = 'a'])", instance, null, new Double(1));
        testEval("count(/data/strtest[@val = 2])", instance, null, new Double(0));
        testEval("count(/data/strtest[@val = /data/string])", instance, null, new Double(1));

        testEval("sum(/data/predtest/@num)", instance, null, 4.0);
        testEval("concat(/data/predtest/@num)", instance, null, "2.02");
        testEval("sum(1)", instance, null, new XPathTypeMismatchException());

        testEval("checklist(-1, 2, /data/predtest[1]/@val = 2, /data/predtest[2]/@val = 2, /data/predtest[3]/@val = 2)", instance, null, Boolean.TRUE);
        testEval("checklist(1, 2, /data/predtest[1]/@val = 2, /data/predtest[2]/@val = 2, /data/predtest[3]/@val = 2)", instance, null, Boolean.TRUE);
        testEval("checklist(-1, 1, /data/predtest[1]/@val = 2, /data/predtest[2]/@val = 2, /data/predtest[3]/@val = 2)", instance, null, Boolean.FALSE);
        testEval("checklist(3, 4, /data/predtest[1]/@val = 2, /data/predtest[2]/@val = 2, /data/predtest[3]/@val = 2)", instance, null, Boolean.FALSE);

        testEval("weighted-checklist(-1, 2, /data/predtest[1]/@val = 2, 1, /data/predtest[2]/@val = 2, 1, /data/predtest[3]/@val = 2, 1)", instance, null, Boolean.TRUE);
        testEval("weighted-checklist(1, 2, /data/predtest[1]/@val = 2, 1, /data/predtest[2]/@val = 2, 1, /data/predtest[3]/@val = 2, 1)", instance, null, Boolean.TRUE);
        testEval("weighted-checklist(-1, 1, /data/predtest[1]/@val = 2, 1, /data/predtest[2]/@val = 2, 1, /data/predtest[3]/@val = 2, 1)", instance, null, Boolean.FALSE);
        testEval("weighted-checklist(3, 4, /data/predtest[1]/@val = 2, 1, /data/predtest[2]/@val = 2, 1, /data/predtest[3]/@val = 2, 1)", instance, null, Boolean.FALSE);

        testEval("max(/data/rangetest[0])", instance, null, Double.NaN);
        testEval("min(/data/rangetest[0])", instance, null, Double.NaN);
        testEval("max(/data/rangetest/@num)", instance, null, new Double("3"));
        testEval("min(/data/rangetest/@num)", instance, null, new Double("-2"));
    }

    /**
     * Test the ability of the engine to rebuild expressions which are common sources
     * of confusion or user errors
     */
    @Test
    public void testStringOutputs() throws XPathSyntaxException {
        testStringOutput("/data//something");
        testStringOutput("invalidfunction('something')/somestep");
        testStringOutput("/data/../relative");
        testStringOutput("/data/@attr/@secondattr");
    }

    public void testStringOutput(String xPathInput) throws XPathSyntaxException {
        XPathExpression expr = XPathParseTool.parseXPath(xPathInput);
        Assert.assertEquals(xPathInput,expr.toPrettyString());
    }


    @Test
    public void testDoNotInferScientificNotationAsDouble() {
        Object dbl = FunctionUtils.InferType("100E5");
        Assert.assertTrue("We should not evaluate strings with scientific notation as doubles",
                XPathEqExpr.testEquality(dbl, "100E5"));
    }

    @Test
    public void testOverrideNow() {
        EvaluationContext ec = new EvaluationContext(null);

        ec.addFunctionHandler(new IFunctionHandler() {
            @Override
            public String getName() {
                return "now";
            }

            @Override
            public Vector getPrototypes() {
                Vector<Class[]> p = new Vector<>();
                p.addElement(new Class[0]);
                return p;
            }

            @Override
            public boolean rawArgs() {
                return false;
            }

            @Override
            public Object eval(Object[] args, EvaluationContext ec) {
                return "pass";
            }
        });

        testEval("now()", null, ec, "pass");
    }

    // Utility methods for string encryption.
    private SecretKey generateSecretKey(int keyLength) throws Exception {
        KeyGenerator keyGen = KeyGenerator.getInstance("AES");
        keyGen.init(keyLength, new SecureRandom());
        return keyGen.generateKey();
    }

    public void encryptAndCompare(EvaluationContext ec, String algorithm,
                                  int keyLength, String message,
                                  Exception expectedException) throws UnsupportedEncodingException {
        SecretKey secretKey = null;
        try {
            secretKey = generateSecretKey(keyLength);
        } catch(Exception ex) {
            fail("Unexpected exception generating secret key");
        }

        // The encrypted output contains a random initialization vector, so
        // we can't know in advance what it will be. Instead decrypt the output
        // and check for the input message.
        String keyString =
                new String(Base64.getEncoder().encode(secretKey.getEncoded()), "UTF-8");
        try {
            Object result = evalExpr("encrypt-string('" + message + "','" +
                                     keyString + "','" + algorithm + "')",
                                     null, ec);
            String resultString = FunctionUtils.toString(result);
            String decryptedMessage = EncryptionUtils.decrypt(resultString, keyString);
            if (!message.equals(decryptedMessage)) {
                fail("Expected decrypted message " + message + ", got " +
                     decryptedMessage);
            }
        } catch(Exception ex) {
            assertExceptionExpected(expectedException != null,
                                    expectedException, ex);
            return;
        }
    }

    @Test
    public void testEncryptString() throws UnsupportedEncodingException {
        final int KEY_LENGTH_BIT = 256;
        EvaluationContext ec = getFunctionHandlers();
        // Valid inputs that should decrypt to themselves.
        encryptAndCompare(ec, "AES", KEY_LENGTH_BIT, "49812057128", null);
        encryptAndCompare(ec, "AES", KEY_LENGTH_BIT,
                          "A short message to be encrypted", null);
        encryptAndCompare(ec, "AES", KEY_LENGTH_BIT,
                          "A longer message to be encrypted by the AES GCM " +
                          "method, which will test that somewhat longer " +
                          "messages can be correctly encrypted", null);

        // Invalid inputs that should raise exceptions.
        encryptAndCompare(ec, "DES", KEY_LENGTH_BIT,
                          "A short message to be encrypted",
                          new XPathException());
        encryptAndCompare(ec, "AES", KEY_LENGTH_BIT/2,
                          "A short message to be encrypted",
                          new XPathException());
    }

    protected void addDataRef(FormInstance dm, String ref, IAnswerData data) {
        TreeReference treeRef = XPathReference.getPathExpr(ref).getReference();
        treeRef = inlinePositionArgs(treeRef);

        addNodeRef(dm, treeRef);


        if (data != null) {
            dm.resolveReference(treeRef).setValue(data);
        }
    }

    private TreeReference inlinePositionArgs(TreeReference treeRef) {
        //find/replace position predicates
        for (int i = 0; i < treeRef.size(); ++i) {
            Vector<XPathExpression> predicates = treeRef.getPredicate(i);
            if (predicates == null || predicates.size() == 0) {
                continue;
            }
            if (predicates.size() > 1) {
                throw new IllegalArgumentException("only position [] predicates allowed");
            }
            if (!(predicates.elementAt(0) instanceof XPathNumericLiteral)) {
                throw new IllegalArgumentException("only position [] predicates allowed");
            }
            double d = ((XPathNumericLiteral)predicates.elementAt(0)).d;
            if (d != (double)((int)d)) {
                throw new IllegalArgumentException("invalid position: " + d);
            }

            int multiplicity = (int)d - 1;
            if (treeRef.getMultiplicity(i) != TreeReference.INDEX_UNBOUND) {
                throw new IllegalArgumentException("Cannot inline already qualified steps");
            }
            treeRef.setMultiplicity(i, multiplicity);
        }

        treeRef = treeRef.removePredicates();
        return treeRef;
    }

    private void addNodeRef(FormInstance dm, TreeReference treeRef) {
        TreeElement lastValidStep = dm.getRoot();
        for (int i = 1; i < treeRef.size(); ++i) {
            TreeElement step = dm.resolveReference(treeRef.getSubReference(i));
            if (step == null) {
                if (treeRef.getMultiplicity(i) == TreeReference.INDEX_ATTRIBUTE) {
                    //must be the last step
                    lastValidStep.setAttribute(null, treeRef.getName(i), "");
                    return;
                }
                String currentName = treeRef.getName(i);
                step = new TreeElement(currentName, treeRef.getMultiplicity(i) == TreeReference.INDEX_UNBOUND ? TreeReference.DEFAULT_MUTLIPLICITY : treeRef.getMultiplicity(i));
                lastValidStep.addChild(step);
            }
            lastValidStep = step;
        }
    }

    public FormInstance createTestInstance() {
        TreeElement data = new TreeElement("data");
        data.addChild(new TreeElement("path"));
        return new FormInstance(data);
    }

    protected EvaluationContext getFunctionHandlers() {
        EvaluationContext ec = new EvaluationContext(null);
        final Class[][] allPrototypes = {
                {Double.class, Double.class},
                {Double.class},
                {String.class, String.class},
                {Double.class, String.class, Boolean.class},
                {Boolean.class},
                {Boolean.class, Double.class, String.class, Date.class, CustomType.class}
        };

        ec.addFunctionHandler(new IFunctionHandler() {
            public String getName() {
                return "testfunc";
            }

            public Vector getPrototypes() {
                Vector p = new Vector();
                p.addElement(new Class[0]);
                return p;
            }

            public boolean rawArgs() {
                return false;
            }

            public Object eval(Object[] args, EvaluationContext ec) {
                return Boolean.TRUE;
            }
        });

        ec.addFunctionHandler(new IFunctionHandler() {
            @Override
            public String getName() {
                return "add";
            }

            @Override
            public Vector getPrototypes() {
                Vector p = new Vector();
                p.addElement(allPrototypes[0]);
                return p;
            }

            @Override
            public boolean rawArgs() {
                return false;
            }

            @Override
            public Object eval(Object[] args, EvaluationContext ec) {
                return new Double(((Double)args[0]).doubleValue() + ((Double)args[1]).doubleValue());
            }
        });

        ec.addFunctionHandler(new IFunctionHandler() {
            @Override
            public String getName() {
                return "proto";
            }

            @Override
            public Vector getPrototypes() {
                Vector p = new Vector();
                p.addElement(allPrototypes[0]);
                p.addElement(allPrototypes[1]);
                p.addElement(allPrototypes[2]);
                p.addElement(allPrototypes[3]);
                return p;
            }

            @Override
            public boolean rawArgs() {
                return false;
            }

            @Override
            public Object eval(Object[] args, EvaluationContext ec) {
                return printArgs(args);
            }
        });

        ec.addFunctionHandler(new IFunctionHandler() {
            @Override
            public String getName() {
                return "raw";
            }

            @Override
            public Vector getPrototypes() {
                Vector p = new Vector();
                p.addElement(allPrototypes[3]);
                return p;
            }

            @Override
            public boolean rawArgs() {
                return true;
            }

            @Override
            public Object eval(Object[] args, EvaluationContext ec) {
                return printArgs(args);
            }
        });

        ec.addFunctionHandler(new IFunctionHandler() {
            @Override
            public String getName() {
                return "null-proto";
            }

            @Override
            public Vector getPrototypes() {
                return null;
            }

            @Override
            public boolean rawArgs() {
                return false;
            }

            @Override
            public Object eval(Object[] args, EvaluationContext ec) {
                return Boolean.FALSE;
            }
        });

        ec.addFunctionHandler(new IFunctionHandler() {
            @Override
            public String getName() {
                return "convertible";
            }

            @Override
            public Vector getPrototypes() {
                Vector p = new Vector();
                p.addElement(new Class[0]);
                return p;
            }

            @Override
            public boolean rawArgs() {
                return false;
            }

            @Override
            public Object eval(Object[] args, EvaluationContext ec) {
                return new IExprDataType() {
                    @Override
                    public Boolean toBoolean() {
                        return Boolean.TRUE;
                    }

                    @Override
                    public Double toNumeric() {
                        return new Double(5.0);
                    }

                    @Override
                    public String toString() {
                        return "hi";
                    }
                };
            }
        });

        ec.addFunctionHandler(new IFunctionHandler() {
            @Override
            public String getName() {
                return "inconvertible";
            }

            @Override
            public Vector getPrototypes() {
                Vector p = new Vector();
                p.addElement(new Class[0]);
                return p;
            }

            @Override
            public boolean rawArgs() {
                return false;
            }

            @Override
            public Object eval(Object[] args, EvaluationContext ec) {
                return new Object();
            }
        });

        ec.addFunctionHandler(new IFunctionHandler() {
            @Override
            public String getName() {
                return "get-custom";
            }

            @Override
            public Vector getPrototypes() {
                Vector p = new Vector();
                p.addElement(allPrototypes[4]);
                return p;
            }

            @Override
            public boolean rawArgs() {
                return false;
            }

            @Override
            public Object eval(Object[] args, EvaluationContext ec) {
                return ((Boolean)args[0]).booleanValue() ? new CustomSubType() : new CustomType();
            }
        });

        ec.addFunctionHandler(new IFunctionHandler() {
            @Override
            public String getName() {
                return "check-types";
            }

            @Override
            public Vector getPrototypes() {
                Vector p = new Vector();
                p.addElement(allPrototypes[5]);
                return p;
            }

            @Override
            public boolean rawArgs() {
                return false;
            }

            @Override
            public Object eval(Object[] args, EvaluationContext ec) {
                if (args.length != 5 || !(args[0] instanceof Boolean) || !(args[1] instanceof Double) ||
                        !(args[2] instanceof String) || !(args[3] instanceof Date) || !(args[4] instanceof CustomType))
                    fail("Types in custom function handler not converted properly/prototype not matched properly");

                return Boolean.TRUE;
            }
        });
        return ec;
    }

    private EvaluationContext getVariableContext() {
        EvaluationContext ec = new EvaluationContext(null);

        ec.setVariable("var_float_five", new Float(5.0));
        ec.setVariable("var_string_five", "five");
        ec.setVariable("var_int_five", new Integer(5));
        ec.setVariable("var_double_five", new Double(5.0));

        return ec;
    }

    private String printArgs(Object[] oa) {
        StringBuffer sb = new StringBuffer();
        sb.append("[");
        for (int i = 0; i < oa.length; i++) {
            String fullName = oa[i].getClass().getName();
            int lastIndex = Math.max(fullName.lastIndexOf('.'), fullName.lastIndexOf('$'));
            sb.append(fullName.substring(lastIndex + 1, fullName.length()));
            sb.append(":");
            sb.append(oa[i] instanceof Date ? DateUtils.formatDate((Date)oa[i], DateUtils.FORMAT_ISO8601) : oa[i].toString());
            if (i < oa.length - 1)
                sb.append(",");
        }
        sb.append("]");
        return sb.toString();
    }

    private class CustomType {
        public String toString() {
            return "";
        }

        public boolean equals(Object o) {
            return o instanceof CustomType;
        }
    }

    private class CustomSubType extends CustomType {
    }

    /* unused
    private class CustomAnswerData implements IAnswerData {
        public String getDisplayText() { return "custom"; }
        public Object getValue() { return new CustomType(); }
        public void setValue(Object o) { }
        public void readExternal(DataInputStream in, PrototypeFactory pf) { }
        public void writeExternal(DataOutputStream out) { }
        public IAnswerData clone() {
            return new CustomAnswerData();
        }
    }
    */
    private abstract class StatefulFunc implements IFunctionHandler {
        public String val;

        @Override
        public boolean rawArgs() {
            return false;
        }
    }

    final StatefulFunc read = new StatefulFunc() {
        @Override
        public String getName() {
            return "read";
        }

        @Override
        public Vector getPrototypes() {
            Vector p = new Vector();
            p.addElement(new Class[0]);
            return p;
        }

        @Override
        public Object eval(Object[] args, EvaluationContext ec) {
            return val;
        }
    };

    final StatefulFunc write = new StatefulFunc() {
        @Override
        public String getName() {
            return "write";
        }

        @Override
        public Vector getPrototypes() {
            Vector p = new Vector();
            Class[] proto = {String.class};
            p.addElement(proto);
            return p;
        }

        @Override
        public Object eval(Object[] args, EvaluationContext ec) {
            val = (String)args[0];
            return Boolean.TRUE;
        }
    };
}


