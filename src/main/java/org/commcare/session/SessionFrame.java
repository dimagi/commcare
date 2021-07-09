package org.commcare.session;

import org.commcare.suite.model.StackFrameStep;
import org.javarosa.core.model.condition.EvaluationContext;
import org.javarosa.core.util.externalizable.DeserializationException;
import org.javarosa.core.util.externalizable.ExtUtil;
import org.javarosa.core.util.externalizable.ExtWrapList;
import org.javarosa.core.util.externalizable.Externalizable;
import org.javarosa.core.util.externalizable.PrototypeFactory;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Vector;

/**
 * A Session Frame contains the actions that a user has taken while
 * navigating through a CommCare application. Each action is represented
 * as a StackFrameStep
 *
 * @author ctsims
 */
public class SessionFrame implements Externalizable {

    // region - Possible states of a SessionFrame, which describes what type of information the
    // session needs to proceed with its next step. This is analogously represented as the type
    // of the current StackFrameStep

    /**
     * CommCare needs a Command (an entry, view, etc) to proceed. Generally sitting on a menu screen.
     */
    public static final String STATE_COMMAND_ID = "COMMAND_ID";

    /**
     * CommCare needs any piece of information coming from a datum val (other than a computed datum)
     */
    public static final String STATE_DATUM_VAL = "CASE_ID";

    /**
     * Signifies that the frame should be rewound to the last MARK, setting the
     * MARK's datum id (which is the next needed datum at that point in the frame)
     * to the value carried in the rewind.
     */
    public static final String STATE_REWIND = "REWIND";

    /**
     * Deliniates a rewind point. Contains a datum id, which corresponds to
     * the next needed datum at that point in the frame.
     */
    public static final String STATE_MARK = "MARK";

    /**
     * CommCare needs a computed xpath value to proceed
     */
    public static final String STATE_DATUM_COMPUTED = "COMPUTED_DATUM";

    /**
     * CommCare needs to make a synchronous server request
     */
    public static final String STATE_SYNC_REQUEST = "SYNC_REQUEST";

    /**
     * CommCare needs to make a query request to server
     */
    public static final String STATE_QUERY_REQUEST = "QUERY_REQUEST";

    /**
     * Unknown at parse time - this could be a COMMAND or a COMPUTED, best
     * guess determined by the CommCareSession based on the current frame
     */
    public static final String STATE_UNKNOWN = "STATE_UNKNOWN";

    /**
     * CommCare needs the XMLNS of the form to be entered to proceed
     */
    public static final String STATE_FORM_XMLNS = "FORM_XMLNS";

    /**
     * Used to mark a set of push stack operations as a compound step, so that at the time of
     * popping back to a previous state, we can remove all those operations.
     */
    public static final String COMPOUND_STEP = "COMPOUND_STEP";

    // endregion - states

    private Vector<StackFrameStep> steps = new Vector<>();
    private Vector<StackFrameStep> snapshot = new Vector<>();

    /**
     * A Frame is dead if it's execution path has finished and it shouldn't
     * be considered part of the stack
     */
    private boolean dead = false;

    /**
     * Create a new, un-id'd session frame
     */
    public SessionFrame() {

    }

    /**
     * Copy constructor
     */
    public SessionFrame(SessionFrame oldSessionFrame) {
        for (StackFrameStep step : oldSessionFrame.steps) {
            steps.addElement(new StackFrameStep(step));
        }
        for (StackFrameStep snapshotStep : oldSessionFrame.snapshot) {
            snapshot.addElement(new StackFrameStep(snapshotStep));
        }
        this.dead = oldSessionFrame.dead;
    }


    public Vector<StackFrameStep> getSteps() {
        return steps;
    }

    public StackFrameStep popStep() {
        StackFrameStep recentPop = null;

        if (steps.size() > 0) {
            recentPop = steps.elementAt(steps.size() - 1);
            steps.removeElementAt(steps.size() - 1);
        }
        return recentPop;
    }

    protected boolean rewindToMarkAndSet(StackFrameStep step, EvaluationContext evalContext) {
        int markIndex = getLatestMarkPosition(steps);

        if (markIndex >= 0) {
            String markDatumId = steps.get(markIndex).getId();
            steps = new Vector<>(steps.subList(0, markIndex));
            if (step.getValue() != null) {
                String evaluatedStepValue = step.evaluateValue(evalContext);
                steps.addElement(new StackFrameStep(SessionFrame.STATE_UNKNOWN, markDatumId, evaluatedStepValue));
            }
            return true;
        } else {
            return false;
        }
    }

