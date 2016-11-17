package org.commcare.suite.model;

import org.commcare.util.GridCoordinate;
import org.commcare.util.GridStyle;
import org.javarosa.core.model.condition.EvaluationContext;
import org.javarosa.core.model.instance.TreeReference;
import org.javarosa.core.util.ArrayUtilities;
import org.javarosa.core.util.OrderedHashtable;
import org.javarosa.core.util.externalizable.DeserializationException;
import org.javarosa.core.util.externalizable.ExtUtil;
import org.javarosa.core.util.externalizable.ExtWrapList;
import org.javarosa.core.util.externalizable.ExtWrapMap;
import org.javarosa.core.util.externalizable.ExtWrapNullable;
import org.javarosa.core.util.externalizable.ExtWrapTagged;
import org.javarosa.core.util.externalizable.Externalizable;
import org.javarosa.core.util.externalizable.PrototypeFactory;
import org.javarosa.model.xform.XPathReference;
import org.javarosa.xpath.XPathParseTool;
import org.javarosa.xpath.expr.FunctionUtils;
import org.javarosa.xpath.expr.XPathExpression;
import org.javarosa.xpath.parser.XPathSyntaxException;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.Vector;

/**
 * A Detail model defines the structure in which
 * the details about something should be displayed
 * to users (generally cases or referrals).
 *
 * Detail models maintain a set of Text objects
 * which provide a template for how details about
 * objects should be displayed, along with a model
 * which defines the context of what data should be
 * obtained to fill in those templates.
 *
 * @author ctsims
 */
public class Detail implements Externalizable {

    private String id;
    private TreeReference nodeset;

    private DisplayUnit title;

    /**
     * Optional and only relevant if this detail has child details. In that
     * case, form may be 'image' or omitted.
     */
    private String titleForm;

    private Detail[] details;
    private DetailField[] fields;
    private Callout callout;

    private OrderedHashtable<String, String> variables;
    private OrderedHashtable<String, XPathExpression> variablesCompiled;


    private Vector<Action> actions;

    // Force the activity that is showing this detail to show itself in landscape view only
    private boolean forceLandscapeView;

    private XPathExpression focusFunction;

    // region -- These fields are only used if this detail is a case tile

    // Allows for the possibility of case tiles being displayed in a grid
    private int numEntitiesToDisplayPerRow;

    // Indicates that the height of a single cell in the tile's grid layout should be treated as
    // equal to its width, rather than being computed independently
    private boolean useUniformUnitsInCaseTile;

    // endregion

    /**
     * Serialization Only
     */
    public Detail() {

    }

    public Detail(String id, DisplayUnit title, String nodeset,
                  Vector<Detail> detailsVector,
                  Vector<DetailField> fieldsVector,
                  OrderedHashtable<String, String> variables,
                  Vector<Action> actions, Callout callout, String fitAcross,
                  String uniformUnitsString, String forceLandscape, String focusFunction) {

        if (detailsVector.size() > 0 && fieldsVector.size() > 0) {
            throw new IllegalArgumentException("A detail may contain either sub-details or fields, but not both.");
        }

        this.id = id;
        this.title = title;
        if (nodeset != null) {
            this.nodeset = XPathReference.getPathExpr(nodeset).getReference();
        }
        this.details = ArrayUtilities.copyIntoArray(detailsVector, new Detail[detailsVector.size()]);
        this.fields = ArrayUtilities.copyIntoArray(fieldsVector, new DetailField[fieldsVector.size()]);
        this.variables = variables;
        this.actions = actions;
        this.callout = callout;
        this.useUniformUnitsInCaseTile = "true".equals(uniformUnitsString);
        this.forceLandscapeView = "true".equals(forceLandscape);

        if (focusFunction != null) {
            try {
                this.focusFunction = XPathParseTool.parseXPath(focusFunction);
            } catch (XPathSyntaxException e) {
                e.printStackTrace();
                throw new RuntimeException(e.getMessage());
            }
        }

        if (fitAcross != null) {
            try {
                this.numEntitiesToDisplayPerRow = Integer.parseInt(fitAcross);
            } catch (NumberFormatException e) {
                numEntitiesToDisplayPerRow = 1;
            }
        } else {
            numEntitiesToDisplayPerRow = 1;
        }
    }

    /**
     * @return The id of this detail template
     */
    public String getId() {
        return id;
    }

    /**
     * @return A title to be displayed to users regarding
     * the type of content being described.
     */
    public DisplayUnit getTitle() {
        return title;
    }

    /**
     * @return A reference to a set of sub-elements of this detail. If provided,
     * the detail will display fields for each element of this nodeset.
     */
    public TreeReference getNodeset() {
        return nodeset;
    }

    /**
     * @return Any child details of this detail.
     */
    public Detail[] getDetails() {
        return details;
    }

