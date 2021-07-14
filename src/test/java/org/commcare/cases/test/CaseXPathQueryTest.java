package org.commcare.cases.test;

import org.commcare.test.utilities.CaseTestUtils;
import org.commcare.test.utilities.TestProfileConfiguration;
import org.commcare.util.mocks.MockDataUtils;
import org.commcare.util.mocks.MockUserDataSandbox;
import org.javarosa.core.model.condition.EvaluationContext;
import org.javarosa.core.model.trace.ReducingTraceReporter;
import org.javarosa.core.model.trace.TraceSerialization;
import org.javarosa.core.model.utils.InstrumentationUtils;
import org.javarosa.xpath.parser.XPathSyntaxException;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.Collection;

/**
 * Test xpath expression evaluation that references the case instance
 *
 * @author Phillip Mates (pmates@dimagi.com)
 */

@RunWith(value = Parameterized.class)
public class CaseXPathQueryTest {
    private MockUserDataSandbox sandbox;

    TestProfileConfiguration config;
    @Parameterized.Parameters(name = "{0}")
    public static Collection data() {
        return TestProfileConfiguration.BulkOffOn();
    }

    public CaseXPathQueryTest(TestProfileConfiguration config) {
        this.config = config;
    }


    @Before
    public void setUp() {
        sandbox = MockDataUtils.getStaticStorage();
    }

    @Test
    public void elementQueryWithNoCaseInstance() throws XPathSyntaxException {
        MockUserDataSandbox emptySandbox = MockDataUtils.getStaticStorage();
        EvaluationContext ec = MockDataUtils.buildContextWithInstance(emptySandbox, "casedb",
                CaseTestUtils.CASE_INSTANCE);

        Assert.assertTrue(CaseTestUtils.xpathEvalAndCompare(ec,
                "instance('casedb')/casedb/case[@case_id = 'case_one']/case_name", ""));
    }

    @Test
    public void elementQueryWithCaseInstance() throws Exception {
        config.parseIntoSandbox(
                this.getClass().getResourceAsStream("/case_query_testing.xml"), sandbox);
        EvaluationContext ec = MockDataUtils.buildContextWithInstance(sandbox, "casedb",
                CaseTestUtils.CASE_INSTANCE);

        Assert.assertTrue(CaseTestUtils.xpathEvalAndCompare(ec,
                "instance('casedb')/casedb/case[@case_id = 'case_one']/case_name", "case"));
    }

    @Test
    public void referenceNonExistentCaseId() throws Exception {
        config.parseIntoSandbox(
                this.getClass().getResourceAsStream("/case_query_testing.xml"), sandbox);
        EvaluationContext ec = MockDataUtils.buildContextWithInstance(sandbox, "casedb",
                CaseTestUtils.CASE_INSTANCE);

        Assert.assertTrue(CaseTestUtils.xpathEvalAndCompare(ec,
                "count(instance('casedb')/casedb/case[@case_id = 'no-case'])", 0.0));
    }

    @Test
    public void caseQueryWithNoProperty() throws Exception {
        config.parseIntoSandbox(
                this.getClass().getResourceAsStream("/case_query_testing.xml"), sandbox);
        EvaluationContext ec = MockDataUtils.buildContextWithInstance(sandbox, "casedb",
                CaseTestUtils.CASE_INSTANCE);

        Assert.assertTrue(CaseTestUtils.xpathEvalAndCompare(ec,
                "instance('casedb')/casedb/case[@case_id = 'case_one']/doesnt_exist", ""));

        Assert.assertTrue(CaseTestUtils.xpathEvalAndCompare(ec,
                "instance('casedb')/casedb/case[1]/doesnt_exist", ""));

        CaseTestUtils.xpathEvalAndAssert(ec,
                "count(instance('casedb')/casedb/case[@case_id = 'case_one'][not(doesnt_exist = '')])", 0.0);

        CaseTestUtils.xpathEvalAndAssert(ec,
                "count(instance('casedb')/casedb/case[1][not(doesnt_exist = '')])", 0.0);

        CaseTestUtils.xpathEvalAndAssert(ec,
                "count(instance('casedb')/casedb/case[1][doesnt_exist = 'nomatch'])", 0.0);
    }

