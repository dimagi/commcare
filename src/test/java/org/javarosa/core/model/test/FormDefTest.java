package org.javarosa.core.model.test;

import org.javarosa.core.model.Constants;
import org.javarosa.core.model.FormDef;
import org.javarosa.core.model.FormIndex;
import org.javarosa.core.model.QuestionDef;
import org.javarosa.core.model.condition.EvaluationContext;
import org.javarosa.core.model.condition.IFunctionHandler;
import org.javarosa.core.model.data.DateData;
import org.javarosa.core.model.data.DateTimeData;
import org.javarosa.core.model.data.IAnswerData;
import org.javarosa.core.model.data.IntegerData;
import org.javarosa.core.model.data.SelectOneData;
import org.javarosa.core.model.data.StringData;
import org.javarosa.core.model.data.UncastData;
import org.javarosa.core.model.data.helper.Selection;
import org.javarosa.core.model.instance.FormInstance;
import org.javarosa.core.model.instance.TreeElement;
import org.javarosa.core.model.instance.TreeReference;
import org.javarosa.core.model.instance.test.DummyInstanceInitializationFactory;
import org.javarosa.core.model.utils.test.PersistableSandbox;
import org.javarosa.core.test.FormParseInit;
import org.javarosa.form.api.FormEntryController;
import org.javarosa.test_utils.ExprEvalUtils;
import org.javarosa.xform.util.XFormAnswerDataSerializer;
import org.javarosa.xpath.parser.XPathSyntaxException;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.util.Calendar;
import java.util.Date;
import java.util.Vector;

import static org.junit.Assert.*;

/**
 * @author Phillip Mates (pmates@dimagi.com)
 */
public class FormDefTest {
    /**
     * Make sure that 'current()' expands correctly when used in conditionals
     * such as in 'relevant' tags. The test answers a question and expects the
     * correct elements to be re-evaluated and set to not relevant.
     */
    @Test
    public void testCurrentFuncInTriggers() {
        FormParseInit fpi = new FormParseInit("/trigger_and_current_tests.xml");
        FormEntryController fec = initFormEntry(fpi);

        do {
            QuestionDef q = fpi.getCurrentQuestion();
            if (q == null) {
                continue;
            }
            // get the reference of question
            TreeReference qRef = q.getBind().getReference();

            // are we changing the value of /data/show?
            if (qRef.toString().equals("/data/show")) {
                int response = fec.answerQuestion(new StringData("no"));
                if (response != FormEntryController.ANSWER_OK) {
                    fail("Bad response from fec.answerQuestion()");
                }
            } else if (q.getID() == 2) {
                // check (sketchily) if the second question is shown, which
                // shouldn't happen after answering "no" to the first, unless
                // triggers aren't working properly.
                fail("shouldn't be relevant after answering no before");
            }
        } while (fec.stepToNextEvent() != FormEntryController.EVENT_END_OF_FORM);
    }

    /**
     * Make sure that relative references in <bind> elements are correctly
     * contextualized.
     */
    @Test
    public void testRelativeRefInTriggers() {
        FormParseInit fpi = new FormParseInit("/test_nested_preds_with_rel_refs.xml");
        FormEntryController fec = fpi.getFormEntryController();
        fec.jumpToIndex(FormIndex.createBeginningOfFormIndex());

        FormDef fd = fpi.getFormDef();
        // run initialization to ensure xforms-ready event and binds are
        // triggered.
        fd.initialize(true, new DummyInstanceInitializationFactory());

        FormInstance instance = fd.getMainInstance();

        String errorMsg;
        errorMsg = ExprEvalUtils.expectedEval("/data/query-one", instance, null, "0", null);
        assertTrue(errorMsg, "".equals(errorMsg));

        boolean[] shouldBePresent = {true, true};

        do {
            QuestionDef q = fpi.getCurrentQuestion();
            if (q == null) {
                continue;
            }

            if (q.getID() <= shouldBePresent.length && !shouldBePresent[q.getID() - 1]) {
                fail("question with id " + q.getID() + " shouldn't be relevant");
            }
        } while (fec.stepToNextEvent() != FormEntryController.EVENT_END_OF_FORM);
    }

