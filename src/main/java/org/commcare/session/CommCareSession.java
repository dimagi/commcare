package org.commcare.session;

import org.commcare.suite.model.ComputedDatum;
import org.commcare.suite.model.Detail;
import org.commcare.suite.model.EntityDatum;
import org.commcare.suite.model.Entry;
import org.commcare.suite.model.FormEntry;
import org.commcare.suite.model.FormIdDatum;
import org.commcare.suite.model.Menu;
import org.commcare.suite.model.RemoteQueryDatum;
import org.commcare.suite.model.RemoteRequestEntry;
import org.commcare.suite.model.SessionDatum;
import org.commcare.suite.model.StackFrameStep;
import org.commcare.suite.model.StackOperation;
import org.commcare.suite.model.Suite;
import org.commcare.util.CommCarePlatform;
import org.javarosa.core.model.condition.EvaluationContext;
import org.javarosa.core.model.instance.DataInstance;
import org.javarosa.core.model.instance.ExternalDataInstance;
import org.javarosa.core.model.instance.InstanceInitializationFactory;
import org.javarosa.core.services.locale.Localizer;
import org.javarosa.core.util.OrderedHashtable;
import org.javarosa.core.util.externalizable.DeserializationException;
import org.javarosa.core.util.externalizable.ExtUtil;
import org.javarosa.core.util.externalizable.ExtWrapList;
import org.javarosa.xpath.XPathException;
import org.javarosa.xpath.XPathParseTool;
import org.javarosa.xpath.expr.FunctionUtils;
import org.javarosa.xpath.expr.XPathExpression;
import org.javarosa.xpath.parser.XPathSyntaxException;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.List;
import java.util.Set;
import java.util.Stack;
import java.util.Vector;

import javax.annotation.Nullable;

/**
 * Before arriving at the Form Entry phase, CommCare applications
 * need to determine what form to enter, and with what pre-requisites.
 *
 * A CommCare Session helps to encapsulate this information by identifying
 * the set of possible entry operations (Every piece of data needed to begin
 * entry) and specifying the operation which would most quickly filter our
 * the set of operations.
 *
 * NOTE: Currently horribly coupled to the platform.
 *
 * @author ctsims
 */
public class CommCareSession {

    private final CommCarePlatform platform;
    private StackFrameStep popped;
    private String currentCmd;

    /**
     * A table of all datums (id --> value) that are currently on the session stack
     */
    private final OrderedHashtable<String, String> collectedDatums;
    private String currentXmlns;

    /**
     * The current session frame data
     */
    protected SessionFrame frame;

    /**
     * The stack of pending Frames
     */
    private Stack<SessionFrame> frameStack;

    /**
     * Used by touchforms
     */
    @SuppressWarnings("unused")
    public CommCareSession() {
        this((CommCarePlatform)null);
    }

    public CommCareSession(CommCarePlatform platform) {
        this.platform = platform;
        this.collectedDatums = new OrderedHashtable<>();
        this.frame = new SessionFrame();
        this.frameStack = new Stack<>();
    }

    /**
     * Copy constructor
     */
    public CommCareSession(CommCareSession oldCommCareSession) {
        // NOTE: 'platform' is being copied in a shallow manner
        this.platform = oldCommCareSession.platform;

        if (oldCommCareSession.popped != null) {
            this.popped = new StackFrameStep(oldCommCareSession.popped);
        }
        this.currentCmd = oldCommCareSession.currentCmd;
        this.currentXmlns = oldCommCareSession.currentXmlns;
        this.frame = new SessionFrame(oldCommCareSession.frame);

        collectedDatums = new OrderedHashtable<>();
        for (Enumeration e = oldCommCareSession.collectedDatums.keys(); e.hasMoreElements(); ) {
            String key = (String)e.nextElement();
            collectedDatums.put(key, oldCommCareSession.collectedDatums.get(key));
        }

        this.frameStack = new Stack<>();
        // NOTE: can't use for/each due to J2ME build issues w/ Stack
        for (int i = 0; i < oldCommCareSession.frameStack.size(); i++) {
            frameStack.addElement(oldCommCareSession.frameStack.elementAt(i));
        }
    }