    @Test
    public void caseQueryEqualsTest() throws Exception {
        config.parseIntoSandbox(
                this.getClass().getResourceAsStream("/case_query_testing.xml"), sandbox);
        EvaluationContext ec =
                MockDataUtils.buildContextWithInstance(sandbox, "casedb",
                        CaseTestUtils.CASE_INSTANCE);

        ReducingTraceReporter traceReporter = new ReducingTraceReporter(false);
        ec.setDebugModeOn(traceReporter);

        Assert.assertTrue(CaseTestUtils.xpathEvalAndCompare(ec,
                "count(instance('casedb')/casedb/case[@case_id = 'case_one'])", 1.0));

        String trace = InstrumentationUtils.collectAndClearTraces(
                traceReporter, "case query", TraceSerialization.TraceInfoType.FULL_PROFILE);

        // make sure the evaluation was routed through a case db index lookup
        assert trace.split("\n")[27].contains("Storage [casedb] Key Lookup [case-id|]:");
    }

    @Test
    public void caseQueryNotEqualsTest() throws Exception {
        config.parseIntoSandbox(
                this.getClass().getResourceAsStream("/case_query_testing.xml"), sandbox);
        EvaluationContext ec =
                MockDataUtils.buildContextWithInstance(sandbox, "casedb",
                        CaseTestUtils.CASE_INSTANCE);

        ReducingTraceReporter traceReporter = new ReducingTraceReporter(false);
        ec.setDebugModeOn(traceReporter);

        Assert.assertTrue(CaseTestUtils.xpathEvalAndCompare(ec,
                "count(instance('casedb')/casedb/case[@case_id != 'case_one'])", 2.0));

        String trace = InstrumentationUtils.collectAndClearTraces(
                traceReporter, "case query", TraceSerialization.TraceInfoType.FULL_PROFILE);

        // make sure the evaluation was routed through a case db index lookup
        assert trace.split("\n")[27].contains("Storage [casedb] Key Lookup [case-id|]:");
    }

    @Test
    public void caseIndexAliasTest() throws Exception {
        config.parseIntoSandbox(
                this.getClass().getResourceAsStream("/case_query_testing.xml"), sandbox);
        EvaluationContext ec =
                MockDataUtils.buildContextWithInstance(sandbox, "casedb",
                        CaseTestUtils.CASE_INSTANCE);

        Assert.assertTrue(CaseTestUtils.xpathEvalAndCompare(ec,
                "count(instance('casedb')/casedb/case[@category = 'real'])", 1.0));
        Assert.assertTrue(CaseTestUtils.xpathEvalAndCompare(ec,
                "count(instance('casedb')/casedb/case[patient_type = 'real'])", 1.0));
        Assert.assertTrue(CaseTestUtils.xpathEvalAndCompare(ec,
                "count(instance('casedb')/casedb/case[current_status = 'c'])", 2.0));
        Assert.assertTrue(CaseTestUtils.xpathEvalAndCompare(ec,
                "count(instance('casedb')/casedb/case[@state = 'c'])", 1.0));
        Assert.assertTrue(CaseTestUtils.xpathEvalAndCompare(ec,
                "count(instance('casedb')/casedb/case[@category != 'real'])", 2.0));
        Assert.assertTrue(CaseTestUtils.xpathEvalAndCompare(ec,
                "count(instance('casedb')/casedb/case[@category = 'fake'])", 0.0));
        Assert.assertTrue(CaseTestUtils.xpathEvalAndCompare(ec,
                "count(instance('casedb')/casedb/case[@category != 'fake'])", 3.0));
        Assert.assertTrue(CaseTestUtils.xpathEvalAndCompare(ec,
                "count(instance('casedb')/casedb/case[true() and @category != 'real'])", 2.0));
        Assert.assertTrue(CaseTestUtils.xpathEvalAndCompare(ec,
                "count(instance('casedb')/casedb/case[@state = 'c'][@category = 'real'])", 1.0));
        Assert.assertTrue(CaseTestUtils.xpathEvalAndCompare(ec,
                "count(instance('casedb')/casedb/case[current_status = 'c'][@category = 'real'])", 1.0));
        Assert.assertTrue(CaseTestUtils.xpathEvalAndCompare(ec,
                "count(instance('casedb')/casedb/case[@state = 'a'][@category != 'fake'])", 1.0));

    }
}