    @Test
    public void testAnswerConstraint() {
        FormParseInit fpi = new FormParseInit("/ImageSelectTester.xhtml");
        FormEntryController fec = initFormEntry(fpi);

        do {
            QuestionDef q = fpi.getCurrentQuestion();
            if (q == null || q.getTextID() == null || "".equals(q.getTextID())) {
                continue;
            }
            if (q.getTextID().equals("constraint-test")) {
                int response = fec.answerQuestion(new IntegerData(13));
                if (response == FormEntryController.ANSWER_CONSTRAINT_VIOLATED) {
                    fail("Answer Constraint test failed.");
                } else if (response == FormEntryController.ANSWER_OK) {
                    break;
                } else {
                    fail("Bad response from fec.answerQuestion()");
                }
            }
        } while (fec.stepToNextEvent() != FormEntryController.EVENT_END_OF_FORM);
    }

    @Test
    public void testAnswerConstraintOldText() {
        IntegerData ans = new IntegerData(7);
        FormParseInit fpi = new FormParseInit("/ImageSelectTester.xhtml");
        FormEntryController fec = initFormEntry(fpi);
        fec.setLanguage("English");

        do {
            QuestionDef q = fpi.getCurrentQuestion();
            if (q == null || q.getTextID() == null || "".equals(q.getTextID())) {
                continue;
            }
            if (q.getTextID().equals("constraint-test")) {
                int response = fec.answerQuestion(ans);
                if (response == FormEntryController.ANSWER_CONSTRAINT_VIOLATED) {
                    if (!"Old Constraint".equals(fec.getModel().getQuestionPrompt().getConstraintText())) {
                        fail("Old constraint message not found, instead got: "
                                + fec.getModel().getQuestionPrompt().getConstraintText());
                    }
                } else if (response == FormEntryController.ANSWER_OK) {
                    fail("Should have constrained");
                    break;
                }
            }
            if (q.getTextID().equals("constraint-test-2")) {

                int response3 = fec.answerQuestion(new IntegerData(13));
                if (response3 == FormEntryController.ANSWER_CONSTRAINT_VIOLATED) {
                    if (!"New Alert".equals(fec.getModel().getQuestionPrompt().getConstraintText())) {
                        fail("New constraint message not found, instead got: "
                                + fec.getModel().getQuestionPrompt().getConstraintText());
                    }
                } else if (response3 == FormEntryController.ANSWER_OK) {
                    fail("Should have constrained (2)");
                    break;
                }

            }
            if (q.getTextID().equals("constraint-test-3")) {

                int response4 = fec.answerQuestion(new IntegerData(13));
                if (response4 == FormEntryController.ANSWER_CONSTRAINT_VIOLATED) {
                    if (!"The best QB of all time: Tom Brady".equals(fec.getModel().getQuestionPrompt().getConstraintText())) {
                        fail("New constraint message not found, instead got: "
                                + fec.getModel().getQuestionPrompt().getConstraintText());
                    }
                } else if (response4 == FormEntryController.ANSWER_OK) {
                    fail("Should have constrained (2)");
                    break;
                }

            }
        } while (fec.stepToNextEvent() != FormEntryController.EVENT_END_OF_FORM);
    }

    /**
     * Test setvalue expressions which have predicate references
     */
    @Test
    public void testSetValuePredicate() {
        FormParseInit fpi = new FormParseInit("/test_setvalue_predicate.xml");
        FormEntryController fec = initFormEntry(fpi);

        boolean testPassed = false;
        do {
            if (fec.getModel().getEvent() != FormEntryController.EVENT_QUESTION) {
                continue;
            }
            String text = fec.getModel().getQuestionPrompt().getQuestionText();
            //check for our test
            if (text.contains("Test") && text.contains("pass")) {
                testPassed = true;
            }
        } while (fec.stepToNextEvent() != FormEntryController.EVENT_END_OF_FORM);
        if (!testPassed) {
            fail("Setvalue Predicate Target Test");
        }
    }

    /**
     * Test nested form repeat triggers and actions
     */
    @Test
    public void testNestedRepeatActions() throws Exception {
        FormParseInit fpi = new FormParseInit("/xform_tests/test_looped_model_iteration.xml");
        FormEntryController fec = initFormEntry(fpi);
        stepThroughEntireForm(fec);

        ExprEvalUtils.assertEqualsXpathEval("Nested repeats did not evaluate to the proper outcome",
                60.0,
                "/data/sum",
                fpi.getFormDef().getEvaluationContext());
    }