    /**
     * @param commandId the current command id
     * @return A list of all of the form entry actions that are possible with the given commandId
     * and the given list of already-collected datums
     */
    private Vector<Entry> getEntriesForCommand(String commandId) {
        Vector<Entry> entries = new Vector<>();
        if (commandId == null) {
            return entries;
        }
        for (Suite s : platform.getInstalledSuites()) {
            List<Menu> menusWithId = s.getMenusWithId(commandId);
            if (menusWithId != null) {
                for (Menu menu : menusWithId) {
                    entries.addAll(getStillValidEntriesFromMenu(menu));
                }
            }

            if (s.getEntries().containsKey(commandId)) {
                entries.addElement(s.getEntries().get(commandId));
            }
        }
        return entries;
    }

    @Nullable
    public FormEntry getEntryForNameSpace(String xmlns) {
        for (Suite suite : platform.getInstalledSuites()) {
            for (Enumeration e = suite.getEntries().elements(); e.hasMoreElements(); ) {
                Object suiteEntry = e.nextElement();
                if (suiteEntry instanceof FormEntry) {
                    FormEntry formEntry = ((FormEntry)suiteEntry);
                    if (formEntry.getXFormNamespace().equals(xmlns)) {
                        return formEntry;
                    }
                }
            }
        }
        return null;
    }

    private Vector<Entry> getStillValidEntriesFromMenu(Menu menu) {
        Hashtable<String, Entry> globalEntryMap = platform.getCommandToEntryMap();
        Vector<Entry> stillValid = new Vector<>();
        for (String cmd : menu.getCommandIds()) {
            Entry e = globalEntryMap.get(cmd);
            if (e == null) {
                throw new RuntimeException("No entry found for menu command [" + cmd + "]");
            }
            stillValid.addElement(e);
        }
        return stillValid;
    }

    public OrderedHashtable<String, String> getData() {
        return collectedDatums;
    }

    public CommCarePlatform getPlatform() {
        return this.platform;
    }

    /**
     * Based on the current state of the session, determine what information is needed next to
     * proceed
     *
     * @return One of the session SessionFrame.STATE_* strings, or null if
     * the session does not need anything else to proceed
     */
    public String getNeededData(EvaluationContext evalContext) {
        if (currentCmd == null) {
            return SessionFrame.STATE_COMMAND_ID;
        }

        Vector<Entry> entriesForCurrentCommand = getEntriesForCommand(currentCmd);
        String needDatum = getDataNeededByAllEntries(entriesForCurrentCommand);

        if (needDatum != null) {
            return needDatum;
        } else if (entriesForCurrentCommand.isEmpty()) {
            // No entries available directly within the current command, so we must need to select another menu
            return SessionFrame.STATE_COMMAND_ID;
        } else if (entriesForCurrentCommand.size() == 1
                && entriesForCurrentCommand.elementAt(0) instanceof RemoteRequestEntry
                && ((RemoteRequestEntry)entriesForCurrentCommand.elementAt(0)).getPostRequest().isRelevant(evalContext)) {
            return SessionFrame.STATE_SYNC_REQUEST;
        } else if (entriesForCurrentCommand.size() > 1 || !entriesForCurrentCommand.elementAt(0).getCommandId().equals(currentCmd)) {
            //the only other thing we can need is a form command. If there's
            //still more than one applicable entry, we need to keep going
            return SessionFrame.STATE_COMMAND_ID;
        } else {
            return null;
        }
    }

    /**
     * Checks that all entries have the same id for their first required data,
     * and if so, returns the data's associated session state. Otherwise,
     * returns null.
     */
    private String getDataNeededByAllEntries(Vector<Entry> entries) {
        String datumNeededByAllEntriesSoFar = null;
        String neededDatumId = null;
        for (Entry e : entries) {
            SessionDatum datumNeededForThisEntry =
                    getFirstMissingDatum(collectedDatums, e.getSessionDataReqs());
            if (datumNeededForThisEntry != null) {
                if (neededDatumId == null) {
                    neededDatumId = datumNeededForThisEntry.getDataId();
                    if (datumNeededForThisEntry instanceof EntityDatum) {
                        datumNeededByAllEntriesSoFar = SessionFrame.STATE_DATUM_VAL;
                    } else if (datumNeededForThisEntry instanceof ComputedDatum) {
                        datumNeededByAllEntriesSoFar = SessionFrame.STATE_DATUM_COMPUTED;
                    } else if (datumNeededForThisEntry instanceof RemoteQueryDatum) {
                        datumNeededByAllEntriesSoFar = SessionFrame.STATE_QUERY_REQUEST;
                    }
                } else if (!neededDatumId.equals(datumNeededForThisEntry.getDataId())) {
                    // data needed from the first entry isn't consistent with
                    // the current entry
                    return null;
                }
            } else {
                // we don't need any data, or the first data needed isn't
                // consistent across entries
                return null;
            }
        }

        return datumNeededByAllEntriesSoFar;
    }

