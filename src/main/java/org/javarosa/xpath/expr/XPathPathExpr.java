package org.javarosa.xpath.expr;

import org.javarosa.core.model.condition.EvaluationContext;
import org.javarosa.core.model.condition.pivot.UnpivotableExpressionException;
import org.javarosa.core.model.data.BooleanData;
import org.javarosa.core.model.data.DateData;
import org.javarosa.core.model.data.DateTimeData;
import org.javarosa.core.model.data.DecimalData;
import org.javarosa.core.model.data.GeoPointData;
import org.javarosa.core.model.data.IAnswerData;
import org.javarosa.core.model.data.IntegerData;
import org.javarosa.core.model.data.LongData;
import org.javarosa.core.model.data.SelectMultiData;
import org.javarosa.core.model.data.SelectOneData;
import org.javarosa.core.model.data.StringData;
import org.javarosa.core.model.data.UncastData;
import org.javarosa.core.model.data.helper.Selection;
import org.javarosa.core.model.instance.AbstractTreeElement;
import org.javarosa.core.model.instance.DataInstance;
import org.javarosa.core.model.instance.TreeReference;
import org.javarosa.core.util.externalizable.DeserializationException;
import org.javarosa.core.util.externalizable.ExtUtil;
import org.javarosa.core.util.externalizable.ExtWrapList;
import org.javarosa.core.util.externalizable.PrototypeFactory;
import org.javarosa.xform.util.XFormAnswerDataSerializer;
import org.javarosa.xpath.XPathException;
import org.javarosa.xpath.XPathLazyNodeset;
import org.javarosa.xpath.XPathMissingInstanceException;
import org.javarosa.xpath.XPathNodeset;
import org.javarosa.xpath.XPathTypeMismatchException;
import org.javarosa.xpath.XPathUnsupportedException;
import org.javarosa.xpath.analysis.AnalysisInvalidException;
import org.javarosa.xpath.analysis.XPathAnalyzer;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.text.MessageFormat;
import java.util.Vector;

public class XPathPathExpr extends XPathExpression {
    private boolean templatePathChecked = false;
    public static final int INIT_CONTEXT_ROOT = 0;
    public static final int INIT_CONTEXT_RELATIVE = 1;
    public static final int INIT_CONTEXT_EXPR = 2;

    public int initContext;
    public XPathStep[] steps;
    private TreeReference cachedReference;

    //for INIT_CONTEXT_EXPR only
    public XPathFilterExpr filtExpr;

    public XPathPathExpr() {
    } //for deserialization

    public XPathPathExpr(int initContext, XPathStep[] steps) {
        this.initContext = initContext;
        this.steps = steps;
    }

     public XPathPathExpr(XPathFilterExpr filtExpr, XPathStep[] steps) {
         this(INIT_CONTEXT_EXPR, steps);
         this.filtExpr = filtExpr;
     }