    /**
     * Test triggers fired from inserting a new repeat entry. Triggers fired
     * during insert action don't need to be fired again when all triggers
     * rooted by that repeat entry are fired.
     */
    @Test
    public void testRepeatInsertTriggering() throws Exception {
        FormParseInit fpi =
                new FormParseInit("/xform_tests/test_repeat_insert_duplicate_triggering.xml");
        FormEntryController fec = initFormEntry(fpi);
        stepThroughEntireForm(fec);

        EvaluationContext evalCtx = fpi.getFormDef().getEvaluationContext();
        // make sure the language isn't the default language, 'esperanto',
        // which it is initially set to
        ExprEvalUtils.assertEqualsXpathEval("Check language set correctly",
                "en", "/data/iter/country[1]/language", evalCtx);
        ExprEvalUtils.assertEqualsXpathEval("Check id attr set correctly",
                "1", "/data/iter/country[2]/@id", evalCtx);
        ExprEvalUtils.assertEqualsXpathEval("Check id node set correctly",
                "1", "/data/iter/country[2]/id", evalCtx);
    }

    /**
     * Tests:
     * -Adding a timestamp attribute to a node in the model when the corresponding question's
     * value is changed
     * -Setting a default value for one question based on the answer to another
     * -Deserialization of a FormDef
     */
    @Test
    public void testQuestionLevelActionsAndSerialization() throws Exception {
        // Generate a normal version of the fpi
        FormParseInit fpi =
                new FormParseInit("/xform_tests/test_question_level_actions.xml");

        // Then generate one from a deserialized version of the initial form def
        FormDef fd = fpi.getFormDef();
        PersistableSandbox sandbox = new PersistableSandbox();
        byte[] serialized = sandbox.serialize(fd);
        FormDef deserializedFormDef = sandbox.deserialize(serialized, FormDef.class);
        FormParseInit fpiFromDeserialization = new FormParseInit(deserializedFormDef);

        // First test normally
        testQuestionLevelActions(fpi);

        // Then test with the deserialized version (to test that FormDef serialization is working properly)
        testQuestionLevelActions(fpiFromDeserialization);
    }

    public void testQuestionLevelActions(FormParseInit fpi)
            throws Exception {
        FormEntryController fec = initFormEntry(fpi);
        EvaluationContext evalCtx = fpi.getFormDef().getEvaluationContext();

        ExprEvalUtils.assertEqualsXpathEval(
                "Test that xforms-ready event triggered the form-level setvalue action",
                "default value", "/data/selection", evalCtx);

        Calendar birthday = Calendar.getInstance();
        birthday.set(1993, Calendar.MARCH, 26);

        int questionIndex = 0;
        do {
            QuestionDef q = fpi.getCurrentQuestion();
            if (q == null) {
                continue;
            }

            // Note this relies on the questions in the test xml file staying in the current order
            if (questionIndex == 0) {
                fec.answerQuestion(new StringData("Answer to text question"));
            } else if (questionIndex == 1) {
                fec.answerQuestion(new SelectOneData(new Selection("one")));
            } else if (questionIndex == 2) {
                fec.answerQuestion(new DateData(birthday.getTime()));
            }

            questionIndex++;
        } while (fec.stepToNextEvent() != FormEntryController.EVENT_END_OF_FORM);

        Object evalResult = ExprEvalUtils.xpathEval(evalCtx, "/data/text/@time");
        assertTrue("Check that a timestamp was set for the text question",
                evalResult instanceof Date);

        evalResult = ExprEvalUtils.xpathEval(evalCtx, "/data/selection/@time");
        assertTrue("Check that a timestamp was set for the selection question",
                evalResult instanceof Date);

        evalResult = ExprEvalUtils.xpathEval(evalCtx, "/data/birthday/@time");
        assertTrue("Check that a timestamp was set for the date question",
                evalResult instanceof Date);

        long currentInMillis = Calendar.getInstance().getTimeInMillis();
        long birthdayInMillis = birthday.getTimeInMillis();
        long diff = currentInMillis - birthdayInMillis;
        long MILLISECONDS_IN_A_YEAR = (long)(365.25 * 24 * 60 * 60 * 1000);
        double expectedAge = (double)(diff / MILLISECONDS_IN_A_YEAR);

        ExprEvalUtils.assertEqualsXpathEval("Check that a default value for the age question was " +
                        "set correctly based upon provided answer to birthday question",
                expectedAge, "/data/age", evalCtx);
    }