    public String[] getHeaderTitles() {
        Hashtable<String, String> menus = new Hashtable<>();

        for (Suite s : platform.getInstalledSuites()) {
            for (Menu m : s.getMenus()) {
                menus.put(m.getId(), m.getName().evaluate());
            }
        }

        Vector<StackFrameStep> steps = frame.getSteps();
        String[] returnVal = new String[steps.size()];

        Hashtable<String, Entry> entries = platform.getCommandToEntryMap();
        int i = 0;
        for (StackFrameStep step : steps) {
            if (SessionFrame.STATE_COMMAND_ID.equals(step.getType())) {
                //Menu or form.
                if (menus.containsKey(step.getId())) {
                    returnVal[i] = menus.get(step.getId());
                } else if (entries.containsKey(step.getId())) {
                    returnVal[i] = entries.get(step.getId()).getText().evaluate();
                }
            } else if (SessionFrame.STATE_DATUM_VAL.equals(step.getType())) {
                //TODO: Grab the name of the case
            } else if (SessionFrame.STATE_DATUM_COMPUTED.equals(step.getType())) {
                //Nothing to do here
            }

            if (returnVal[i] != null) {
                //Menus contain a potential argument listing where that value is on the screen,
                //clear it out if it exists
                returnVal[i] = Localizer.processArguments(returnVal[i], new String[]{""}).trim();
            }

            ++i;
        }

        return returnVal;
    }

    /**
     * @return The next relevant datum for the current entry. Requires there to be
     * an entry on the stack
     */
    public SessionDatum getNeededDatum() {
        Vector<Entry> entries = getEntriesForCommand(getCommand());
        if (entries.isEmpty()) {
            throw new IllegalStateException("The current session has no valid entry");
        }
        return getNeededDatum(entries.firstElement());
    }

    /**
     * @param entry An entry which is consistent as a step on the stack
     * @return A session datum definition if one is pending. Null otherwise.
     */
    public SessionDatum getNeededDatum(Entry entry) {
        return getFirstMissingDatum(collectedDatums, entry.getSessionDataReqs());
    }

    /**
     * Return the first SessionDatum that is in allDatumsNeeded, but is not represented in
     * datumsCollectedSoFar
     */
    private SessionDatum getFirstMissingDatum(OrderedHashtable datumsCollectedSoFar,
                                              Vector<SessionDatum> allDatumsNeeded) {
        for (SessionDatum datum : allDatumsNeeded) {
            if (!datumsCollectedSoFar.containsKey(datum.getDataId())) {
                return datum;
            }
        }
        return null;
    }

    public Detail getDetail(String id) {
        for (Suite s : platform.getInstalledSuites()) {
            Detail d = s.getDetail(id);
            if (d != null) {
                return d;
            }
        }
        return null;
    }

    /**
     * When StackFrameSteps are parsed, those that are "datum" operations will be marked as type
     * "unknown". When we encounter a StackFrameStep of unknown type at runtime, we need to
     * determine whether it should be interpreted as STATE_DATUM_COMPUTED, STATE_COMMAND_ID,
     * or STATE_DATUM_VAL This primarily affects the behavior of stepBack().
     *
     * The logic being employed is: If there is a previous step on the stack whose entries would
     * have added this command, interpret it as a command. If there is an EntityDatum that
     * was have added this as an entity selection, interpret this as a datum_val. O
     * Otherwise, interpret it as a computed datum.
     */
    private String guessUnknownType(StackFrameStep popped) {
        String poppedId = popped.getId();
        for (StackFrameStep stackFrameStep : frame.getSteps()) {
            String commandId = stackFrameStep.getId();
            Vector<Entry> entries = getEntriesForCommand(commandId);
            for (Entry entry : entries) {
                String childCommand = entry.getCommandId();
                if (childCommand.equals(poppedId)) {
                    return SessionFrame.STATE_COMMAND_ID;
                }
                Vector<SessionDatum> data = entry.getSessionDataReqs();
                for (SessionDatum datum : data) {
                    if (datum instanceof EntityDatum &&
                            datum.getDataId().equals(poppedId)) {
                        return SessionFrame.STATE_DATUM_VAL;
                    }
                }
            }
        }
        return SessionFrame.STATE_DATUM_COMPUTED;
    }

