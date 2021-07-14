package org.javarosa.core.model;

import org.commcare.cases.query.QueryContext;
import org.commcare.cases.query.ScopeLimitedReferenceRequestCache;
import org.commcare.modern.util.Pair;
import org.javarosa.core.log.WrappedException;
import org.javarosa.core.model.actions.Action;
import org.javarosa.core.model.actions.ActionController;
import org.javarosa.core.model.actions.FormSendCalloutHandler;
import org.javarosa.core.model.condition.Condition;
import org.javarosa.core.model.condition.Constraint;
import org.javarosa.core.model.condition.EvaluationContext;
import org.javarosa.core.model.condition.IConditionExpr;
import org.javarosa.core.model.condition.IFunctionHandler;
import org.javarosa.core.model.condition.Recalculate;
import org.javarosa.core.model.condition.Triggerable;
import org.javarosa.core.model.data.IAnswerData;
import org.javarosa.core.model.data.IntegerData;
import org.javarosa.core.model.data.InvalidData;
import org.javarosa.core.model.data.SelectMultiData;
import org.javarosa.core.model.data.SelectOneData;
import org.javarosa.core.model.data.helper.Selection;
import org.javarosa.core.model.instance.AbstractTreeElement;
import org.javarosa.core.model.instance.DataInstance;
import org.javarosa.core.model.instance.FormInstance;
import org.javarosa.core.model.instance.InstanceInitializationFactory;
import org.javarosa.core.model.instance.InvalidReferenceException;
import org.javarosa.core.model.instance.TreeElement;
import org.javarosa.core.model.instance.TreeReference;
import org.javarosa.core.model.trace.EvaluationTrace;
import org.javarosa.core.model.trace.ReducingTraceReporter;
import org.javarosa.core.model.util.restorable.RestoreUtils;
import org.javarosa.core.model.utils.InstrumentationUtils;
import org.javarosa.core.model.utils.ItemSetUtils;
import org.javarosa.core.services.locale.Localizer;
import org.javarosa.core.services.storage.IMetaData;
import org.javarosa.core.util.CacheTable;
import org.javarosa.core.util.DataUtil;
import org.javarosa.core.util.ShortestCycleAlgorithm;
import org.javarosa.core.util.externalizable.DeserializationException;
import org.javarosa.core.util.externalizable.ExtUtil;
import org.javarosa.core.util.externalizable.ExtWrapList;
import org.javarosa.core.util.externalizable.ExtWrapListPoly;
import org.javarosa.core.util.externalizable.ExtWrapMap;
import org.javarosa.core.util.externalizable.ExtWrapNullable;
import org.javarosa.core.util.externalizable.ExtWrapTagged;
import org.javarosa.core.util.externalizable.PrototypeFactory;
import org.javarosa.model.xform.XPathReference;
import org.javarosa.xpath.XPathException;
import org.javarosa.xpath.XPathTypeMismatchException;
import org.javarosa.xpath.analysis.AnalysisInvalidException;
import org.javarosa.xpath.analysis.TreeReferenceAccumulatingAnalyzer;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.Vector;

import javax.annotation.Nullable;

import datadog.trace.api.Trace;
import io.opentracing.Span;
import io.opentracing.util.GlobalTracer;


/**
 * Definition of a form. This has some meta data about the form definition and a
 * collection of groups together with question branching or skipping rules.
 *
 * @author Daniel Kayiwa, Drew Roos
 */
