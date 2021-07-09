package org.commcare.backend.session.test;

import org.commcare.modern.session.SessionWrapper;
import org.commcare.session.SessionFrame;
import org.commcare.suite.model.Action;
import org.commcare.test.utilities.MockApp;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * @author $|-|!˅@M
 */
public class ParentChildSessionNavigatorTest {

    private MockApp mApp;
    private SessionWrapper session;

    @Before
    public void setUp() throws Exception {
        mApp = new MockApp("/parent_child_stack_test/");
        session = mApp.getSession();
    }

    @Test
    public void testParentCaseRegistration_forBackNavigation() {
        // Select module 2
        session.setCommand("m2");

        // It should trigger a parent case selection
        Assert.assertEquals(SessionFrame.STATE_DATUM_VAL, session.getNeededData());
        Assert.assertEquals("parent_id", session.getNeededDatum().getDataId());

        // Use the registration action to open parent registration form
        Action action = session.getPlatform().getDetail("m1_case_short")
                .getCustomActions(session.getEvaluationContext()).firstElement();
        session.executeStackOperations(action.getStackOperations(), session.getEvaluationContext());
        Assert.assertEquals("m0-f0", session.getCommand());

        // Pressing back should take us back to parent case list
        session.stepBack();
        Assert.assertEquals("m2", session.getCommand());
        Assert.assertEquals(SessionFrame.STATE_DATUM_VAL, session.getNeededData());
        Assert.assertEquals("parent_id", session.getNeededDatum().getDataId());
    }

    @Test
    public void testParentCaseRegistration_forCompletion() {
        // Select module 2
        session.setCommand("m2");

        // It should trigger a parent case selection
        Assert.assertEquals("parent_id", session.getNeededDatum().getDataId());

        // Use the registration action to open parent registration form
        Action action = session.getPlatform().getDetail("m1_case_short")
                .getCustomActions(session.getEvaluationContext()).firstElement();
        session.executeStackOperations(action.getStackOperations(), session.getEvaluationContext());
        Assert.assertEquals("m0-f0", session.getCommand());

        // Complete parent registration
        session.finishExecuteAndPop(session.getEvaluationContext());

        // assert that we're now in child case registration form
        Assert.assertEquals("m1-f0", session.getCommand());

        // complete child registration
        session.finishExecuteAndPop(session.getEvaluationContext());

        // assert that frame is dead.
        Assert.assertTrue(session.getFrame().isDead());
    }

    @Test
    public void testChildCaseRegistration_forBackNavigation() {
        // Select module 2
        session.setCommand("m2");
        // Open child list
        session.setDatum("parent_id", "first_parent");
        Assert.assertEquals(SessionFrame.STATE_DATUM_VAL, session.getNeededData());
        Assert.assertEquals("case_id", session.getNeededDatum().getDataId());

        // Pressing back takes to parent list
        session.stepBack();
        Assert.assertEquals(SessionFrame.STATE_DATUM_VAL, session.getNeededData());
        Assert.assertEquals("parent_id", session.getNeededDatum().getDataId());

        // Open child list again
        session.setDatum("parent_id", "first_parent");
        Assert.assertEquals("case_id", session.getNeededDatum().getDataId());

        // Use the registration action to open child registration form
        Action action = session.getPlatform().getDetail("m2_case_short")
                .getCustomActions(session.getEvaluationContext()).firstElement();
        session.executeStackOperations(action.getStackOperations(), session.getEvaluationContext());
        Assert.assertEquals("m1-f0", session.getCommand());

        // Pressing back takes to child list and then parent list
        session.stepBack();
        Assert.assertEquals("case_id", session.getNeededDatum().getDataId());
        session.stepBack();
        Assert.assertEquals("parent_id", session.getNeededDatum().getDataId());
    }

    @Test
    public void testChildCaseRegistration_forCompletion() {
        // Select module 2
        session.setCommand("m2");
        // Open child list
        session.setDatum("parent_id", "first_parent");
        Assert.assertEquals(SessionFrame.STATE_DATUM_VAL, session.getNeededData());
        Assert.assertEquals("case_id", session.getNeededDatum().getDataId());

        // Use the registration action to open child registration form
        Action action = session.getPlatform().getDetail("m2_case_short")
                .getCustomActions(session.getEvaluationContext()).firstElement();
        session.executeStackOperations(action.getStackOperations(), session.getEvaluationContext());
        Assert.assertEquals("m1-f0", session.getCommand());

        // Complete registration
        session.finishExecuteAndPop(session.getEvaluationContext());

        // We should be back to child list again
        Assert.assertEquals(SessionFrame.STATE_DATUM_VAL, session.getNeededData());
        Assert.assertEquals("case_id", session.getNeededDatum().getDataId());
    }
}