    /**
     * @return true if the current state of the session is such that we are NOT waiting for
     * user-provided input, and false otherwise
     */
    private boolean shouldPopNext(EvaluationContext evalContext) {
        String neededData = getNeededData(evalContext);
        String poppedType = popped == null ? "" : popped.getType();

        if (popped != null) {
            Boolean wasLastStepCompound = (Boolean) popped.getExtra(SessionFrame.COMPOUND_STEP);
            Boolean isCurrentStepCompound = (Boolean) frame.getTopStepExtra(SessionFrame.COMPOUND_STEP);
            if (wasLastStepCompound && isCurrentStepCompound) {
                return true;
            }
        }

        if (neededData == null ||
                SessionFrame.STATE_DATUM_COMPUTED.equals(neededData) ||
                SessionFrame.STATE_DATUM_COMPUTED.equals(poppedType) ||
                topStepIsMark()) {
            return true;
        }

        if (SessionFrame.STATE_DATUM_VAL.equals(neededData)) {
            SessionDatum neededDatum = getNeededDatum();
            if (neededDatum instanceof EntityDatum) {
                EntityDatum entityDatum = ((EntityDatum)neededDatum);
                if (entityDatum.getCurrentAutoselectableCase(evalContext) != null
                        && entityDatum.getLongDetail() == null) {
                    // If the next needed datum is an entity for which there would be an
                    // auto-selected case in the current eval context, AND there is no case detail,
                    // then we want to step back over this
                    return true;
                }
            }
        }

        return SessionFrame.STATE_UNKNOWN.equals(poppedType)
                && guessUnknownType(popped).equals(SessionFrame.STATE_DATUM_COMPUTED);
    }

    public void stepBack(EvaluationContext evalContext) {
        // Pop the first thing off of the stack frame, no matter what
        popStepInCurrentSessionFrame();

        // Keep popping things off until the value of needed data indicates that we are back to
        // somewhere where we are waiting for user-provided input
        while (shouldPopNext(evalContext)) {
            popStepInCurrentSessionFrame();
        }
    }

    private boolean topStepIsMark() {
        return !frame.getSteps().isEmpty()
                && SessionFrame.STATE_MARK.equals(frame.getSteps().lastElement().getType());
    }

    public void popStep(EvaluationContext evalContext) {
        popStepInCurrentSessionFrame();

        while (getNeededData(evalContext) == null
                || topStepIsMark()) {
            popStepInCurrentSessionFrame();
        }
    }

    private void popStepInCurrentSessionFrame() {
        StackFrameStep recentPop = frame.popStep();

        //TODO: Check the "base state" of the frame after popping to see if we invalidated the stack
        syncState();
        popped = recentPop;
    }

    public void setDatum(String keyId, String value) {
        setDatum(SessionFrame.STATE_DATUM_VAL, keyId, value);
    }

    public void setDatum(String action, String keyId, String value) {
        frame.pushStep(new StackFrameStep(action, keyId, value));
        syncState();
    }

    /**
     * Set a (xml) data instance as the result to a session query datum.
     * The instance is available in session's evaluation context until the corresponding query frame is removed
     */
    public void setQueryDatum(ExternalDataInstance queryResultInstance) {
        SessionDatum datum = getNeededDatum();
        if (datum instanceof RemoteQueryDatum) {
            StackFrameStep step =
                    new StackFrameStep(SessionFrame.STATE_QUERY_REQUEST,
                            datum.getDataId(), datum.getValue(), queryResultInstance);
            frame.pushStep(step);
            syncState();
        } else {
            throw new RuntimeException("Trying to set query successful when one isn't needed.");
        }
    }