    /**
     * Translate an xpath path reference into a TreeReference
     * TreeReferences only support a subset of xpath paths:
     * - only simple child name tests 'child::name', '.', and '..' allowed.
     * - '../' steps must come before anything else
     *
     * @return a reference built from this path expression
     */
    public TreeReference getReference() throws XPathUnsupportedException {
        if (cachedReference != null) {
            return cachedReference;
        }
        TreeReference ref = new TreeReference();
        boolean parentsAllowed;
        // process the beginning of the reference
        switch (initContext) {
            case XPathPathExpr.INIT_CONTEXT_ROOT:
                ref.setRefLevel(TreeReference.REF_ABSOLUTE);
                parentsAllowed = false;
                break;
            case XPathPathExpr.INIT_CONTEXT_RELATIVE:
                ref.setRefLevel(0);
                ref.setContextType(TreeReference.CONTEXT_INHERITED);
                parentsAllowed = true;
                break;
            case XPathPathExpr.INIT_CONTEXT_EXPR:
                if (filtExpr.x != null && filtExpr.x instanceof XPathFuncExpr) {
                    XPathFuncExpr func = (XPathFuncExpr)(filtExpr.x);
                    if (func.name.equals("instance")) {
                        // i assume when refering the non main instance you have to be absolute
                        parentsAllowed = false;
                        if (func.args.length != 1) {
                            throw new XPathUnsupportedException("instance() function used with " +
                                    func.args.length + " arguements. Expecting 1 arguement");
                        }
                        if (!(func.args[0] instanceof XPathStringLiteral)) {
                            throw new XPathUnsupportedException("instance() function expecting 1 string literal arguement arguement");
                        }
                        XPathStringLiteral strLit = (XPathStringLiteral)(func.args[0]);
                        // we've got a non-standard instance in play, watch out
                        ref = new TreeReference(strLit.s, TreeReference.REF_ABSOLUTE);
                    } else if (func.name.equals("current")) {
                        parentsAllowed = true;
                        ref = TreeReference.baseCurrentRef();
                    } else {
                        // We only support expression root contexts for
                        // instance refs, everything else is an illegal filter
                        throw new XPathUnsupportedException("filter expression");
                    }
                } else {
                    // We only support expression root contexts for instance
                    // refs, everything else is an illegal filter
                    throw new XPathUnsupportedException("filter expression");
                }
                break;
            default:
                throw new XPathUnsupportedException("filter expression");
        }

        final String otherStepMessage = "step other than 'child::name', '.', '..'";
        for (XPathStep step : steps) {
            if (step.axis == XPathStep.AXIS_SELF) {
                if (step.test != XPathStep.TEST_TYPE_NODE) {
                    throw new XPathUnsupportedException(otherStepMessage);
                }
            } else if (step.axis == XPathStep.AXIS_PARENT) {
                if (!parentsAllowed || step.test != XPathStep.TEST_TYPE_NODE) {
                    throw new XPathUnsupportedException(otherStepMessage);
                } else {
                    ref.incrementRefLevel();
                }
            } else if (step.axis == XPathStep.AXIS_ATTRIBUTE) {
                if (step.test == XPathStep.TEST_NAME) {
                    ref.add(step.name.toString(), TreeReference.INDEX_ATTRIBUTE);
                    parentsAllowed = false;
                    //TODO: Can you step back from an attribute, or should this always be
                    //the last step?
                } else {
                    throw new XPathUnsupportedException("attribute step other than 'attribute::name");
                }
            } else if (step.axis == XPathStep.AXIS_CHILD) {
                if (step.test == XPathStep.TEST_NAME) {
                    ref.add(step.name.toString(), TreeReference.INDEX_UNBOUND);
                    parentsAllowed = false;
                } else if (step.test == XPathStep.TEST_NAME_WILDCARD) {
                    ref.add(TreeReference.NAME_WILDCARD, TreeReference.INDEX_UNBOUND);
                    parentsAllowed = false;
                } else {
                    throw new XPathUnsupportedException(otherStepMessage);
                }
            } else {
                throw new XPathUnsupportedException(otherStepMessage);
            }

            if (step.predicates.length > 0) {
                Vector<XPathExpression> v = new Vector<>();
                for (XPathExpression predicate : step.predicates) {
                    v.addElement(predicate);
                }
                // add the predicate vector to the last step in the ref
                ref.addPredicate(ref.size() - 1, v);
            }
        }
        cachedReference = ref;
        return ref;
    }

    @Override
    protected XPathNodeset evalRaw(DataInstance m, EvaluationContext ec) {
        TreeReference genericRef = getReference();
        TreeReference ref;

        if (genericRef.getContextType() == TreeReference.CONTEXT_ORIGINAL) {
            // reference begins with "current()" so contextualize in the original context
            ref = genericRef.contextualize(ec.getOriginalContext());
        } else {
            ref = genericRef.contextualize(ec.getContextRef());
        }

        //We don't necessarily know the model we want to be working with until we've contextualized the
        //node

        //check if this nodeset refers to a non-main instance
        if (ref.getInstanceName() != null && ref.isAbsolute()) {
            DataInstance nonMain = ec.getInstance(ref.getInstanceName());
            if (nonMain != null) {
                m = nonMain;
                if (m.getRoot() == null) {
                    //This instance is _declared_, but doesn't actually have any data in it.
                    throw new XPathMissingInstanceException(ref.getInstanceName(), "Instance referenced by " + ref.toString(true) + " has not been loaded");
                }
            } else {
                Object[] args = new Object[] {ref.getInstanceName(), ref.toString(true), ec.getContextRef()};
                MessageFormat msg = new MessageFormat("The instance \"{0}\" in expression \"{1}\" used by \"{2}\" does not exist in the form. Please correct your form or application.");
                throw new XPathMissingInstanceException(ref.getInstanceName(), msg.format(args));
            }
        } else {
            //TODO: We should really stop passing 'm' around and start just getting the right instance from ec
            //at a more central level
            m = ec.getMainInstance();

            if (m == null) {
                String refStr = ref.toString(true);
                throw new XPathException("Cannot evaluate the reference [" + refStr + "] in the current evaluation context. No default instance has been declared!");
            }
        }
        //Otherwise we'll leave 'm' as set to the main instance

        // Error out if a (template) path along the reference starting at the
        // main DataInstance doesn't exist.
        if (!templatePathChecked && ref.isAbsolute() && !m.hasTemplatePath(ref)) {
            return XPathNodeset.constructInvalidPathNodeset(ref.toString(), genericRef.toString());
        }

        // only check the template path once, since it is expensive
        templatePathChecked = true;

        return new XPathLazyNodeset(ref, m, ec);
    }

