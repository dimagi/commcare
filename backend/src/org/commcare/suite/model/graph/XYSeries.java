package org.commcare.suite.model.graph;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Enumeration;
import java.util.Hashtable;

import org.javarosa.model.Text;
import org.javarosa.core.model.condition.EvaluationContext;
import org.javarosa.core.model.instance.TreeReference;
import org.javarosa.core.util.externalizable.DeserializationException;
import org.javarosa.core.util.externalizable.ExtUtil;
import org.javarosa.core.util.externalizable.ExtWrapMap;
import org.javarosa.core.util.externalizable.Externalizable;
import org.javarosa.core.util.externalizable.PrototypeFactory;
import org.javarosa.model.xform.XPathReference;
import org.javarosa.xpath.XPathParseTool;
import org.javarosa.xpath.expr.XPathExpression;
import org.javarosa.xpath.parser.XPathSyntaxException;

/**
 * Single series (line) on an xy graph.
 *
 * @author jschweers
 */
public class XYSeries implements Externalizable, Configurable {
    private TreeReference mNodeSet;
    private Hashtable<String, Text> mConfiguration;

    private String mX;
    private String mY;

    private XPathExpression mXParse;
    private XPathExpression mYParse;

    /*
     * Deserialization only!
     */
    public XYSeries() {

    }

    public XYSeries(String nodeSet) {
        mNodeSet = XPathReference.getPathExpr(nodeSet).getReference(true);
        mConfiguration = new Hashtable<String, Text>();
    }

    public TreeReference getNodeSet() {
        return mNodeSet;
    }

    public String getX() {
        return mX;
    }

    public void setX(String x) {
        mX = x;
        mXParse = null;
    }

    public String getY() {
        return mY;
    }

    public void setY(String y) {
        mY = y;
        mYParse = null;
    }

    public void setConfiguration(String key, Text value) {
        mConfiguration.put(key, value);
    }

    public Text getConfiguration(String key) {
        return mConfiguration.get(key);
    }

    public Enumeration getConfigurationKeys() {
        return mConfiguration.keys();
    }

    /*
     * (non-Javadoc)
     * @see org.javarosa.core.util.externalizable.Externalizable#readExternal(java.io.DataInputStream, org.javarosa.core.util.externalizable.PrototypeFactory)
     */
    public void readExternal(DataInputStream in, PrototypeFactory pf)
            throws IOException, DeserializationException {
        mX = ExtUtil.readString(in);
        mY = ExtUtil.readString(in);
        mNodeSet = (TreeReference)ExtUtil.read(in, TreeReference.class, pf);
        mConfiguration = (Hashtable<String, Text>)ExtUtil.read(in, new ExtWrapMap(String.class, Text.class), pf);
    }

    /*
     * (non-Javadoc)
     * @see org.javarosa.core.util.externalizable.Externalizable#writeExternal(java.io.DataOutputStream)
     */
    public void writeExternal(DataOutputStream out) throws IOException {
        ExtUtil.writeString(out, mX);
        ExtUtil.writeString(out, mY);
        ExtUtil.write(out, mNodeSet);
        ExtUtil.write(out, new ExtWrapMap(mConfiguration));
    }

    /*
     * Parse all not-yet-parsed functions in this object.
     */
    protected void parse() throws XPathSyntaxException {
        if (mXParse == null) {
            mXParse = parse(mX);
        }
        if (mYParse == null) {
            mYParse = parse(mY);
        }
    }

    /*
     * Helper function to parse a single piece of XPath.
     */
    protected XPathExpression parse(String function) throws XPathSyntaxException {
        if (function == null) {
            return null;
        }
        return XPathParseTool.parseXPath("string(" + function + ")");
    }

    /*
     * Get the actual x value within a given EvaluationContext.
     */
    public String evaluateX(EvaluationContext context) throws XPathSyntaxException {
        parse();
        return evaluateExpression(mXParse, context);
    }

    /*
     * Get the actual y value within a given EvaluationContext.
     */
    public String evaluateY(EvaluationContext context) throws XPathSyntaxException {
        parse();
        return evaluateExpression(mYParse, context);
    }

    /*
     * Helper for evaluateX and evaluateY.
     */
    protected String evaluateExpression(XPathExpression expression, EvaluationContext context) {
        if (expression != null) {
            String value = (String)expression.eval(context.getMainInstance(), context);
            if (value.length() > 0) {
                return value;
            }
        }
        return null;
    }
}