    public void setComputedDatum(EvaluationContext ec) throws XPathException {
        SessionDatum datum = getNeededDatum();
        XPathExpression form;
        try {
            form = XPathParseTool.parseXPath(datum.getValue());
        } catch (XPathSyntaxException e) {
            e.printStackTrace();
            throw new RuntimeException(e.getMessage());
        }
        if (datum instanceof FormIdDatum) {
            setXmlns(FunctionUtils.toString(form.eval(ec)));
            setDatum("", "awful");
        } else if (datum instanceof ComputedDatum) {
            setDatum(datum.getDataId(), FunctionUtils.toString(form.eval(ec)));
        }
    }

    public void setXmlns(String xmlns) {
        frame.pushStep(new StackFrameStep(SessionFrame.STATE_FORM_XMLNS, xmlns, null));
        syncState();
    }

    public void setCommand(String commandId) {
        frame.pushStep(new StackFrameStep(SessionFrame.STATE_COMMAND_ID, commandId, null));
        syncState();
    }

    public void syncState() {
        this.collectedDatums.clear();
        this.currentCmd = null;
        this.currentXmlns = null;
        this.popped = null;

        for (StackFrameStep step : frame.getSteps()) {
            if (SessionFrame.STATE_DATUM_VAL.equals(step.getType()) ||
                    SessionFrame.STATE_DATUM_COMPUTED.equals(step.getType()) ||
                    SessionFrame.STATE_UNKNOWN.equals(step.getType()) &&
                            (guessUnknownType(step).equals(SessionFrame.STATE_DATUM_COMPUTED)
                                    || guessUnknownType(step).equals(SessionFrame.STATE_DATUM_VAL))) {
                String key = step.getId();
                String value = step.getValue();
                if (key != null && value != null) {
                    collectedDatums.put(key, value);
                }
            } else if (SessionFrame.STATE_QUERY_REQUEST.equals(step.getType())) {
                collectedDatums.put(step.getId(), step.getValue());
            } else if (SessionFrame.STATE_COMMAND_ID.equals(step.getType())) {
                this.currentCmd = step.getId();
            } else if (SessionFrame.STATE_FORM_XMLNS.equals(step.getType())) {
                this.currentXmlns = step.getId();
            }
        }
    }

    public StackFrameStep getPoppedStep() {
        return popped;
    }

    public String getForm() {
        if (this.currentXmlns != null) {
            return this.currentXmlns;
        }
        String command = getCommand();
        if (command == null) {
            return null;
        }

        Entry e = platform.getCommandToEntryMap().get(command);
        if (e.isView() || e.isRemoteRequest()) {
            return null;
        } else {
            return ((FormEntry)e).getXFormNamespace();
        }
    }

    public String getCommand() {
        return this.currentCmd;
    }

    /**
     * Clear the current stack frame and release any pending
     * stack frames (completely clearing the session)
     */
    public void clearAllState() {
        frame = new SessionFrame();
        frameStack.removeAllElements();
        syncState();
    }

    /**
     * Retrieve an evaluation context in which to evaluate expressions in the
     * current session state
     *
     * @param iif the instance initailzier for the current platform
     * @return Evaluation context for current session state
     */
    public EvaluationContext getEvaluationContext(InstanceInitializationFactory iif) {
        return this.getEvaluationContext(iif, getCommand(), null);
    }

    /**
     * Retrieve an evaluation context in which to evaluate expressions in the context of a given
     * command in the installed app
     *
     * @param iif the instance initializer for the current platform
     * @return Evaluation context for a command in the installed app
     */
    public EvaluationContext getEvaluationContext(InstanceInitializationFactory iif,
                                                  String command,
                                                  Set<String> instancesToInclude) {
        if (command == null) {
            return new EvaluationContext(null);
        }
        Vector<Entry> entries = getEntriesForCommand(command);

        if (entries.size() == 0) {
            return new EvaluationContext(null);
        }

        Entry entry = entries.elementAt(0);

        Hashtable<String, DataInstance> instancesInScope = entry.getInstances(instancesToInclude);

        for (Enumeration en = instancesInScope.keys(); en.hasMoreElements(); ) {
            String key = (String)en.nextElement();
            instancesInScope.put(key, instancesInScope.get(key).initialize(iif, key));
        }
        addInstancesFromFrame(instancesInScope, iif);

        return new EvaluationContext(null, instancesInScope);
    }

