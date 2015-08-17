package org.commcare.util;

import org.commcare.suite.model.StackFrameStep;

import java.util.Vector;

/**
 * A Session Frame contains the actions that a user has taken while
 * navigating through a CommCare application. Each action is represented
 * as a StackFrameStep
 *
 * @author ctsims
 */
public class SessionFrame {


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
     * CommCare needs a computed xpath value to proceed *
     */
    public static final String STATE_DATUM_COMPUTED = "COMPUTED_DATUM";

    /**
     * CommCare needs the XMLNS of the form to be entered to proceed *
     */
    public static final String STATE_FORM_XMLNS = "FORM_XMLNS";

    // endregion - states



    private String frameId;
    protected Vector<StackFrameStep> steps = new Vector<StackFrameStep>();

    protected Vector<StackFrameStep> snapshot;

    /**
     * A Frame is dead if it's execution path has finished and it shouldn't be considered part of the stack *
     */
    boolean dead = false;

    /**
     * Create a new, un-id'd session frame
     */
    public SessionFrame() {

    }


    public SessionFrame(String frameId) {
        this.frameId = frameId;
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


    public void pushStep(StackFrameStep step) {
        steps.addElement(step);
    }


    public String getFrameId() {
        return frameId;
    }

    /**
     * Requests that the frame capture an original snapshot of its state.
     * This snapshot can be referenced later to compare the eventual state
     * of the frame to an earlier point
     */
    public void captureSnapshot() {
        synchronized (steps) {
            snapshot = new Vector<StackFrameStep>();
            for (StackFrameStep s : steps) {
                snapshot.addElement(s);
            }
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
    public boolean isSnapshotIncompatible() {
        synchronized (steps) {
            //No snapshot, can't be incompatible.
            if (snapshot == null) {
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
    }

    public void clearSnapshot() {
        synchronized (steps) {
            this.snapshot = null;
        }
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
}