    /**
     * Given a detail, return an array of details that will contain either
     * - all child details
     * - a single-element array containing the given detail, if it has no children
     */
    public Detail[] getFlattenedDetails() {
        if (this.isCompound()) {
            return this.getDetails();
        }
        return new Detail[]{this};
    }

    /**
     * @return Any fields belonging to this detail.
     */
    public DetailField[] getFields() {
        return fields;
    }

    /**
     * @return True iff this detail has child details.
     */
    public boolean isCompound() {
        return details.length > 0;
    }

    /**
     * Whether this detail is expected to be so huge in scope that
     * the platform should limit its strategy for loading it to be asynchronous
     * and cached on special keys.
     */
    public boolean useAsyncStrategy() {
        for (DetailField f : getFields()) {
            if (f.getSortOrder() == DetailField.SORT_ORDER_CACHABLE) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void readExternal(DataInputStream in, PrototypeFactory pf) throws IOException, DeserializationException {
        id = (String)ExtUtil.read(in, new ExtWrapNullable(String.class), pf);
        title = (DisplayUnit)ExtUtil.read(in, DisplayUnit.class, pf);
        titleForm = (String)ExtUtil.read(in, new ExtWrapNullable(String.class), pf);
        nodeset = (TreeReference)ExtUtil.read(in, new ExtWrapNullable(TreeReference.class), pf);
        Vector<Detail> theDetails = (Vector<Detail>)ExtUtil.read(in, new ExtWrapList(Detail.class), pf);
        details = new Detail[theDetails.size()];
        ArrayUtilities.copyIntoArray(theDetails, details);
        Vector<DetailField> theFields = (Vector<DetailField>)ExtUtil.read(in, new ExtWrapList(DetailField.class), pf);
        fields = new DetailField[theFields.size()];
        ArrayUtilities.copyIntoArray(theFields, fields);
        variables = (OrderedHashtable<String, String>)ExtUtil.read(in, new ExtWrapMap(String.class, String.class, ExtWrapMap.TYPE_ORDERED), pf);
        actions = (Vector<Action>)ExtUtil.read(in, new ExtWrapList(Action.class), pf);
        callout = (Callout)ExtUtil.read(in, new ExtWrapNullable(Callout.class), pf);
        forceLandscapeView = ExtUtil.readBool(in);
        focusFunction = (XPathExpression)ExtUtil.read(in, new ExtWrapNullable(new ExtWrapTagged()), pf);
        numEntitiesToDisplayPerRow = (int)ExtUtil.readNumeric(in);
        useUniformUnitsInCaseTile = ExtUtil.readBool(in);
    }

    @Override
    public void writeExternal(DataOutputStream out) throws IOException {
        ExtUtil.write(out, new ExtWrapNullable(id));
        ExtUtil.write(out, title);
        ExtUtil.write(out, new ExtWrapNullable(titleForm));
        ExtUtil.write(out, new ExtWrapNullable(nodeset));
        ExtUtil.write(out, new ExtWrapList(ArrayUtilities.toVector(details)));
        ExtUtil.write(out, new ExtWrapList(ArrayUtilities.toVector(fields)));
        ExtUtil.write(out, new ExtWrapMap(variables));
        ExtUtil.write(out, new ExtWrapList(actions));
        ExtUtil.write(out, new ExtWrapNullable(callout));
        ExtUtil.writeBool(out, forceLandscapeView);
        ExtUtil.write(out, new ExtWrapNullable(focusFunction == null ? null : new ExtWrapTagged(focusFunction)));
        ExtUtil.writeNumeric(out, numEntitiesToDisplayPerRow);
        ExtUtil.writeBool(out, useUniformUnitsInCaseTile);
    }

    public OrderedHashtable<String, XPathExpression> getVariableDeclarations() {
        if (variablesCompiled == null) {
            variablesCompiled = new OrderedHashtable<>();
            for (Enumeration en = variables.keys(); en.hasMoreElements(); ) {
                String key = (String)en.nextElement();
                //TODO: This is stupid, parse this stuff at XML Parse time.
                try {
                    variablesCompiled.put(key, XPathParseTool.parseXPath(variables.get(key)));
                } catch (XPathSyntaxException e) {
                    e.printStackTrace();
                    throw new RuntimeException(e.getMessage());
                }
            }
        }
        return variablesCompiled;
    }

    /**
     * Retrieve the custom/callback action used in this detail in
     * the event that there are no matches.
     *
     * @return An Action model definition if one is defined for this detail.
     * Null if there is no associated action.
     */
    public Vector<Action> getCustomActions(EvaluationContext evaluationContext) {
        Vector<Action> relevantActions = new Vector<>();
        for (Action action : actions) {
            if (action.isRelevant(evaluationContext)) {
                relevantActions.addElement(action);
            }
        }
        return relevantActions;
    }

    /**
     * @return The indices of which fields should be used for sorting and their order
     */
    public int[] getSortOrder() {
        Vector<Integer> indices = new Vector<>();
        outer:
        for (int i = 0; i < fields.length; ++i) {
            int order = fields[i].getSortOrder();
            if (order < 1) {
                continue;
            }
            for (int j = 0; j < indices.size(); ++j) {
                if (order < fields[indices.elementAt(j)].getSortOrder()) {
                    indices.insertElementAt(i, j);
                    continue outer;
                }
            }
            //otherwise it's larger than all of the other fields.
            indices.addElement(i);
            continue;
        }
        if (indices.size() == 0) {
            return new int[]{};
        } else {
            int[] ret = new int[indices.size()];
            for (int i = 0; i < ret.length; ++i) {
                ret[i] = indices.elementAt(i);
            }
            return ret;
        }
    }

    //These are just helpers around the old structure. Shouldn't really be
    //used if avoidable

    /**
     * Obsoleted - Don't use
     */
    public String[] getTemplateSizeHints() {
        return new Map<String[]>(new String[fields.length]) {
            @Override
            protected void map(DetailField f, String[] a, int i) {
                a[i] = f.getTemplateWidthHint();
            }
        }.go();
    }

    /**
     * Obsoleted - Don't use
     */
    public String[] getHeaderForms() {
        return new Map<String[]>(new String[fields.length]) {
            @Override
            protected void map(DetailField f, String[] a, int i) {
                a[i] = f.getHeaderForm();
            }
        }.go();
    }

    /**
     * Obsoleted - Don't use
     */
    public String[] getTemplateForms() {
        return new Map<String[]>(new String[fields.length]) {
            @Override
            protected void map(DetailField f, String[] a, int i) {
                a[i] = f.getTemplateForm();
            }
        }.go();
    }

    public boolean usesEntityTileView() {
        boolean usingEntityTile = false;
        for (DetailField currentField : fields) {
            if (currentField.getGridX() >= 0 && currentField.getGridY() >= 0 &&
                    currentField.getGridWidth() >= 0 && currentField.getGridHeight() > 0) {
                usingEntityTile = true;
            }
        }
        return usingEntityTile;
    }

    public boolean shouldBeLaidOutInGrid() {
        return numEntitiesToDisplayPerRow > 1 && usesEntityTileView();
    }

    public int getNumEntitiesToDisplayPerRow() {
        return numEntitiesToDisplayPerRow;
    }

    public boolean useUniformUnitsInCaseTile() {
        return useUniformUnitsInCaseTile;
    }

    public boolean forcesLandscape() {
        return forceLandscapeView;
    }

    public GridCoordinate[] getGridCoordinates() {
        GridCoordinate[] mGC = new GridCoordinate[fields.length];

        for (int i = 0; i < fields.length; i++) {
            DetailField currentField = fields[i];
            mGC[i] = new GridCoordinate(currentField.getGridX(), currentField.getGridY(),
                    currentField.getGridWidth(), currentField.getGridHeight());
        }

        return mGC;
    }

    public GridStyle[] getGridStyles() {
        GridStyle[] mGC = new GridStyle[fields.length];

        for (int i = 0; i < fields.length; i++) {
            DetailField currentField = fields[i];
            mGC[i] = new GridStyle(currentField.getFontSize(), currentField.getHorizontalAlign(),
                    currentField.getVerticalAlign(), currentField.getCssId());
        }

        return mGC;
    }

    public Callout getCallout() {
        return callout;
    }

    private abstract class Map<E> {
        private final E a;

        private Map(E a) {
            this.a = a;
        }

        protected abstract void map(DetailField f, E a, int i);

        public E go() {
            for (int i = 0; i < fields.length; ++i) {
                map(fields[i], a, i);
            }
            return a;
        }
    }

    /**
     * Given an evaluation context which a qualified nodeset, will populate that EC with the
     * evaluated variable values associated with this detail.
     *
     * @param ec The Evaluation Context to be used to evaluate the variable expressions and which
     *           will be populated by their result. Will be modified in place.
     */
    public void populateEvaluationContextVariables(EvaluationContext ec) {
        Hashtable<String, XPathExpression> variables = getVariableDeclarations();
        //These are actually in an ordered hashtable, so we can't just get the keyset, since it's
        //in a 1.3 hashtable equivalent
        for (Enumeration en = variables.keys(); en.hasMoreElements(); ) {
            String key = (String)en.nextElement();
            ec.setVariable(key, FunctionUtils.unpack(variables.get(key).eval(ec)));
        }
    }

    public boolean evaluateFocusFunction(EvaluationContext ec) {
        if (focusFunction == null) {
            return false;
        }
        Object value = FunctionUtils.unpack(focusFunction.eval(ec));
        return FunctionUtils.toBoolean(value);
    }

    public XPathExpression getFocusFunction() {
        return focusFunction;
    }
}