    private void addInstancesFromFrame(Hashtable<String, DataInstance> instanceMap,
                                       InstanceInitializationFactory iif) {
        for (StackFrameStep step : frame.getSteps()) {
            if (step.hasXmlInstance()) {
                instanceMap.put(step.getId(), step.getXmlInstance().initialize(iif, step.getId()));
            }
        }
    }

    /**
     * @return A copy of the current frame with UNKNOWN types evaluated to their best guess
     */
    public SessionFrame getFrame() {
        SessionFrame copyFrame = new SessionFrame(frame);
        for (StackFrameStep step : copyFrame.getSteps()) {
            if (step.getType().equals(SessionFrame.STATE_UNKNOWN)) {
                step.setType(guessUnknownType(step));
            }
        }
        return copyFrame;
    }

    /**
     * Executes a set of stack operations against the current session environment.
     *
     * The context data and session data provided will consistently match the live frame
     * when the operations began executing, although frame operations will be executed
     * against the most recent frame. (IE: If a new frame is pushed here, xpath expressions
     * calculated within it will be evaluated against the starting, but <push> actions
     * will happen against the newly pushed frame)
     *
     * @return True if stack ops triggered a rewind, used for determining stack clean-up logic
     */
    public boolean executeStackOperations(Vector<StackOperation> ops, EvaluationContext ec) {
        // The on deck frame is the frame that is the target of operations that execute
        // as part of this stack update. If at the end of the stack ops the frame on deck
        // doesn't match the current (living) frame, it will become the the current frame
        SessionFrame onDeck = frame;

        boolean didRewind = false;
        for (StackOperation op : ops) {
            if (!processStackOp(op, ec)) {
                // rewind occurred, stop processing futher ops.
                didRewind = true;
                break;
            }
        }

        popOrSync(onDeck, didRewind);
        return didRewind;
    }

    /**
     * @return false if current frame was rewound
     */
    private boolean processStackOp(StackOperation op,
                                   EvaluationContext ec) {
        switch (op.getOp()) {
            case StackOperation.OPERATION_CREATE:
                createFrame(new SessionFrame(), op, ec);
                break;
            case StackOperation.OPERATION_PUSH:
                if (!performPush(op, ec)) {
                    return false;
                }
                break;
            case StackOperation.OPERATION_CLEAR:
                performClearOperation(op, ec);
                break;
            default:
                throw new RuntimeException("Undefined stack operation: " + op.getOp());
        }

        return true;
    }

    private void createFrame(SessionFrame createdFrame,
                             StackOperation op, EvaluationContext ec) {
        if (op.isOperationTriggered(ec)) {
            performPushInner(op, createdFrame, ec, false);
            pushNewFrame(createdFrame);
        }
    }

    /**
     * @return false if push was terminated early by a 'rewind'
     */
    private boolean performPushInner(StackOperation op, SessionFrame frame, EvaluationContext ec, boolean isCompoundStep) {
        for (StackFrameStep step : op.getStackFrameSteps()) {
            if (SessionFrame.STATE_REWIND.equals(step.getType())) {
                if (frame.rewindToMarkAndSet(step, ec)) {
                    return false;
                }
                // if no mark is found ignore the rewind and continue
            } else {
                pushFrameStep(step, frame, ec);
                frame.addExtraTopStep(SessionFrame.COMPOUND_STEP, isCompoundStep);
            }
        }
        return true;
    }

    private void pushFrameStep(StackFrameStep step, SessionFrame frame, EvaluationContext ec) {
        SessionDatum neededDatum = null;
        if (SessionFrame.STATE_MARK.equals(step.getType())) {
            neededDatum = getNeededDatumForFrame(this, frame);
        }
        frame.pushStep(step.defineStep(ec, neededDatum));
    }