    /**
     * Tests trigger caching related to cascading relevancy calculations to children.
     */
    @Test
    public void testTriggerCaching() throws Exception {
        // Running the form creates a few animals with weights that count down from the init_weight.
        // Skips over a specified entry by setting it to irrelevant.
        FormParseInit fpi = new FormParseInit("/xform_tests/test_trigger_caching.xml");
        FormEntryController fec = initFormEntry(fpi);
        stepThroughEntireForm(fec);

        EvaluationContext evalCtx = fpi.getFormDef().getEvaluationContext();
        ExprEvalUtils.assertEqualsXpathEval("Check max animal weight",
                400.0, "/data/heaviest_animal_weight", evalCtx);
        ExprEvalUtils.assertEqualsXpathEval("Check min animal",
                100.0, "/data/lightest_animal_weight", evalCtx);
        ExprEvalUtils.assertEqualsXpathEval("Ensure we skip over setting attr of irrelevant entry",
                "", "/data/animals[/data/skip_weighing_nth_animal]/weight/@time", evalCtx);

        Object weighTimeResult =
                ExprEvalUtils.xpathEval(evalCtx,
                        "/data/animals[/data/skip_weighing_nth_animal - 1]/weight/@time");
        if ("".equals(weighTimeResult) || "-1".equals(weighTimeResult)) {
            fail("@time should be set for relevant animal weight.");
        }
        ExprEvalUtils.assertEqualsXpathEval("Assert genus skip value",
                1.0, "/data/skip_genus_nth_animal", evalCtx);
        ExprEvalUtils.assertEqualsXpathEval("Ensure genus at skip entry is irrelevant",
                "", "/data/animals[1]/genus/species", evalCtx);
        ExprEvalUtils.assertEqualsXpathEval("Ensure genuse at non-skip entry has default value",
                "default", "/data/animals[2]/genus/species", evalCtx);

        ExprEvalUtils.assertEqualsXpathEval(
                "Relevancy of skipped genus entry should be irrelevant to, due to the way it is calculated",
                "", "/data/disabled_species", evalCtx);
    }

    /**
     * Regressions around complex repeat behaviors
     */
    @Test
    public void testLoopedRepeatIndexFetches() throws Exception {
        FormParseInit fpi = new FormParseInit("/xform_tests/test_looped_form_index_fetch.xml");
        FormEntryController fec = initFormEntry(fpi);

        fec.stepToNextEvent();
        fec.stepToNextEvent();

        fec.answerQuestion(new IntegerData(2));
        while (fec.stepToNextEvent() != FormEntryController.EVENT_QUESTION) ;

        fec.answerQuestion(new UncastData("yes"));
        while (fec.stepToNextEvent() != FormEntryController.EVENT_QUESTION) ;

        fec.getNextIndex(fec.getModel().getFormIndex(), true);
        fec.answerQuestion(new IntegerData(2));
        fec.getNextIndex(fec.getModel().getFormIndex(), true);
    }

    /**
     * Android-level form save on complete used to run through all
     * triggerables.  Disabling this, for performance reasons, exposed a bug
     * were triggerables that fire on repeat inserts and have expressions that
     * reference future (to be inserted) repeat entries never get re-fired due
     * to over contextualization. This has been fixed by generalizing the
     * triggerable context where path predicates are present for intersecting
     * references in the triggerable expression.
     */
    @Test
    public void testModelIterationLookahead() throws XPathSyntaxException {
        FormParseInit fpi = new FormParseInit("/xform_tests/model_iteration_lookahead.xml");
        FormEntryController fec = initFormEntry(fpi);
        stepThroughEntireForm(fec);

        EvaluationContext evalCtx = fpi.getFormDef().getEvaluationContext();
        ExprEvalUtils.assertEqualsXpathEval("",
                "20", "/data/myiterator/iterator[1]/target_value", evalCtx);
        ExprEvalUtils.assertEqualsXpathEval("",
                100.0, "/data/myiterator/iterator[1]/relevancy_depending_on_future", evalCtx);
    }

    /**
     * Testing that two identical bind conditions, modulo the operator (=, <),
     * are treated as distict entities.  Regression test for an issue where
     * `equals` method for binary conditions wasn't taking condition type
     * (arith, bool, equality) into account.
     */
    @Test
    public void testSimilarBindConditionsAreDistinguished() throws Exception {
        FormParseInit fpi =
                new FormParseInit("/xform_tests/test_display_conditions_regression.xml");
        FormEntryController fec = initFormEntry(fpi);

        boolean visibleLabelWasPresent = false;
        do {
            QuestionDef q = fpi.getCurrentQuestion();
            if (q == null || q.getTextID() == null || "".equals(q.getTextID())) {
                continue;
            }
            if (q.getTextID().equals("visible-label")) {
                visibleLabelWasPresent = true;
            }
            if (q.getTextID().equals("invisible-label")) {
                fail("Label whose display condition should be false was showing");
            }
        } while (fec.stepToNextEvent() != FormEntryController.EVENT_END_OF_FORM);

        if (!visibleLabelWasPresent) {
            fail("Label whose display condition should be true was not showing");
        }
    }