    public static Object getRefValue(DataInstance model, EvaluationContext ec, TreeReference ref) {
        if (ec.isConstraint && ref.equals(ec.getContextRef())) {
            //ITEMSET TODO: need to update this; for itemset/copy constraints, need to simulate a whole xml sub-tree here
            return unpackValue(ec.candidateValue);
        } else {
            AbstractTreeElement node = model.resolveReference(ref, ec);
            if (node == null) {
                //shouldn't happen -- only existent nodes should be in nodeset
                throw new XPathTypeMismatchException("Node " + ref.toString() + " does not exist!");
            }

            return unpackValue(node.isRelevant() ? node.getValue() : null);
        }
    }

    public static Object unpackValue(IAnswerData val) {
        if (val == null) {
            return "";
        } else if (val instanceof UncastData) {
            return val.getValue();
        } else if (val instanceof IntegerData) {
            return ((Integer)val.getValue()).doubleValue();
        } else if (val instanceof LongData) {
            return ((Long)val.getValue()).doubleValue();
        } else if (val instanceof DecimalData) {
            return val.getValue();
        } else if (val instanceof StringData) {
            return val.getValue();
        } else if (val instanceof SelectOneData) {
            return ((Selection)val.getValue()).getValue();
        } else if (val instanceof SelectMultiData) {
            return (new XFormAnswerDataSerializer()).serializeAnswerData(val);
        } else if (val instanceof DateData) {
            return val.getValue();
        } else if (val instanceof BooleanData) {
            return val.getValue();
        } else if (val instanceof GeoPointData) {
            return val.uncast().getString();
        } else {
            System.out.println("warning: unrecognized data type in xpath expr: " + val.getClass().getName());

            //TODO: Does this mess up any of our other plans?
            return val.uncast().getString();
        }
    }