    private static int getLatestMarkPosition(Vector<StackFrameStep> steps) {
        for (int index = steps.size() - 1; index >= 0; index--) {
            if (SessionFrame.STATE_MARK.equals(steps.get(index).getType())) {
                return index;
            }
        }
        return -1;
    }

    public void pushStep(StackFrameStep step) {
        steps.addElement(step);
    }

    /**
     * Requests that the frame capture an original snapshot of its state.
     * This snapshot can be referenced later to compare the eventual state
     * of the frame to an earlier point
     */
    public synchronized void captureSnapshot() {
        snapshot.removeAllElements();
        for (StackFrameStep s : steps) {
            snapshot.addElement(s);
        }
    }

    /**
     * Determines whether the current frame state is incompatible with
     * a previously snapshotted frame state, if one exists. If no snapshot
     * exists, this method will return false.
     *
     * Compatibility is determined by checking that each step in the previous
     * snapshot is matched by an identical step in the current snapshot.
     */
    public synchronized boolean isSnapshotIncompatible() {
        //No snapshot, can't be incompatible.
        if (snapshot.isEmpty()) {
            return false;
        }

        if (snapshot.size() > steps.size()) {
            return true;
        }

        //Go through each step in the snapshot
        for (int i = 0; i < snapshot.size(); ++i) {
            if (!snapshot.elementAt(i).equals(steps.elementAt(i))) {
                return true;
            }
        }

        //If we didn't find anything wrong, we're good to go!
        return false;
    }

    public synchronized void clearSnapshot() {
        this.snapshot.removeAllElements();
    }

    /**
     * @return Whether this frame is dead or not. Dead frames have finished their session
     * and can never again become part of the stack.
     */
    public boolean isDead() {
        return dead;
    }

    /**
     * Kill this frame, ensuring it will never return to the stack.
     */
    public void kill() {
        dead = true;
    }

    public synchronized void addExtraTopStep(String key, Object value) {
        if (!steps.isEmpty()) {
            StackFrameStep topStep = steps.elementAt(steps.size() - 1);
            topStep.addExtra(key, value);
        }
    }

    public synchronized Object getTopStepExtra(String key) {
        if (!steps.isEmpty()) {
            StackFrameStep topStep = steps.elementAt(steps.size() - 1);
            return topStep.getExtra(key);
        }
        return null;
    }

    @Override
    public void readExternal(DataInputStream in, PrototypeFactory pf) throws IOException, DeserializationException {
        steps = (Vector<StackFrameStep>)ExtUtil.read(in, new ExtWrapList(StackFrameStep.class), pf);
        snapshot = (Vector<StackFrameStep>)ExtUtil.read(in, new ExtWrapList(StackFrameStep.class), pf);
        dead = ExtUtil.readBool(in);
    }

    @Override
    public void writeExternal(DataOutputStream out) throws IOException {
        ExtUtil.write(out, new ExtWrapList(steps));
        ExtUtil.write(out, new ExtWrapList(snapshot));
        ExtUtil.writeBool(out, dead);
    }

    @Override
    public String toString() {
        StringBuilder output = new StringBuilder();

        if (!steps.isEmpty()) {
            output.append("frame:\t");
            prettyPrintSteps(steps, output);
        }

        if (!snapshot.isEmpty()) {
            output.append("\nsnapshot:\t");
            prettyPrintSteps(snapshot, output);
        }

        if (dead) {
            output.append("\n[DEAD]");
        }

        return output.toString();
    }

    private void prettyPrintSteps(Vector<StackFrameStep> stepsToPrint,
                                    StringBuilder stringBuilder) {
        if (!stepsToPrint.isEmpty()) {
            // prevent trailing '/' by intercalating all but last element
            for (int i = 0; i < stepsToPrint.size() - 1; i++) {
                StackFrameStep step = stepsToPrint.elementAt(i);
                stringBuilder.append(step.toString()).append(" \\ ");
            }
            // add the last elem
            stringBuilder.append(stepsToPrint.lastElement());
        }
    }
}