public class FormDef implements IFormElement, IMetaData,
        ActionController.ActionResultProcessor {
    public static final String STORAGE_KEY = "FORMDEF";
    private static final int TEMPLATING_RECURSION_LIMIT = 10;

    /**
     * Hierarchy of questions, groups and repeats in the form
     */
    private Vector<IFormElement> children;
    /**
     * A collection of group definitions.
     */
    private int id;
    /**
     * The numeric unique identifier of the form definition on the local device
     */
    private String title;
    /**
     * The display title of the form.
     */
    private String name;

    private Vector<XFormExtension> extensions;

    /**
     * A unique external name that is used to identify the form between machines
     */
    private Localizer localizer;

    // This list is topologically ordered, meaning for any tA
    // and tB in the list, where tA comes before tB, evaluating tA cannot
    // depend on any result from evaluating tB
    private ArrayList<Triggerable> triggerables;

    // <IConditionExpr> contents of <output> tags that serve as parameterized
    // arguments to captions
    private Vector outputFragments;

    /**
     * Map references to the calculate/relevancy conditions that depend on that
     * reference's value. Used to trigger re-evaluation of those conditionals
     * when the reference is updated.
     */
    private HashMap<TreeReference, Vector<Triggerable>> triggerIndex;

    /**
     * Associates repeatable nodes with the Condition that determines their
     * relevancy.
     */
    private HashMap<TreeReference, Condition> conditionRepeatTargetIndex;

    public EvaluationContext exprEvalContext;

    // XML ID's cannot start with numbers, so this should never conflict
    private static final String DEFAULT_SUBMISSION_PROFILE = "1";

    private Hashtable<String, SubmissionProfile> submissionProfiles;

    /**
     * Secondary and external instance pointers
     */
    private Hashtable<String, DataInstance> formInstances;

    private FormInstance mainInstance = null;

    private boolean mDebugModeEnabled = false;

    private final Vector<Triggerable> triggeredDuringInsert = new Vector<>();

    private ActionController actionController;
    //If this instance is just being edited, don't fire end of form events
    private boolean isCompletedInstance;

    private boolean mProfilingEnabled = false;
    private boolean useExpressionCaching;

    FormSendCalloutHandler sendCalloutHandler;

    /**
     * Cache children that trigger target will cascade to. For speeding up
     * calculations that determine what needs to be triggered when a value
     * changes.
     */
    private final CacheTable<TreeReference, ArrayList<TreeReference>> cachedCascadingChildren =
            new CacheTable<>();

    public FormDef() {
        this(false);
    }

    public FormDef(boolean useExpressionCaching) {
        setID(-1);
        setChildren(null);
        triggerables = new ArrayList<>();
        triggerIndex = new HashMap<>();
        //This is kind of a wreck...
        setEvaluationContext(new EvaluationContext(null));
        outputFragments = new Vector();
        submissionProfiles = new Hashtable<>();
        formInstances = new Hashtable<>();
        extensions = new Vector<>();
        actionController = new ActionController();
        this.useExpressionCaching = useExpressionCaching;
    }

    /**
     * Getters and setters for the vectors tha
     */
    public void addNonMainInstance(DataInstance instance) {
        formInstances.put(instance.getInstanceId(), instance);
        this.setEvaluationContext(new EvaluationContext(null));
    }

    /**
     * Get an instance based on a name
     */
    public DataInstance getNonMainInstance(String name) {
        if (!formInstances.containsKey(name)) {
            return null;
        }
        return formInstances.get(name);
    }

    public Enumeration getNonMainInstances() {
        return formInstances.elements();
    }

    /**
     * Set the main instance
     */
    public void setInstance(FormInstance fi) {
        mainInstance = fi;
        fi.setFormId(getID());
        this.setEvaluationContext(new EvaluationContext(null));
        attachControlsToInstanceData();
    }

    /**
     * Get the main instance
     */
    public FormInstance getMainInstance() {
        return mainInstance;
    }

    public FormInstance getInstance() {
        return getMainInstance();
    }

    // ---------- child elements
    @Override
    public void addChild(IFormElement fe) {
        this.children.addElement(fe);
    }

    @Override
    public IFormElement getChild(int i) {
        if (i < this.children.size())
            return this.children.elementAt(i);

        throw new ArrayIndexOutOfBoundsException(
                "FormDef: invalid child index: " + i + " only "
                        + children.size() + " children");
    }

    public IFormElement getChild(FormIndex index) {
        IFormElement element = this;
        while (index != null && index.isInForm()) {
            element = element.getChild(index.getLocalIndex());
            index = index.getNextLevel();
        }
        return element;
    }

    /**
     * Dereference the form index and return a Vector of all interstitial nodes
     * (top-level parent first; index target last)
     *
     * Ignore 'new-repeat' node for now; just return/stop at ref to
     * yet-to-be-created repeat node (similar to repeats that already exist)
     */
    public Vector explodeIndex(FormIndex index) {
        Vector<Integer> indexes = new Vector<>();
        Vector<Integer> multiplicities = new Vector<>();
        Vector<IFormElement> elements = new Vector<>();

        collapseIndex(index, indexes, multiplicities, elements);
        return elements;
    }

    // take a reference, find the instance node it refers to (factoring in
    // multiplicities)

    public TreeReference getChildInstanceRef(FormIndex index) {
        Vector<Integer> indexes = new Vector<>();
        Vector<Integer> multiplicities = new Vector<>();
        Vector<IFormElement> elements = new Vector<>();

        collapseIndex(index, indexes, multiplicities, elements);
        return getChildInstanceRef(elements, multiplicities);
    }

    /**
     * Return a tree reference which follows the path down the concrete elements provided
     * along with the multiplicities provided.
     */
    public TreeReference getChildInstanceRef(Vector<IFormElement> elements,
                                             Vector<Integer> multiplicities) {
        if (elements.size() == 0) {
            return null;
        }

        // get reference for target element
        TreeReference ref = FormInstance.unpackReference(elements.lastElement().getBind()).clone();
        for (int i = 0; i < ref.size(); i++) {
            //There has to be a better way to encapsulate this
            if (ref.getMultiplicity(i) != TreeReference.INDEX_ATTRIBUTE) {
                ref.setMultiplicity(i, 0);
            }
        }

        // fill in multiplicities for repeats along the way
        for (int i = 0; i < elements.size(); i++) {
            IFormElement temp = elements.elementAt(i);
            if (temp instanceof GroupDef && ((GroupDef)temp).isRepeat()) {
                TreeReference repRef = FormInstance.unpackReference(temp.getBind());
                if (repRef.isParentOf(ref, false)) {
                    int repMult = multiplicities.elementAt(i);
                    ref.setMultiplicity(repRef.size() - 1, repMult);
                } else {
                    // question/repeat hierarchy is not consistent with
                    // instance instance and bindings
                    return null;
                }
            }
        }

        return ref;
    }

    public void setLocalizer(Localizer l) {
        this.localizer = l;
    }

    // don't think this should ever be called(!)
    @Override
    public XPathReference getBind() {
        throw new RuntimeException("method not implemented");
    }

    public void setValue(IAnswerData data, TreeReference ref) {
        setValue(data, ref, mainInstance.resolveReference(ref));
    }

    public void setValue(IAnswerData data, TreeReference ref, TreeElement node) {
        setAnswer(data, node);
        triggerTriggerables(ref);
        //TODO: pre-populate fix-count repeats here?
    }

    public void setAnswer(IAnswerData data, TreeReference ref) {
        setAnswer(data, mainInstance.resolveReference(ref));
    }

    public void setAnswer(IAnswerData data, TreeElement node) {
        node.setAnswer(data);
    }

    /**
     * Deletes the inner-most repeat that this node belongs to and returns the
     * corresponding FormIndex. Behavior is currently undefined if you call this
     * method on a node that is not contained within a repeat.
     */
    public FormIndex deleteRepeat(FormIndex index) {
        Vector<Integer> indexes = new Vector<>();
        Vector<Integer> multiplicities = new Vector<>();
        Vector<IFormElement> elements = new Vector<>();
        collapseIndex(index, indexes, multiplicities, elements);

        // loop backwards through the elements, removing objects from each
        // vector, until we find a repeat
        // TODO: should probably check to make sure size > 0
        for (int i = elements.size() - 1; i >= 0; i--) {
            IFormElement e = elements.elementAt(i);
            if (e instanceof GroupDef && ((GroupDef)e).isRepeat()) {
                break;
            } else {
                indexes.removeElementAt(i);
                multiplicities.removeElementAt(i);
                elements.removeElementAt(i);
            }
        }

        // build new formIndex which includes everything
        // up to the node we're going to remove
        FormIndex newIndex = buildIndex(indexes, multiplicities, elements);

        TreeReference deleteRef = getChildInstanceRef(newIndex);
        TreeElement deleteElement = mainInstance.resolveReference(deleteRef);
        TreeReference parentRef = deleteRef.getParentRef();
        TreeElement parentElement = mainInstance.resolveReference(parentRef);

        parentElement.removeChild(deleteElement);

        reduceTreeSiblingMultiplicities(parentElement, deleteElement);

        this.getMainInstance().cleanCache();

        triggerTriggerables(deleteRef);
        return newIndex;
    }

    /**
     * When a repeat is deleted, we need to reduce the multiplicities of its siblings that were higher than it
     * by one.
     *
     * @param parentElement the parent of the deleted element
     * @param deleteElement the deleted element
     */
    private void reduceTreeSiblingMultiplicities(TreeElement parentElement, TreeElement deleteElement) {
        int childMult = deleteElement.getMult();
        // update multiplicities of other child nodes
        for (int i = 0; i < parentElement.getNumChildren(); i++) {
            TreeElement child = parentElement.getChildAt(i);
            // We also need to check that this element matches the deleted element (besides multiplicity)
            // in the case where the deleted repeat's parent isn't a subgroup
            if (child.doFieldsMatch(deleteElement) && child.getMult() > childMult) {
                child.setMult(child.getMult() - 1);
            }
        }
    }

    public void createNewRepeat(FormIndex index) throws InvalidReferenceException {
        TreeReference repeatContextRef = getChildInstanceRef(index);
        TreeElement template = mainInstance.getTemplate(repeatContextRef);

        mainInstance.copyNode(template, repeatContextRef);

        // Fire jr-insert events before "calculate"s
        triggeredDuringInsert.removeAllElements();
        actionController.triggerActionsFromEvent(Action.EVENT_JR_INSERT, this, repeatContextRef, this);

        // trigger conditions that depend on the creation of this new node
        triggerTriggerables(repeatContextRef);

        // trigger conditions for the node (and sub-nodes)
        initTriggerablesRootedBy(repeatContextRef, triggeredDuringInsert);
    }

    @Override
    public void processResultOfAction(TreeReference refSetByAction, String event) {
        if (Action.EVENT_JR_INSERT.equals(event)) {
            Vector<Triggerable> triggerables =
                    triggerIndex.get(refSetByAction.genericize());
            if (triggerables != null) {
                for (Triggerable elem : triggerables) {
                    triggeredDuringInsert.addElement(elem);
                }
            }
        }
    }

    public boolean isRepeatRelevant(TreeReference repeatRef) {
        boolean relev = true;

        Condition c = conditionRepeatTargetIndex.get(repeatRef.genericize());
        if (c != null) {
            relev = c.evalBool(mainInstance, new EvaluationContext(exprEvalContext, repeatRef));
        }

        //check the relevancy of the immediate parent
        if (relev) {
            TreeElement templNode = mainInstance.getTemplate(repeatRef);
            TreeReference parentPath = templNode.getParent().getRef().genericize();
            TreeElement parentNode = mainInstance.resolveReference(parentPath.contextualize(repeatRef));
            relev = parentNode.isRelevant();
        }

        return relev;
    }

    /**
     * Does the repeat group at the given index enable users to add more items,
     * and if so, has the user reached the item limit?
     *
     * @param repeatRef   Reference pointing to a particular repeat item
     * @param repeatIndex Id for looking up the repeat group
     * @return Do the current constraints on the repeat group allow for adding
     * more children?
     */
    public boolean canCreateRepeat(TreeReference repeatRef, FormIndex repeatIndex) {
        GroupDef repeat = (GroupDef)this.getChild(repeatIndex);

        //Check to see if this repeat can have children added by the user
        if (repeat.noAddRemove) {
            //Check to see if there's a count to use to determine how many children this repeat
            //should have
            if (repeat.getCountReference() != null) {
                int currentMultiplicity = repeatIndex.getElementMultiplicity();

                TreeReference absPathToCount = repeat.getConextualizedCountReference(repeatRef);
                AbstractTreeElement countNode = this.getMainInstance().resolveReference(absPathToCount);
                if (countNode == null) {
                    throw new XPathTypeMismatchException("Could not find the location " +
                            absPathToCount.toString() + " where the repeat at " +
                            repeatRef.toString(false) + " is looking for its count");
                }
                //get the total multiplicity possible
                IAnswerData boxedCount = countNode.getValue();
                int count;
                if (boxedCount == null) {
                    count = 0;
                } else {
                    try {
                        count = ((Integer)new IntegerData().cast(boxedCount.uncast()).getValue());
                    } catch (IllegalArgumentException iae) {
                        throw new XPathTypeMismatchException("The repeat count value \"" +
                                boxedCount.uncast().getString() +
                                "\" at " + absPathToCount.toString() +
                                " must be a number!");
                    }
                }

                if (count <= currentMultiplicity) {
                    return false;
                }
            } else {
                //Otherwise the user can never add repeat instances
                return false;
            }
        }

        //TODO: If we think the node is still relevant, we also need to figure out a way to test that assumption against
        //the repeat's constraints.


        return true;
    }

    public void copyItemsetAnswer(QuestionDef q, TreeElement targetNode, IAnswerData data) throws InvalidReferenceException {
        ItemsetBinding itemset = q.getDynamicChoices();
        TreeReference targetRef = targetNode.getRef();
        TreeReference destRef = itemset.getDestRef().contextualize(targetRef);

        Vector<Selection> selections = null;
        Vector<String> selectedValues = new Vector<>();
        if (data instanceof SelectMultiData) {
            selections = (Vector<Selection>)data.getValue();
        } else if (data instanceof SelectOneData) {
            selections = new Vector<>();
            selections.addElement((Selection)data.getValue());
        }
        if (itemset.valueRef != null) {
            for (int i = 0; i < selections.size(); i++) {
                selectedValues.addElement(selections.elementAt(i).choice.getValue());
            }
        }

        //delete existing dest nodes that are not in the answer selection
        Hashtable<String, TreeElement> existingValues = new Hashtable<>();
        Vector<TreeReference> existingNodes = exprEvalContext.expandReference(destRef);
        for (int i = 0; i < existingNodes.size(); i++) {
            TreeElement node = getMainInstance().resolveReference(existingNodes.elementAt(i));

            if (itemset.valueRef != null) {
                String value = itemset.getRelativeValue().evalReadable(this.getMainInstance(), new EvaluationContext(exprEvalContext, node.getRef()));
                if (selectedValues.contains(value)) {
                    existingValues.put(value, node); //cache node if in selection and already exists
                }
            }

            //delete from target
            targetNode.removeChild(node);
        }

        //copy in nodes for new answer; preserve ordering in answer
        for (int i = 0; i < selections.size(); i++) {
            Selection s = selections.elementAt(i);
            SelectChoice ch = s.choice;

            TreeElement cachedNode = null;
            if (itemset.valueRef != null) {
                String value = ch.getValue();
                if (existingValues.containsKey(value)) {
                    cachedNode = existingValues.get(value);
                }
            }

            if (cachedNode != null) {
                cachedNode.setMult(i);
                targetNode.addChild(cachedNode);
            } else {
                getMainInstance().copyItemsetNode(ch.copyNode, destRef, this);
            }
        }

        // trigger conditions that depend on the creation of these new nodes
        triggerTriggerables(destRef);

        // initialize conditions for the node (and sub-nodes)
        // NOTE PLM: the following trigger initialization doesn't cascade to
        // children because it is behaving like trigger initalization for new
        // repeat entries.  If we begin actually using this method, the trigger
        // cascading logic should be fixed.
        initTriggerablesRootedBy(destRef, new Vector<Triggerable>());
        // not 100% sure this will work since destRef is ambiguous as the last
        // step, but i think it's supposed to work
    }

    /**
     * Add a Condition to the form's Collection.
     */
    public Triggerable addTriggerable(Triggerable t) {
        int existingIx = triggerables.indexOf(t);
        if (existingIx != -1) {
            // One node may control access to many nodes; this means many nodes
            // effectively have the same condition. Let's identify when
            // conditions are the same, and store and calculate it only once.

            // nov-2-2011: ctsims - We need to merge the context nodes together
            // whenever we do this (finding the highest common ground between
            // the two), otherwise we can end up failing to trigger when the
            // ignored context exists and the used one doesn't

            Triggerable existingTriggerable = triggerables.get(existingIx);

            existingTriggerable.contextRef = existingTriggerable.contextRef.intersect(t.contextRef);

            return existingTriggerable;

            // NOTE: if the contextRef is unnecessarily deep, the condition
            // will be evaluated more times than needed. Perhaps detect when
            // 'identical' condition has a shorter contextRef, and use that one
            // instead?
        } else {
            // The triggerable isn't being added in any order, so topological
            // sorting has been disrupted
            triggerables.add(t);

            for (TreeReference trigger : t.getTriggers()) {
                TreeReference predicatelessTrigger = t.widenContextToAndClearPredicates(trigger);
                if (!triggerIndex.containsKey(predicatelessTrigger)) {
                    triggerIndex.put(predicatelessTrigger.clone(), new Vector<Triggerable>());
                }
                Vector<Triggerable> triggered = triggerIndex.get(predicatelessTrigger);
                if (!triggered.contains(t)) {
                    triggered.addElement(t);
                }
            }

            return t;
        }
    }

    /**
     * Dependency-sorted enumerator for the triggerables present in the form.
     *
     * @return Enumerator of triggerables such that when an element X precedes
     * Y then X doesn't have any references that are dependent on Y.
     */
    public Iterator<Triggerable> getTriggerables() {
        return triggerables.iterator();
    }

    /**
     * @return All references in the form that are depended on by
     * calculate/relevancy conditions.
     */
    public Iterator<TreeReference> refWithTriggerDependencies() {
        return triggerIndex.keySet().iterator();
    }

    /**
     * Get the triggerable conditions, like relevancy/calculate, that depend on
     * the given reference.
     *
     * @param ref An absolute reference that is used in relevancy/calculate
     *            expressions.
     * @return All the triggerables that depend on the given reference.
     */
    public Vector conditionsTriggeredByRef(TreeReference ref) {
        return triggerIndex.get(ref);
    }

    /**
     * Finalize the DAG associated with the form's triggered conditions. This will create
     * the appropriate ordering and dependencies to ensure the conditions will be evaluated
     * in the appropriate orders.
     *
     * @throws IllegalStateException If the trigger ordering contains an illegal cycle and the
     *                               triggers can't be laid out appropriately
     */
    public void finalizeTriggerables() throws IllegalStateException {
        ArrayList<Pair<Triggerable, Triggerable>> partialOrdering = new ArrayList<>();
        buildPartialOrdering(partialOrdering);

        ArrayList<Triggerable> vertices = new ArrayList<>(triggerables);
        triggerables.clear();

        while (!vertices.isEmpty()) {
            List<Triggerable> roots = buildRootNodes(vertices, partialOrdering);

            if (roots.isEmpty()) {
                // if no root nodes while graph still has nodes, graph has cycles
                throwGraphCyclesException(vertices);
            }

            setOrderOfTriggerable(roots, vertices, partialOrdering);
        }

        // At this point triggerables should be topologically sorted (according
        // to Drew)

        buildConditionRepeatTargetIndex();
    }

    private void buildPartialOrdering(List<Pair<Triggerable, Triggerable>> partialOrdering) {
        for (Triggerable t : triggerables) {
            ArrayList<Triggerable> deps = new ArrayList<>();
            fillTriggeredElements(t, deps, false);

            for (Triggerable u : deps) {
                partialOrdering.add(new Pair<>(t, u));
            }
        }
    }

    private static List<Triggerable> buildRootNodes(List<Triggerable> vertices,
                                                    List<Pair<Triggerable, Triggerable>> partialOrdering) {
        ArrayList<Triggerable> roots = new ArrayList<>(vertices);

        for (Pair<Triggerable, Triggerable> edge : partialOrdering) {
            edge.second.updateStopContextualizingAtFromDominator(edge.first);
            roots.remove(edge.second);
        }
        return roots;
    }

    private ArrayList<TreeReference> getTreeReferenceAndChildren(TreeReference reference) {
        ArrayList<TreeReference> updatedNodes = new ArrayList<>();
        updatedNodes.add(reference);
        updatedNodes = findCascadeReferences(reference, updatedNodes);
        return updatedNodes;
    }

    private void throwGraphCyclesException(List<Triggerable> vertices) {
        Vector edges = new Vector<TreeReference[]>();
        for (Triggerable outerTriggerables : vertices) {
            for (TreeReference outerReference : outerTriggerables.getTargets()) {
                // Get child refs because children are affected by parents
                ArrayList<TreeReference> updatedNodes = getTreeReferenceAndChildren(outerReference);
                if (updatedNodes != null) {
                    for (TreeReference innerReference : updatedNodes) {
                        Vector<Triggerable> triggered = (Vector<Triggerable>)conditionsTriggeredByRef(innerReference);
                        if (triggered != null) {
                            for (Triggerable trig : triggered) {
                                if (!innerReference.equals(outerReference)) {
                                    // We are dealing with a child and parent, so add an edge between the parent
                                    // and child for clarity in the error message.
                                    edges.add(new TreeReference[]{outerReference, innerReference});
                                }
                                // Add all the targets of the of the triggered
                                for (TreeReference reference : trig.getTargets()) {
                                    edges.add(new TreeReference[]{innerReference, reference});
                                }
                            }
                        }
                    }
                }
            }
        }
        throw new IllegalStateException(new ShortestCycleAlgorithm(edges).getCycleErrorMessage());
    }

    private void setOrderOfTriggerable(List<Triggerable> roots,
                                       List<Triggerable> vertices,
                                       List<Pair<Triggerable, Triggerable>> partialOrdering) {
        for (Triggerable root : roots) {
            triggerables.add(root);
            vertices.remove(root);
        }
        for (int i = partialOrdering.size() - 1; i >= 0; i--) {
            Pair<Triggerable, Triggerable> edge = partialOrdering.get(i);
            if (roots.contains(edge.first)) {
                partialOrdering.remove(i);
            }
        }
    }

    private void buildConditionRepeatTargetIndex() {
        conditionRepeatTargetIndex = new HashMap<>();
        for (Triggerable t : triggerables) {
            if (t instanceof Condition) {
                for (TreeReference target : t.getTargets()) {
                    if (mainInstance.getTemplate(target) != null) {
                        conditionRepeatTargetIndex.put(target, (Condition)t);
                    }
                }
            }
        }
    }

    /**
     * Get all of the elements which will need to be evaluated (in order) when
     * the triggerable is fired.
     *
     * @param destination       (mutated) Will have triggerables added to it.
     * @param isRepeatEntryInit Don't cascade triggers to children when
     *                          initializing a new repeat entry.  Repeat entry
     *                          children have already been queued to be
     *                          triggered.
     */
    private void fillTriggeredElements(Triggerable t,
                                       List<Triggerable> destination,
                                       boolean isRepeatEntryInit) {
        if (t.canCascade()) {
            for (TreeReference target : t.getTargets()) {
                ArrayList<TreeReference> updatedNodes = new ArrayList<>();
                updatedNodes.add(target);
                // Repeat sub-elements have already been added to 'destination'
                // when we grabbed all triggerables that target children of the
                // repeat entry (via initTriggerablesRootedBy). Hence skip them
                if (!isRepeatEntryInit && t.isCascadingToChildren()) {
                    updatedNodes = findCascadeReferences(target, updatedNodes);
                }

                addTriggerablesTargetingNodes(updatedNodes, destination);
            }
        }
    }

    /**
     * Gather list of generic references to children of a target reference for
     * a triggerable that cascades to its children. This is needed when, for
     * example, changing the relevancy of the target will require the triggers
     * pointing to children be recalcualted.
     *
     * @param target       Gather children of this by using a template or
     *                     manually traversing the tree
     * @param updatedNodes (potentially mutated) Gets generic child references
     *                     added to it.
     * @return Potentially cached version of updatedNodes argument that
     * contains the target and generic references to the children it might
     * cascade to.
     */
    private ArrayList<TreeReference> findCascadeReferences(TreeReference target,
                                                           ArrayList<TreeReference> updatedNodes) {
        ArrayList<TreeReference> cachedNodes = cachedCascadingChildren.retrieve(target);
        if (cachedNodes == null) {
            if (target.getMultLast() == TreeReference.INDEX_ATTRIBUTE) {
                // attributes don't have children that might change under
                // contextualization
                cachedCascadingChildren.register(target, updatedNodes);
            } else {
                Vector<TreeReference> expandedRefs = exprEvalContext.expandReference(target, true);
                if (expandedRefs.size() > 0) {
                    AbstractTreeElement template = mainInstance.getTemplatePath(target);
                    if (template != null) {
                        addChildrenOfElement(template, updatedNodes);
                        cachedCascadingChildren.register(target, updatedNodes);
                    } else {
                        // NOTE PLM: entirely possible this can be removed if
                        // the getTemplatePath code is updated to handle
                        // heterogeneous paths.  Set a breakpoint here and run
                        // the test suite to see an example
                        // NOTE PLM: Though I'm pretty sure we could cache
                        // this, I'm going to avoid doing so because I'm unsure
                        // whether it is possible for children that are
                        // cascaded to will change when expandedRefs changes
                        // due to new data being added.
                        addChildrenOfReference(expandedRefs, updatedNodes);
                    }
                }
            }
        } else {
            updatedNodes = cachedNodes;
        }
        return updatedNodes;
    }

    /**
     * Resolve the expanded references and gather their generic children and
     * attributes into the genericRefs list.
     */
    private void addChildrenOfReference(List<TreeReference> expandedRefs,
                                        List<TreeReference> genericRefs) {
        for (TreeReference ref : expandedRefs) {
            addChildrenOfElement(exprEvalContext.resolveReference(ref), genericRefs);
        }
    }

    /**
     * Gathers generic children and attribute references for the provided
     * element into the genericRefs list.
     */
    private static void addChildrenOfElement(AbstractTreeElement treeElem,
                                             List<TreeReference> genericRefs) {
        // recursively add children of element
        for (int i = 0; i < treeElem.getNumChildren(); ++i) {
            AbstractTreeElement child = treeElem.getChildAt(i);
            TreeReference genericChild = child.getRef().genericize();
            if (!genericRefs.contains(genericChild)) {
                genericRefs.add(genericChild);
            }
            addChildrenOfElement(child, genericRefs);
        }

        // add all the attributes of this element
        for (int i = 0; i < treeElem.getAttributeCount(); ++i) {
            AbstractTreeElement child =
                    treeElem.getAttribute(treeElem.getAttributeNamespace(i),
                            treeElem.getAttributeName(i));
            TreeReference genericChild = child.getRef().genericize();
            if (!genericRefs.contains(genericChild)) {
                genericRefs.add(genericChild);
            }
        }
    }

    private void addTriggerablesTargetingNodes(List<TreeReference> updatedNodes,
                                               List<Triggerable> destination) {
        //Now go through each of these updated nodes (generally just 1 for a normal calculation,
        //multiple nodes if there's a relevance cascade.
        for (TreeReference ref : updatedNodes) {
            //Check our index to see if that target is a Trigger for other conditions
            //IE: if they are an element of a different calculation or relevancy calc

            //We can't make this reference generic before now or we'll lose the target information,
            //so we'll be more inclusive than needed and see if any of our triggers are keyed on
            //the predicate-less path of this ref
            TreeReference predicatelessRef = ref;
            if (ref.hasPredicates()) {
                predicatelessRef = ref.removePredicates();
            }
            Vector<Triggerable> triggered = triggerIndex.get(predicatelessRef);

            if (triggered != null) {
                //If so, walk all of these triggerables that we found
                for (Triggerable triggerable : triggered) {
                    //And add them to the queue if they aren't there already
                    if (!destination.contains(triggerable)) {
                        destination.add(triggerable);
                    }
                }
            }
        }
    }

    /**
     * Enables debug traces in this form, which can be requested as a map after
     * this call has been performed. Debug traces will be available until they
     * are explicitly disabled.
     *
     * This call also re-executes all triggerables in debug mode to make their
     * current traces available.
     *
     * Must be called after this form is initialized.
     */
    public void enableDebugTraces() {
        if (!mDebugModeEnabled) {
            for (Triggerable t : triggerables) {
                t.setDebug(true);
            }

            // Re-execute all triggerables to collect traces
            initAllTriggerables();
            mDebugModeEnabled = true;
        }
    }

    /**
     * Disable debug tracing for this form. Debug traces will no longer be
     * available after this call.
     */
    public void disableDebugTraces() {
        if (mDebugModeEnabled) {
            for (Triggerable t : triggerables) {
                t.setDebug(false);
            }
            mDebugModeEnabled = false;
        }
    }

    /**
     * Aggregates a map of evaluation traces collected by the form's
     * triggerables.
     *
     * @return A mapping from TreeReferences to a set of evaluation traces. The
     * traces are separated out by triggerable category (calculate, relevant,
     * etc) and represent the execution of the last expression that was
     * executed for that trigger.
     * @throws IllegalStateException If debugging has not been enabled.
     */
    public Hashtable<TreeReference, Hashtable<String, EvaluationTrace>> getDebugTraceMap()
            throws IllegalStateException {

        if (!mDebugModeEnabled) {
            throw new IllegalStateException("Debugging is not enabled");
        }

        // TODO: sure would be nice to be able to cache this at some point, but
        // will have to have a way to invalidate by trigger or something
        Hashtable<TreeReference, Hashtable<String, EvaluationTrace>> debugInfo =
                new Hashtable<>();

        for (Triggerable t : triggerables) {
            Hashtable<TreeReference, EvaluationTrace> triggerOutputs = t.getEvaluationTraces();

            for (Enumeration e = triggerOutputs.keys(); e.hasMoreElements(); ) {
                TreeReference elementRef = (TreeReference)e.nextElement();
                String label = t.getDebugLabel();
                Hashtable<String, EvaluationTrace> traces = debugInfo.get(elementRef);
                if (traces == null) {
                    traces = new Hashtable<>();
                }
                traces.put(label, triggerOutputs.get(elementRef));
                debugInfo.put(elementRef, traces);
            }
        }

        return debugInfo;
    }

    @Trace
    private void initAllTriggerables() {
        // Use all triggerables because we can assume they are rooted by rootRef
        TreeReference rootRef = TreeReference.rootRef();

        Vector<Triggerable> applicable = new Vector<>();
        for (Triggerable triggerable : triggerables) {
            applicable.addElement(triggerable);
        }

        evaluateTriggerables(applicable, rootRef, false);
    }

    /**
     * Evaluate triggerables targeting references that are children of the
     * provided newly created (repeat instance) ref.  Ignore all triggerables
     * that were already fired by processing the jr-insert action. Ignored
     * triggerables can still be fired if a dependency is modified.
     *
     * @param triggeredDuringInsert Triggerables that don't need to be fired
     *                              because they have already been fired while processing insert events
     */
    @Trace
    private void initTriggerablesRootedBy(TreeReference rootRef,
                                          Vector<Triggerable> triggeredDuringInsert) {
        TreeReference genericRoot = rootRef.genericize();

        Vector<Triggerable> applicable = new Vector<>();
        for (Triggerable triggerable : triggerables) {
            for (TreeReference target : triggerable.getTargets()) {
                if (genericRoot.isParentOf(target, false)) {
                    if (!triggeredDuringInsert.contains(triggerable)) {
                        applicable.addElement(triggerable);
                        break;
                    }
                }
            }
        }

        evaluateTriggerables(applicable, rootRef, true);
    }

    /**
     * The entry point for the DAG cascade after a value is changed in the model.
     *
     * @param ref The full contextualized unambiguous reference of the value that was
     *            changed.
     */
    @Trace
    public void triggerTriggerables(TreeReference ref) {
        // turn unambiguous ref into a generic ref to identify what nodes
        // should be triggered by this reference changing
        TreeReference genericRef = ref.genericize();

        // get triggerables which are activated by the generic reference
        Vector<Triggerable> triggered = triggerIndex.get(genericRef);
        if (triggered != null) {
            Vector<Triggerable> triggeredCopy = new Vector<>(triggered);

            evaluateTriggerables(triggeredCopy, ref, false);
        }
    }

    /**
     * Step 2 in evaluating DAG computation updates from a value being changed
     * in the instance. This step is responsible for taking the root set of
     * directly triggered conditions, identifying which conditions should
     * further be triggered due to their update, and then dispatching all of
     * the evaluations.
     *
     * @param tv                A vector of all of the trigerrables directly
     *                          triggered by the value changed. Will be mutated
     *                          by this method.
     * @param anchorRef         The reference to original value that was updated
     * @param isRepeatEntryInit Don't cascade triggers to children when
     *                          initializing a new repeat entry.  Repeat entry
     *                          children have already been queued to be
     *                          triggered.
     */
    @Trace
    private void evaluateTriggerables(List<Triggerable> tv,
                                      TreeReference anchorRef,
                                      boolean isRepeatEntryInit) {
        // Update the list of triggerables that need to be evaluated.
        for (int i = 0; i < tv.size(); i++) {
            // NOTE PLM: tv may grow in size through iteration.
            Triggerable t = tv.get(i);
            fillTriggeredElements(t, tv, isRepeatEntryInit);
        }

        // tv should now contain all of the triggerable components which are
        // going to need to be addressed by this update.
        // 'triggerables' is topologically-ordered by dependencies, so evaluate
        // the triggerables in 'tv' in the order they appear in 'triggerables'
        for (Triggerable triggerable : triggerables) {
            if (tv.contains(triggerable)) {
                evaluateTriggerable(triggerable, anchorRef);
            }
        }
    }

    /**
     * Step 3 in DAG cascade. evaluate the individual triggerable expressions
     * against the anchor (the value that changed which triggered
     * recomputation)
     *
     * @param triggerable The triggerable to be updated
     * @param anchorRef   The reference to the value which was changed.
     */
    @Trace
    private void evaluateTriggerable(Triggerable triggerable, TreeReference anchorRef) {
        // Contextualize the reference used by the triggerable against the anchor
        TreeReference contextRef = triggerable.narrowContextBy(anchorRef);
        final Span span = GlobalTracer.get().activeSpan();
        span.setTag("triggerable", triggerable.toString());

        // Now identify all of the fully qualified nodes which this triggerable
        // updates. (Multiple nodes can be updated by the same trigger)
        Vector<TreeReference> expandedReferences = exprEvalContext.expandReference(contextRef);

        for (TreeReference treeReference : expandedReferences) {
            triggerable.apply(mainInstance, exprEvalContext, treeReference, this);
        }
    }

    public boolean evaluateConstraint(TreeReference ref, IAnswerData data) {
        if (data instanceof InvalidData) {
            return false;
        }

        if (data == null) {
            return true;
        }

        TreeElement node = mainInstance.resolveReference(ref);
        Constraint c = node.getConstraint();

        if (c == null) {
            return true;
        }
        EvaluationContext ec = new EvaluationContext(exprEvalContext, ref);
        ec.isConstraint = true;
        ec.candidateValue = data;

        return c.constraint.eval(mainInstance, ec);
    }

    public void setEvaluationContext(EvaluationContext ec) {
        ec = new EvaluationContext(mainInstance, formInstances, ec);
        initEvalContext(ec);
        if (useExpressionCaching) {
            ec.enableExpressionCaching();
        }
        this.exprEvalContext = ec;
    }

    public EvaluationContext getEvaluationContext() {
        return this.exprEvalContext;
    }

    private void initEvalContext(EvaluationContext ec) {
        if (!ec.getFunctionHandlers().containsKey("jr:itext")) {
            final FormDef f = this;
            ec.addFunctionHandler(new IFunctionHandler() {
                @Override
                public String getName() {
                    return "jr:itext";
                }

                @Override
                public Object eval(Object[] args, EvaluationContext ec) {
                    String textID = (String)args[0];
                    try {
                        //SUUUUPER HACKY
                        String form = ec.getOutputTextForm();
                        if (form != null) {
                            textID = textID + ";" + form;
                            String result = f.getLocalizer().getRawText(f.getLocalizer().getLocale(), textID);
                            return result == null ? "" : result;
                        } else {
                            String text = f.getLocalizer().getText(textID);
                            return text == null ? "[itext:" + textID + "]" : text;
                        }
                    } catch (NoSuchElementException nsee) {
                        return "[nolocale]";
                    }
                }

                @Override
                public Vector getPrototypes() {
                    Class[] proto = {String.class};
                    Vector<Class[]> v = new Vector<>();
                    v.addElement(proto);
                    return v;
                }

                @Override
                public boolean rawArgs() {
                    return false;
                }
            });
        }

        /* function to reverse a select value into the display label for that choice in the question it came from
         *
         * arg 1: select value
         * arg 2: string xpath referring to origin question; must be absolute path
         *
         * this won't work at all if the original label needed to be processed/calculated in some way (<output>s, etc.) (is this even allowed?)
         * likely won't work with multi-media labels
         * _might_ work for itemsets, but probably not very well or at all; could potentially work better if we had some context info
         * DOES work with localization
         *
         * it's mainly intended for the simple case of reversing a question with compile-time-static fields, for use inside an <output>
         */
        if (!ec.getFunctionHandlers().containsKey("jr:choice-name")) {
            final FormDef f = this;
            ec.addFunctionHandler(new IFunctionHandler() {
                @Override
                public String getName() {
                    return "jr:choice-name";
                }

                @Override
                public Object eval(Object[] args, EvaluationContext ec) {
                    try {
                        String value = (String)args[0];
                        String questionXpath = (String)args[1];
                        TreeReference ref = RestoreUtils.ref(questionXpath);

                        QuestionDef q = FormDef.findQuestionByRef(ref, f);
                        if (q == null || (q.getControlType() != Constants.CONTROL_SELECT_ONE &&
                                q.getControlType() != Constants.CONTROL_SELECT_MULTI)) {
                            return "";
                        }

                        System.out.println("here!!");

                        Vector<SelectChoice> choices = q.getChoices();
                        for (SelectChoice ch : choices) {
                            if (ch.getValue().equals(value)) {
                                //this is really not ideal. we should hook into the existing code (FormEntryPrompt) for pulling
                                //display text for select choices. however, it's hard, because we don't really have
                                //any context to work with, and all the situations where that context would be used
                                //don't make sense for trying to reverse a select value back to a label in an unrelated
                                //expression

                                String textID = ch.getTextID();
                                if (textID != null) {
                                    return f.getLocalizer().getText(textID);
                                } else {
                                    return ch.getLabelInnerText();
                                }
                            }
                        }
                        return "";
                    } catch (Exception e) {
                        throw new WrappedException("error in evaluation of xpath function [choice-name]", e);
                    }
                }

                @Override
                public Vector getPrototypes() {
                    Class[] proto = {String.class, String.class};
                    Vector<Class[]> v = new Vector<>();
                    v.addElement(proto);
                    return v;
                }

                @Override
                public boolean rawArgs() {
                    return false;
                }
            });
        }
    }

    public void enableExpressionCaching() {
        useExpressionCaching = true;
    }

    public String fillTemplateString(String template, TreeReference contextRef) {
        return fillTemplateString(template, contextRef, new Hashtable());
    }

    /**
     * Performs substitutions on place-holder template from form text by
     * evaluating args in template using the current context.
     *
     * @param template   String
     * @param contextRef TreeReference
     * @param variables  Hashtable<String, ?>
     * @return String with the all args in the template filled with appropriate
     * context values.
     */
    public String fillTemplateString(String template, TreeReference contextRef, Hashtable<String, ?> variables) {
        // argument to value mapping
        Hashtable<String, String> args = new Hashtable<>();

        int depth = 0;
        // grab all template arguments that need to have substitutions performed
        Vector outstandingArgs = Localizer.getArgs(template);

        String templateAfterSubstitution;

        // Step through outstandingArgs from the template, looking up the value
        // they map to, evaluating that under the evaluation context and
        // storing in the local args mapping.
        // Then perform substitutions over the template until a fixpoint is found
        while (outstandingArgs.size() > 0) {
            for (int i = 0; i < outstandingArgs.size(); i++) {
                String argName = (String)outstandingArgs.elementAt(i);
                // lookup value an arg points to if it isn't in our local mapping
                if (!args.containsKey(argName)) {
                    int ix = -1;
                    try {
                        ix = Integer.parseInt(argName);
                    } catch (NumberFormatException nfe) {
                        System.err.println("Warning: expect arguments to be numeric [" + argName + "]");
                    }

                    if (ix < 0 || ix >= outputFragments.size()) {
                        continue;
                    }

                    IConditionExpr expr = (IConditionExpr)outputFragments.elementAt(ix);
                    EvaluationContext ec = new EvaluationContext(exprEvalContext, contextRef);
                    ec.setOriginalContext(contextRef);
                    ec.setVariables(variables);
                    String value = expr.evalReadable(this.getMainInstance(), ec);
                    args.put(argName, value);
                }
            }

            templateAfterSubstitution = Localizer.processArguments(template, args);

            // The last substitution made no progress, probably because the
            // argument isn't in outputFragments, so stop looping and
            // attempting more subs!
            if (template.equals(templateAfterSubstitution)) {
                return template;
            }

            template = templateAfterSubstitution;

            // Since strings being substituted might themselves have arguments that
            // need to be further substituted, we must recompute the unperformed
            // substitutions and continue to loop.
            outstandingArgs = Localizer.getArgs(template);

            if (depth++ >= TEMPLATING_RECURSION_LIMIT) {
                throw new RuntimeException("Dependency cycle in <output>s; recursion limit exceeded!!");
            }
        }

        return template;
    }

    @Trace
    public void populateDynamicChoices(ItemsetBinding itemset, TreeReference curQRef){
        final Span span = GlobalTracer.get().activeSpan();
        span.setTag("itemset", itemset.nodesetRef.toString());
        span.setTag("treeReference", curQRef.toString());
        ItemSetUtils.populateDynamicChoices(itemset, curQRef, exprEvalContext, getMainInstance(), mProfilingEnabled);
    }

    public String toString() {
        return getTitle();
    }

    public void postProcessInstance() {
        if (!isCompletedInstance) {
            actionController.triggerActionsFromEvent(Action.EVENT_XFORMS_REVALIDATE, this);
        }
    }

    /**
     * Reads the form definition object from the supplied stream.
     *
     * Requires that the instance has been set to a prototype of the instance that
     * should be used for deserialization.
     */
    @Override
    public void readExternal(DataInputStream dis, PrototypeFactory pf) throws IOException, DeserializationException {
        setID(ExtUtil.readInt(dis));
        setName(ExtUtil.nullIfEmpty(ExtUtil.readString(dis)));
        setTitle((String)ExtUtil.read(dis, new ExtWrapNullable(String.class), pf));
        setChildren((Vector)ExtUtil.read(dis, new ExtWrapListPoly(), pf));
        setInstance((FormInstance)ExtUtil.read(dis, FormInstance.class, pf));

        setLocalizer((Localizer)ExtUtil.read(dis, new ExtWrapNullable(Localizer.class), pf));

        Vector vcond = (Vector)ExtUtil.read(dis, new ExtWrapList(Condition.class), pf);
        for (Enumeration e = vcond.elements(); e.hasMoreElements(); ) {
            addTriggerable((Condition)e.nextElement());
        }
        Vector vcalc = (Vector)ExtUtil.read(dis, new ExtWrapList(Recalculate.class), pf);
        for (Enumeration e = vcalc.elements(); e.hasMoreElements(); ) {
            addTriggerable((Recalculate)e.nextElement());
        }
        finalizeTriggerables();

        outputFragments = (Vector)ExtUtil.read(dis, new ExtWrapListPoly(), pf);

        submissionProfiles = (Hashtable<String, SubmissionProfile>)ExtUtil.read(dis, new ExtWrapMap(String.class, SubmissionProfile.class), pf);

        formInstances = (Hashtable<String, DataInstance>)ExtUtil.read(dis, new ExtWrapMap(String.class, new ExtWrapTagged()), pf);

        extensions = (Vector)ExtUtil.read(dis, new ExtWrapListPoly(), pf);

        setEvaluationContext(new EvaluationContext(null));
        actionController = (ActionController)ExtUtil.read(dis, ActionController.class, pf);
    }

    /**
     * meant to be called after deserialization and initialization of handlers
     *
     * @param newInstance         true if the form is to be used for a new entry interaction,
     *                            false if it is using an existing IDataModel
     * @param isCompletedInstance true if this is an already completed instance we are editing
     *                            (presumably in HQ) - so don't fire end of form event.
     */
    public void initialize(boolean newInstance, boolean isCompletedInstance,
                           InstanceInitializationFactory factory) {
        initialize(newInstance, isCompletedInstance, factory, null, false);
    }

    public void initialize(boolean newInstance, InstanceInitializationFactory factory) {
        initialize(newInstance, false, factory, null, false);
    }

    public void initialize(boolean newInstance, InstanceInitializationFactory factory, String locale,
                           boolean isReadOnly) {
        initialize(newInstance, false, factory, locale, isReadOnly);
    }

    /**
     * meant to be called after deserialization and initialization of handlers
     *
     * @param newInstance true if the form is to be used for a new entry interaction,
     *                    false if it is using an existing IDataModel
     * @param locale      The default locale in the current environment, if provided. Can be null
     *                    to rely on the form's internal default.
     */
    @Trace
    public void initialize(boolean newInstance, boolean isCompletedInstance,
                           InstanceInitializationFactory factory, String locale, boolean isReadOnly) {
        for (Enumeration en = formInstances.keys(); en.hasMoreElements(); ) {
            String instanceId = (String)en.nextElement();
            DataInstance instance = formInstances.get(instanceId);
            formInstances.put(instanceId, instance.initialize(factory, instanceId));
        }

        initLocale(locale);

        if (newInstance) {
            // only dispatch on a form's first opening, not subsequent loadings
            // of saved instances. Ensures setvalues triggered by xform-ready,
            // useful for recording form start dates.
            actionController.triggerActionsFromEvent(Action.EVENT_XFORMS_READY, this);
        }
        this.isCompletedInstance = isCompletedInstance;
        if (!isReadOnly) {
            initAllTriggerables();
        }
    }

    private void initLocale(String locale) {
        if (localizer != null) {
            if (locale == null || !localizer.hasLocale(locale)) {
                if (localizer.getLocale() == null) {
                    localizer.setToDefault();
                }
            } else {
                localizer.setLocale(locale);
            }
        }
    }

    /**
     * Writes the form definition object to the supplied stream.
     */
    @Override
    public void writeExternal(DataOutputStream dos) throws IOException {
        ExtUtil.writeNumeric(dos, getID());
        ExtUtil.writeString(dos, ExtUtil.emptyIfNull(getName()));
        ExtUtil.write(dos, new ExtWrapNullable(getTitle()));
        ExtUtil.write(dos, new ExtWrapListPoly(getChildren()));
        ExtUtil.write(dos, getMainInstance());
        ExtUtil.write(dos, new ExtWrapNullable(localizer));

        Vector<Condition> conditions = new Vector<>();
        Vector<Recalculate> recalcs = new Vector<>();
        for (Triggerable t : triggerables) {
            if (t instanceof Condition) {
                conditions.addElement((Condition)t);
            } else if (t instanceof Recalculate) {
                recalcs.addElement((Recalculate)t);
            }
        }
        ExtUtil.write(dos, new ExtWrapList(conditions));
        ExtUtil.write(dos, new ExtWrapList(recalcs));

        ExtUtil.write(dos, new ExtWrapListPoly(outputFragments));
        ExtUtil.write(dos, new ExtWrapMap(submissionProfiles));

        //for support of multi-instance forms

        ExtUtil.write(dos, new ExtWrapMap(formInstances, new ExtWrapTagged()));
        ExtUtil.write(dos, new ExtWrapListPoly(extensions));
        ExtUtil.write(dos, actionController);
    }

    public void collapseIndex(FormIndex index,
                              Vector<Integer> indexes,
                              Vector<Integer> multiplicities,
                              Vector<IFormElement> elements) {
        if (!index.isInForm()) {
            return;
        }

        IFormElement element = this;
        while (index != null) {
            int i = index.getLocalIndex();
            element = element.getChild(i);

            indexes.addElement(DataUtil.integer(i));
            multiplicities.addElement(DataUtil.integer(index.getInstanceIndex() == -1 ? 0 : index.getInstanceIndex()));
            elements.addElement(element);

            index = index.getNextLevel();
        }
    }

    public FormIndex buildIndex(Vector<Integer> indexes, Vector<Integer> multiplicities, Vector<IFormElement> elements) {
        FormIndex cur = null;
        Vector<Integer> curMultiplicities = new Vector<>();
        for (int j = 0; j < multiplicities.size(); ++j) {
            curMultiplicities.addElement(multiplicities.elementAt(j));
        }

        Vector<IFormElement> curElements = new Vector<>();
        for (int j = 0; j < elements.size(); ++j) {
            curElements.addElement(elements.elementAt(j));
        }

        for (int i = indexes.size() - 1; i >= 0; i--) {
            int ix = indexes.elementAt(i);
            int mult = multiplicities.elementAt(i);

            if (!(elements.elementAt(i) instanceof GroupDef && ((GroupDef)elements.elementAt(i)).isRepeat())) {
                mult = -1;
            }

            cur = new FormIndex(cur, ix, mult, getChildInstanceRef(curElements, curMultiplicities));
            curMultiplicities.removeElementAt(curMultiplicities.size() - 1);
            curElements.removeElementAt(curElements.size() - 1);
        }
        return cur;
    }

    public int getNumRepetitions(FormIndex index) {
        Vector<Integer> indexes = new Vector<>();
        Vector<Integer> multiplicities = new Vector<>();
        Vector<IFormElement> elements = new Vector<>();

        if (!index.isInForm()) {
            throw new RuntimeException("not an in-form index");
        }

        collapseIndex(index, indexes, multiplicities, elements);

        if (!(elements.lastElement() instanceof GroupDef) || !((GroupDef)elements.lastElement()).isRepeat()) {
            throw new RuntimeException("current element not a repeat");
        }

        //so painful
        TreeElement templNode = mainInstance.getTemplate(index.getReference());
        TreeReference parentPath = templNode.getParent().getRef().genericize();
        TreeElement parentNode = mainInstance.resolveReference(parentPath.contextualize(index.getReference()));
        return parentNode.getChildMultiplicity(templNode.getName());
    }

    //repIndex == -1 => next repetition about to be created
    public FormIndex descendIntoRepeat(FormIndex index, int repIndex) {
        int numRepetitions = getNumRepetitions(index);

        Vector<Integer> indexes = new Vector<>();
        Vector<Integer> multiplicities = new Vector<>();
        Vector<IFormElement> elements = new Vector<>();
        collapseIndex(index, indexes, multiplicities, elements);

        if (repIndex == -1) {
            repIndex = numRepetitions;
        } else {
            if (repIndex < 0 || repIndex >= numRepetitions) {
                throw new RuntimeException("selection exceeds current number of repetitions");
            }
        }

        multiplicities.setElementAt(DataUtil.integer(repIndex), multiplicities.size() - 1);

        return buildIndex(indexes, multiplicities, elements);
    }

    @Override
    public int getDeepChildCount() {
        int total = 0;
        Enumeration e = children.elements();
        while (e.hasMoreElements()) {
            total += ((IFormElement)e.nextElement()).getDeepChildCount();
        }
        return total;
    }

    @Override
    public Vector<IFormElement> getChildren() {
        return children;
    }

    @Override
    public void setChildren(Vector<IFormElement> children) {
        this.children = (children == null ? new Vector<IFormElement>() : children);
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    @Override
    public int getID() {
        return id;
    }

    @Override
    public void setID(int id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Localizer getLocalizer() {
        return localizer;
    }

    public Vector getOutputFragments() {
        return outputFragments;
    }

    public void setOutputFragments(Vector outputFragments) {
        this.outputFragments = outputFragments;
    }

    @Override
    public Object getMetaData(String fieldName) {
        if (fieldName.equals("DESCRIPTOR")) {
            return name;
        }
        if (fieldName.equals("XMLNS")) {
            return ExtUtil.emptyIfNull(mainInstance.schema);
        } else {
            throw new IllegalArgumentException();
        }
    }

    @Override
    public String[] getMetaDataFields() {
        return new String[]{"DESCRIPTOR", "XMLNS"};
    }

    /**
     * Link a deserialized instance back up with its parent FormDef. this allows select/select1 questions to be
     * internationalizable in chatterbox, and (if using CHOICE_INDEX mode) allows the instance to be serialized
     * to xml
     */
    public void attachControlsToInstanceData() {
        attachControlsToInstanceData(getMainInstance().getRoot());
    }

    private void attachControlsToInstanceData(TreeElement node) {
        for (int i = 0; i < node.getNumChildren(); i++) {
            attachControlsToInstanceData(node.getChildAt(i));
        }

        IAnswerData val = node.getValue();
        Vector<Object> selections = null;
        if (val instanceof SelectOneData) {
            selections = new Vector<>();
            selections.addElement(val.getValue());
        } else if (val instanceof SelectMultiData) {
            selections = (Vector<Object>)val.getValue();
        }

        if (selections != null) {
            QuestionDef q = findQuestionByRef(node.getRef(), this);
            if (q == null) {
                throw new RuntimeException("FormDef.attachControlsToInstanceData: can't find question to link");
            }

            if (q.getDynamicChoices() != null) {
                //droos: i think we should do something like initializing the itemset here, so that default answers
                //can be linked to the selectchoices. however, there are complications. for example, the itemset might
                //not be ready to be evaluated at form initialization; it may require certain questions to be answered
                //first. e.g., if we evaluate an itemset and it has no choices, the xform engine will throw an error
                //itemset TODO
            }

            for (int i = 0; i < selections.size(); i++) {
                Selection s = (Selection)selections.elementAt(i);
                s.attachChoice(q);
            }
        }
    }

    public static QuestionDef findQuestionByRef(TreeReference ref, IFormElement fe) {
        if (fe instanceof FormDef) {
            ref = ref.genericize();
        }

        if (fe instanceof QuestionDef) {
            QuestionDef q = (QuestionDef)fe;
            TreeReference bind = FormInstance.unpackReference(q.getBind());
            return (ref.equals(bind) ? q : null);
        } else {
            for (int i = 0; i < fe.getChildren().size(); i++) {
                QuestionDef ret = findQuestionByRef(ref, fe.getChild(i));
                if (ret != null)
                    return ret;
            }
            return null;
        }
    }

    /**
     * Appearance isn't a valid attribute for form, but this method must be included
     * as a result of conforming to the IFormElement interface.
     */
    @Override
    public String getAppearanceAttr() {
        throw new RuntimeException("This method call is not relevant for FormDefs getAppearanceAttr ()");
    }

    /**
     * Appearance isn't a valid attribute for form, but this method must be included
     * as a result of conforming to the IFormElement interface.
     */
    @Override
    public void setAppearanceAttr(String appearanceAttr) {
        throw new RuntimeException("This method call is not relevant for FormDefs setAppearanceAttr()");
    }

    @Override
    public ActionController getActionController() {
        return this.actionController;
    }

    /**
     * Not applicable here.
     */
    @Override
    public String getLabelInnerText() {
        return null;
    }

    /**
     * Not applicable
     */
    @Override
    public String getTextID() {
        return null;
    }

    /**
     * Not applicable
     */
    @Override
    public void setTextID(String textID) {
        throw new RuntimeException("This method call is not relevant for FormDefs [setTextID()]");
    }


    public void setDefaultSubmission(SubmissionProfile profile) {
        submissionProfiles.put(DEFAULT_SUBMISSION_PROFILE, profile);
    }

    public void addSubmissionProfile(String submissionId, SubmissionProfile profile) {
        submissionProfiles.put(submissionId, profile);
    }

    /**
     * @return The submission profile with the given ID.
     */
    public SubmissionProfile getSubmissionProfile(String id) {
        return submissionProfiles.get(id);
    }

    public <X extends XFormExtension> X getExtension(Class<X> extension) {
        for (XFormExtension ex : extensions) {
            if (ex.getClass().isAssignableFrom(extension)) {
                return (X)ex;
            }
        }
        X newEx;
        try {
            newEx = extension.newInstance();
        } catch (InstantiationException e) {
            throw new RuntimeException("Illegally Structured XForm Extension " + extension.getName());
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Illegally Structured XForm Extension " + extension.getName());
        }
        extensions.addElement(newEx);
        return newEx;
    }


    /**
     * Frees all of the components of this form which are no longer needed once it is completed.
     *
     * Once this is called, the form is no longer capable of functioning, but all data should be retained.
     */
    public void seal() {
        triggerables = null;
        triggerIndex = null;
        conditionRepeatTargetIndex = null;
        //We may need ths one, actually
        exprEvalContext = null;
    }

    /**
     * Register a handler which is capable of handling send actions.
     */
    public void setSendCalloutHandler(FormSendCalloutHandler sendCalloutHandler) {
        this.sendCalloutHandler = sendCalloutHandler;
    }

    public String dispatchSendCallout(String url, Map<String, String> paramMap) {
        if (sendCalloutHandler == null) {
            return null;
        } else {
            return sendCalloutHandler.performHttpCalloutForResponse(url, paramMap);
        }
    }
}