    @Override
    public String toString() {
        StringBuffer sb = new StringBuffer();

        sb.append("{path-expr:");
        switch (initContext) {
            case INIT_CONTEXT_ROOT:
                sb.append("abs");
                break;
            case INIT_CONTEXT_RELATIVE:
                sb.append("rel");
                break;
            case INIT_CONTEXT_EXPR:
                sb.append(filtExpr.toString());
                break;
        }
        sb.append(",{");
        for (int i = 0; i < steps.length; i++) {
            sb.append(steps[i].toString());
            if (i < steps.length - 1)
                sb.append(",");
        }
        sb.append("}}");

        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof XPathPathExpr) {
            XPathPathExpr x = (XPathPathExpr)o;

            //Shortcuts for easily comparable values
            if (initContext != x.initContext || steps.length != x.steps.length) {
                return false;
            }

            return ExtUtil.arrayEquals(steps, x.steps, false) && (initContext != INIT_CONTEXT_EXPR || filtExpr.equals(x.filtExpr));
        } else {
            return false;
        }
    }

    @Override
    public int hashCode() {
        int stepsHash = 0;
        for (XPathStep step : steps) {
            stepsHash ^= step.hashCode();
        }

        if (initContext == INIT_CONTEXT_EXPR) {
            return initContext ^ stepsHash ^ filtExpr.hashCode();
        }
        return initContext ^ stepsHash;
    }

    /**
     * Warning: this method has somewhat unclear semantics.
     *
     * "matches" follows roughly the same process as equals(), in that it goes
     * through the path step by step and compares whether each step can refer to the same node.
     * The only difference is that match() will allow for a named step to match a step who's name
     * is a wildcard.
     *
     * So
     * \/data\/path\/to
     * will "match"
     * \/data\/*\/to
     *
     * even though they are not equal.
     *
     * Matching is reflexive, consistent, and symmetric, but _not_ transitive.
     *
     * @return true if the expression is a path that matches this one
     */
    public boolean matches(XPathExpression o) {
        if (o instanceof XPathPathExpr) {
            XPathPathExpr x = (XPathPathExpr)o;

            //Shortcuts for easily comparable values
            if (initContext != x.initContext || steps.length != x.steps.length) {
                return false;
            }

            for (int i = 0; i < steps.length; i++) {
                if (!steps[i].matches(x.steps[i])) {
                    return false;
                }
            }

            // If all steps match, we still need to make sure we're in the same "context" if this
            // is a normal expression.
            return (initContext != INIT_CONTEXT_EXPR || filtExpr.equals(x.filtExpr));
        } else {
            return false;
        }
    }

    @Override
    public void readExternal(DataInputStream in, PrototypeFactory pf) throws IOException, DeserializationException {
        initContext = ExtUtil.readInt(in);
        if (initContext == INIT_CONTEXT_EXPR) {
            filtExpr = (XPathFilterExpr)ExtUtil.read(in, XPathFilterExpr.class, pf);
        }

        Vector v = (Vector)ExtUtil.read(in, new ExtWrapList(XPathStep.class), pf);
        steps = new XPathStep[v.size()];
        for (int i = 0; i < steps.length; i++) {
            steps[i] = ((XPathStep)v.elementAt(i)).intern();
        }
        cacheState = (CacheableExprState)ExtUtil.read(in, CacheableExprState.class, pf);
    }

    @Override
    public void writeExternal(DataOutputStream out) throws IOException {
        ExtUtil.writeNumeric(out, initContext);
        if (initContext == INIT_CONTEXT_EXPR) {
            ExtUtil.write(out, filtExpr);
        }

        Vector<XPathStep> v = new Vector<>();
        for (XPathStep step : steps) {
            v.addElement(step);
        }
        ExtUtil.write(out, new ExtWrapList(v));
        ExtUtil.write(out, cacheState);
    }

    public static XPathPathExpr fromRef(TreeReference ref) {
        XPathPathExpr path = new XPathPathExpr();
        path.initContext = (ref.isAbsolute() ? INIT_CONTEXT_ROOT : INIT_CONTEXT_RELATIVE);
        path.steps = new XPathStep[ref.size()];
        for (int i = 0; i < path.steps.length; i++) {
            if (ref.getName(i).equals(TreeReference.NAME_WILDCARD)) {
                path.steps[i] = new XPathStep(XPathStep.AXIS_CHILD, XPathStep.TEST_NAME_WILDCARD).intern();
            } else {
                path.steps[i] = new XPathStep(XPathStep.AXIS_CHILD, new XPathQName(ref.getName(i))).intern();
            }
        }
        return path;
    }

    @Override
    public Object pivot(DataInstance model, EvaluationContext evalContext,
                        Vector<Object> pivots, Object sentinal) throws UnpivotableExpressionException {
        TreeReference ref = this.getReference();
        //Either concretely the sentinal, or "."
        if (ref.equals(sentinal) || (ref.getRefLevel() == 0)) {
            return sentinal;
        } else {
            //It's very, very hard to figure out how to pivot predicates. For now, just skip it
            for (int i = 0; i < ref.size(); ++i) {
                if (ref.getPredicate(i) != null && ref.getPredicate(i).size() > 0) {
                    throw new UnpivotableExpressionException("Can't pivot filtered treereferences. Ref: " + ref.toString(true) + " has predicates.");
                }
            }
            return this.eval(model, evalContext);
        }
    }

    @Override
    public String toPrettyString() {
        try {
            return getReference().toString(true);
        } catch(Exception e) {
            return toDebugString();
        }
    }

    public String toDebugString() {
        try {
            StringBuffer sb = new StringBuffer();

            switch (initContext) {
                case INIT_CONTEXT_ROOT:
                    sb.append("/");
                    break;
                case INIT_CONTEXT_RELATIVE:
                    sb.append("./");
                    break;
                case INIT_CONTEXT_EXPR:
                    try {
                        sb.append((filtExpr.x).toPrettyString());
                        sb.append("/");
                    } catch (Exception e) {
                        sb.append(filtExpr.toString());
                    }
                    break;
            }
            for (int i = 0; i < steps.length; i++) {
                XPathStep step = steps[i];
                sb.append(step.toPrettyString());
                sb.append("/");
            }

            String output = sb.toString();
            if(output.endsWith("/")) {
                output = output.substring(0, output.length() - 1);
            }

            return output;
        } catch(Exception e) {
            return toString();
        }
    }

    @Override
    public void applyAndPropagateAnalyzer(XPathAnalyzer analyzer) throws AnalysisInvalidException {
        if (analyzer.shortCircuit()) {
            return;
        }
        analyzer.doAnalysis(XPathPathExpr.this);
        getReference().applyAndPropagateAnalyzer(analyzer);
    }
}