    // regression test for when we weren't decrementing multiplicities correctly when repeats were deleted
    @Test
    public void testDeleteRepeatMultiplicities() throws IOException {
        FormParseInit fpi = new FormParseInit("/multiple_repeats.xml");
        FormEntryController fec = initFormEntry(fpi, "en");
        fec.stepToNextEvent();
        fec.newRepeat();
        fec.stepToNextEvent();
        fec.answerQuestion(new StringData("First repeat, first iteration: question2"));
        fec.stepToNextEvent();
        fec.answerQuestion(new StringData("First repeat, first iteration: question3"));
        fec.stepToNextEvent();
        fec.newRepeat();
        fec.stepToNextEvent();
        fec.answerQuestion(new StringData("First repeat, second iteration: question2"));
        fec.stepToNextEvent();
        fec.answerQuestion(new StringData("First repeat, second iteration: question3"));
        fec.stepToNextEvent();
        fec.stepToNextEvent();
        // moving from first repeat (question1) to second (question4)
        fec.newRepeat();
        fec.stepToNextEvent();
        fec.answerQuestion(new StringData("Second repeat, first iteration: question5"));
        fec.stepToNextEvent();
        fec.answerQuestion(new StringData("Second repeat, first iteration: question6"));
        fec.stepToNextEvent();
        fec.newRepeat();
        fec.stepToNextEvent();
        fec.answerQuestion(new StringData("Second repeat, second iteration: question5"));
        fec.stepToNextEvent();
        fec.answerQuestion(new StringData("Second repeat, second iteration: question6"));
        fec.stepToPreviousEvent();
        fec.stepToPreviousEvent();

        TreeElement root = fpi.getFormDef().getInstance().getRoot();

        // confirm both groups have two iterations and second iteration is set
        assertEquals(root.getChildMultiplicity("question4"), 2);
        assertNotEquals(root.getChild("question4", 1), null);
        assertEquals(root.getChildMultiplicity("question1"), 2);
        assertNotEquals(root.getChild("question1", 1), null);

        fec.deleteRepeat(0);

        // Confirm that the deleted repeat is gone and its sibling's multiplicity reduced
        assertEquals(root.getChildMultiplicity("question4"), 1);
        assertEquals(root.getChild("question4", 1), null);
        // Confirm that the other repeat is unchanged
        assertEquals(root.getChildMultiplicity("question1"), 2);
        assertNotEquals(root.getChild("question1", 1), null);
    }

    @Test
    public void testBindForDateTimeType() {
        FormParseInit fpi = new FormParseInit("/xform_tests/datetime_bind.xml");
        FormEntryController fec = initFormEntry(fpi);
        DateTimeData answer = new DateTimeData(new Date());
        do {
            QuestionDef q = fpi.getCurrentQuestion();
            if (q == null || q.getTextID() == null || "".equals(q.getTextID())) {
                continue;
            }
            if (q.getTextID().equals("time-label")) {
                fec.answerQuestion(answer);
            }
        } while (fec.stepToNextEvent() != FormEntryController.EVENT_END_OF_FORM);

        // test that a value that should be updated has been updated
        ExprEvalUtils.testEval("/data/case/update/time",
                fpi.getFormDef().getInstance(), null, new XFormAnswerDataSerializer().serializeAnswerData(answer));
    }