    private static SessionDatum getNeededDatumForFrame(CommCareSession session,
                                                       SessionFrame targetFrame) {
        CommCareSession sessionCopy = new CommCareSession(session);
        sessionCopy.frame = targetFrame;
        sessionCopy.syncState();
        return sessionCopy.getNeededDatum();
    }

    /**
     * @return false if push was terminated early by a 'rewind'
     */
    private boolean performPush(StackOperation op, EvaluationContext ec) {
        if (op.isOperationTriggered(ec)) {
            return performPushInner(op, frame, ec, true);
        }
        return true;
    }

    private void pushNewFrame(SessionFrame matchingFrame) {
        // Before we can push a frame onto the stack, we need to
        // make sure the stack is clean. This means that if the
        // current frame has a snapshot, we've gotta make sure
        // the existing frames are still valid.

        // TODO: We might want to handle this differently in the future,
        // so that we can account for the invalidated frames in the ui
        // somehow.
        cleanStack();

        frameStack.push(matchingFrame);
    }

    private void performClearOperation(StackOperation op,
                                       EvaluationContext ec) {
        if (op.isOperationTriggered(ec)) {
            frameStack.removeElement(frame);
        }
    }

    private boolean popOrSync(SessionFrame onDeck, boolean didRewind) {
        if (!frame.isDead() && frame != onDeck) {
            // If the current frame isn't dead, and isn't on deck, that means we've pushed
            // in new frames and need to load up the correct one

            if (!finishAndPop(didRewind)) {
                // Somehow we didn't end up with any frames after that? that's incredibly weird, I guess
                // we should just start over.
                clearAllState();
            }
            return true;
        } else {
            syncState();
            return false;
        }
    }

    /**
     * Checks to see if the current frame has a clean snapshot. If
     * not, clears the stack and the snapshot (since the snapshot can
     * only be relevant to the existing frames)
     */
    private void cleanStack() {
        // See whether the current frame was incompatible with its start
        // state.
        if (frame.isSnapshotIncompatible()) {
            // If it is, our frames can no longer make sense.
            frameStack.removeAllElements();
            frame.clearSnapshot();
        }
    }

    /**
     * Called after a session has been completed. Executes and pending stack operations
     * from the current session, completes the session, and pops the top of any pending
     * frames into execution.
     *
     * @return True if there was a pending frame and it has been
     * popped into the current session. False if the stack was empty
     * and the session is over.
     */
    public boolean finishExecuteAndPop(EvaluationContext ec) {
        Vector<StackOperation> ops = getCurrentEntry().getPostEntrySessionOperations();

        //Let the session know that the current frame shouldn't work its way back onto the stack
        markCurrentFrameForDeath();

        //First, see if we have operations to run
        boolean didRewind = false;
        if (ops.size() > 0) {
            didRewind = executeStackOperations(ops, ec);
        }
        return finishAndPop(didRewind);
    }

    /**
     * Complete the current session (and perform any cleanup), then
     * check the stack for any pending frames, and load the top one
     * into the current session if so.
     *
     * @param didRewind True if rewind occurred during stack pop.
     *                  Helps determine post-pop stack cleanup logic
     * @return True if there was a pending frame and it has been
     * popped into the current session. False if the stack was empty
     * and the session is over.
     */
    private boolean finishAndPop(boolean didRewind) {
        cleanStack();

        if (frameStack.empty()) {
            return didRewind;
        } else {
            frame = frameStack.pop();
            //Ok, so if _after_ popping from the stack, we still have
            //stack members, we need to be careful about making sure
            //that they won't get triggered if we abandon the current
            //frame
            if (!frameStack.isEmpty()) {
                frame.captureSnapshot();
            }

            syncState();
            return true;
        }
    }

    /**
     * Retrieve the single valid entry for the current session, should be called only
     * when the current request is fully built
     *
     * @return The unique valid entry built on this session. Will throw an exception if there isn't
     * a unique entry.
     */
    public Entry getCurrentEntry() {
        Vector<Entry> e = getEntriesForCommand(getCommand());
        if (e.size() > 1) {
            throw new IllegalStateException("The current session does not contain a single valid entry");
        }
        if (e.isEmpty()) {
            throw new IllegalStateException("The current session has no valid entry");
        }
        return e.elementAt(0);
    }