    /**
     * Regression: IText function in xpath was not properly using the current
     * locale instead of the default
     */
    @Test
    public void testITextXPathFunction() throws XPathSyntaxException {
        FormParseInit fpi = new FormParseInit("/xform_tests/itext_function.xml");
        // init form with the 'new' locale instead of the default 'old' locale
        FormEntryController fec = initFormEntry(fpi, "new");

        boolean inlinePassed = false;
        boolean nestedPassed = false;

        do {
            TreeReference currentRef = fec.getModel().getFormIndex().getReference();
            if (currentRef == null) {
                continue;
            }
            if (currentRef.genericize().toString().equals("/data/inline")) {
                assertEquals("Inline IText Method Callout", "right",
                        fec.getModel().getCaptionPrompt().getQuestionText());
                inlinePassed = true;
            }

            if (currentRef.genericize().toString().equals("/data/nested")) {
                assertEquals("Nexted IText Method Callout", "right",
                        fec.getModel().getCaptionPrompt().getQuestionText());
                nestedPassed = true;
            }
        } while (fec.stepToNextEvent() != FormEntryController.EVENT_END_OF_FORM);

        if (!inlinePassed) {
            Assert.fail("Inline itext callout did not occur");
        }
        if (!nestedPassed) {
            Assert.fail("Nested itext callout did not occur");
        }


        EvaluationContext evalCtx = fpi.getFormDef().getEvaluationContext();
        ExprEvalUtils.assertEqualsXpathEval("IText calculation contained the wrong value",
                "right", "/data/calculation", evalCtx);
    }

    /**
     * Ensures that the relevancy condition for a group insided of a repeat is
     * properly accounted for when building the triggerables DAG
     */
    @Test
    public void testGroupRelevancyInsideRepeat() throws XPathSyntaxException {
        FormParseInit fpi = new FormParseInit("/xform_tests/group_relevancy_in_repeat.xml");
        FormEntryController fec = initFormEntry(fpi);

        do {
            QuestionDef q = fpi.getCurrentQuestion();

            if (q != null && q.getControlType() == Constants.CONTROL_SELECT_ONE) {
                IAnswerData ans = new SelectOneData(new Selection("yes"));
                fec.answerQuestion(ans);
                // test that a value that should be updated has been updated
                ExprEvalUtils.testEval("/data/some_group/repeat_sum",
                        fpi.getFormDef().getInstance(), null, 25.0);
            }
        } while (fec.stepToNextEvent() != FormEntryController.EVENT_END_OF_FORM);
    }

    private static void stepThroughEntireForm(FormEntryController fec) {
        do {
        } while (fec.stepToNextEvent() != FormEntryController.EVENT_END_OF_FORM);
    }


    public static FormEntryController initFormEntry(FormParseInit fpi) {
        return initFormEntry(fpi, null);
    }

    private static FormEntryController initFormEntry(FormParseInit fpi, String locale) {
        FormEntryController fec = fpi.getFormEntryController();
        fpi.getFormDef().initialize(true, null, locale, false);
        fec.jumpToIndex(FormIndex.createBeginningOfFormIndex());
        return fec;
    }

    /**
     * Test that the untrue portion of an if() statement won't evaluate
     */
    @Test
    public void testFormShortCircuit() {
        FormParseInit fpi = new FormParseInit("/IfShortCircuitTest.xhtml");

        // Custom function that will fail if called
        IFunctionHandler functionFailer = new IFunctionHandler() {
            @Override
            public String getName() {
                return "fail_function";
            }

            @Override
            public Object eval(Object[] args, EvaluationContext ec) {
                throw new RuntimeException("False portion of if() statement called");
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
        };

        fpi.getFormDef().exprEvalContext.addFunctionHandler(functionFailer);

        FormEntryController fec = fpi.getFormEntryController();

        do {
            QuestionDef q = fpi.getCurrentQuestion();
            if (q == null) {
                continue;
            }
            fec.answerQuestion(new SelectOneData(new Selection("yes")));
        } while (fec.stepToNextEvent() != FormEntryController.EVENT_END_OF_FORM);
    }

    @Test
    public void testItemsetPopulationAndFilter() {
        FormParseInit fpi = new FormParseInit("/xform_tests/itemset_population_test.xhtml");

        FormEntryController fec = fpi.getFormEntryController();

        do {
            QuestionDef q = fpi.getCurrentQuestion();
            if (q == null) {
                continue;
            }
            TreeReference currentRef = fec.getModel().getFormIndex().getReference();
            if (currentRef == null) {
                continue;
            }

            if (currentRef.genericize().toString().equals("/data/filter")) {
                fec.answerQuestion(new SelectOneData(new Selection("a")));
            }

            if (currentRef.genericize().toString().equals("/data/question")) {
                assertEquals("Itemset Filter returned the wrong size",
                        fec.getModel().getQuestionPrompt().getSelectChoices().size(),
                        3);
            }

        } while (fec.stepToNextEvent() != FormEntryController.EVENT_END_OF_FORM);
    }
}