    /**
     * Retrieves a valid datum definition in the current session's history
     * which contains a selector for the datum Id provided.
     *
     * Can be used to resolve the context about an item that
     * has been selected in this session.
     *
     * @param datumId The ID of a session datum in the session history
     * @return An Entry object which contains a selector for that datum
     * which is in this session history
     */
    public EntityDatum findDatumDefinition(String datumId) {
        //We're performing a walk down the entities in this session here,
        //we should likely generalize this to make it easier to do it for other
        //operations

        Vector<StackFrameStep> steps = frame.getSteps();

        int stepId = -1;
        //walk to our datum
        for (int i = 0; i < steps.size(); ++i) {
            StackFrameStep step = steps.elementAt(i);
            if (step.getId().equals(datumId) && stepIsOfType(step, SessionFrame.STATE_DATUM_VAL)) {
                stepId = i;
                break;
            }
        }
        if (stepId == -1) {
            System.out.println("I don't think this should be possible...");
            return null;
        }

        //ok, so now we have our step, we want to walk backwards until we find the entity
        //associated with our ID
        for (int i = stepId; i >= 0; i--) {
            if (steps.elementAt(i).getType().equals(SessionFrame.STATE_COMMAND_ID)) {
                Vector<Entry> entries = this.getEntriesForCommand(steps.elementAt(i).getId());

                //TODO: Don't we know the right entry? What if our last command is an actual entry?
                for (Entry entry : entries) {
                    for (SessionDatum datum : entry.getSessionDataReqs()) {
                        if (datum.getDataId().equals(datumId) && datum instanceof EntityDatum) {
                            return (EntityDatum)datum;
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * @return true if the given step is either explicitly of the given type, or if it is of
     * unknown type and guessUnknownType() returns the given type
     */
    private boolean stepIsOfType(StackFrameStep step, String desiredType) {
        return desiredType.equals(step.getType()) ||
                (SessionFrame.STATE_UNKNOWN.equals(step.getType())
                        && desiredType.equals(guessUnknownType(step)));
    }

    private void markCurrentFrameForDeath() {
        frame.kill();
    }

    /**
     * Does the command only have a view entry, and no other actions available
     * to take?
     */
    public boolean isViewCommand(String command) {
        Vector<Entry> entries = this.getEntriesForCommand(command);
        return entries.size() == 1 && entries.elementAt(0).isView();
    }

    public boolean isRemoteRequestCommand(String command) {
        Vector<Entry> entries = this.getEntriesForCommand(command);
        return entries.size() == 1 && entries.elementAt(0).isRemoteRequest();
    }

    public void addExtraToCurrentFrameStep(String key, Object value) {
        frame.addExtraTopStep(key, value);
    }

    public Object getCurrentFrameStepExtra(String key) {
        return frame.getTopStepExtra(key);
    }

    /**
     * Builds a session by restoring a serialized SessionFrame and syncing from that.
     * Doesn't support restoring the frame stack
     */
    public static CommCareSession restoreSessionFromStream(CommCarePlatform ccPlatform,
                                                           DataInputStream inputStream)
            throws DeserializationException, IOException {
        SessionFrame restoredFrame = new SessionFrame();
        restoredFrame.readExternal(inputStream, ExtUtil.defaultPrototypes());

        CommCareSession restoredSession = new CommCareSession(ccPlatform);
        restoredSession.frame = restoredFrame;
        Vector<SessionFrame> frames = (Vector<SessionFrame>)ExtUtil.read(inputStream, new ExtWrapList(SessionFrame.class), null);
        Stack<SessionFrame> stackFrames = new Stack<>();
        while (!frames.isEmpty()) {
            SessionFrame lastElement = frames.lastElement();
            frames.remove(lastElement);
            stackFrames.push(lastElement);
        }
        restoredSession.setFrameStack(stackFrames);
        restoredSession.syncState();

        return restoredSession;
    }

    public void serializeSessionState(DataOutputStream outputStream) throws IOException {
        frame.writeExternal(outputStream);
        ExtUtil.write(outputStream, new ExtWrapList(frameStack));
    }

    protected void setFrameStack(Stack<SessionFrame> frameStack) {
        this.frameStack = frameStack;
    }

    public Stack<SessionFrame> getFrameStack() {
        return this.frameStack;
    }
}
