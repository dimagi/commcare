package org.commcare.suite.model;

import org.javarosa.core.model.condition.EvaluationContext;
import org.javarosa.core.util.externalizable.DeserializationException;
import org.javarosa.core.util.externalizable.ExtUtil;
import org.javarosa.core.util.externalizable.ExtWrapList;
import org.javarosa.core.util.externalizable.ExtWrapMap;
import org.javarosa.core.util.externalizable.ExtWrapNullable;
import org.javarosa.core.util.externalizable.Externalizable;
import org.javarosa.core.util.externalizable.PrototypeFactory;
import org.javarosa.xpath.XPathParseTool;
import org.javarosa.xpath.expr.XPathFuncExpr;
import org.javarosa.xpath.parser.XPathSyntaxException;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.Vector;

/**
 * Created by wpride1 on 4/14/15.
 *
 * Object representation of application callouts described in suite.xml
 * Used in callouts from EntitySelectActivity and EntityDetailActivity
 */
public class Callout implements Externalizable, DetailTemplate {

    String actionName;
    String image;
    String displayName;
    Hashtable<String, String> extras = new Hashtable<String, String>();
    Vector<String> responses = new Vector<String>();

    public Callout(String actionName, String image, String displayName) {
        this.actionName = actionName;
        this.image = image;
        this.displayName = displayName;
    }

    /*
    * (non-Javadoc)
    * @see org.commcare.suite.model.DetailTemplate#evaluate(org.javarosa.core.model.condition.EvaluationContext)
    */
    public CalloutData evaluate(EvaluationContext context) {

        Hashtable<String, String> evaluatedExtras = new Hashtable<String, String>();

        Enumeration keys = extras.keys();

        while (keys.hasMoreElements()) {
            String key = (String)keys.nextElement();
            try {
                String evaluatedKey = XPathFuncExpr.toString(XPathParseTool.parseXPath(extras.get(key)).eval(context));
                evaluatedExtras.put(key, evaluatedKey);
            } catch (XPathSyntaxException e) {
                // do nothing
            }
        }

        // emit a CalloutData with the extras evaluated. used for the detail screen.
        CalloutData ret = new CalloutData(actionName, image, displayName, evaluatedExtras, responses);

        return ret;
    }

    public CalloutData evaluate() {

        //emit a callout without the extras evaluated. used for the case list button.
        CalloutData ret = new CalloutData(actionName, image, displayName, extras, responses);

        return ret;
    }

    /*
     * (non-Javadoc)
     * @see org.javarosa.core.util.externalizable.Externalizable#readExternal(java.io.DataInputStream, org.javarosa.core.util.externalizable.PrototypeFactory)
     */
    public void readExternal(DataInputStream in, PrototypeFactory pf) throws IOException, DeserializationException {
        displayName = ExtUtil.readString(in);
        actionName = (String)ExtUtil.read(in, new ExtWrapNullable(String.class));
        image = (String)ExtUtil.read(in, new ExtWrapNullable(String.class));
        extras = (Hashtable<String, String>)ExtUtil.read(in, new ExtWrapMap(String.class, String.class));
        responses = (Vector<String>)ExtUtil.read(in, new ExtWrapList(String.class), pf);
    }

    /*
     * (non-Javadoc)
     * @see org.javarosa.core.util.externalizable.Externalizable#writeExternal(java.io.DataOutputStream)
     */
    public void writeExternal(DataOutputStream out) throws IOException {
        ExtUtil.writeString(out, displayName);
        ExtUtil.write(out, new ExtWrapNullable(actionName));
        ExtUtil.write(out, new ExtWrapNullable(image));
        ExtUtil.write(out, new ExtWrapMap(extras));
        ExtUtil.write(out, new ExtWrapList(responses));
    }

    public String getImage() {
        return image;
    }

    public String getActionName() {
        return actionName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void addExtra(String key, String value) {
        extras.put(key, value);
    }

    public void addResponse(String key) {
        responses.addElement(key);
    }

    public Hashtable<String, String> getExtras() {
        return extras;
    }

    public Vector<String> getResponses() {
        return responses;
    }

    @Override
    public String toString() {
        return "Callout{" +
                "actionName='" + actionName + '\'' +
                ", image='" + image + '\'' +
                ", displayName='" + displayName + '\'' +
                ", extras=" + Arrays.toString(extras.entrySet().toArray()) +
                ", responses=" + responses +
                '}';
    }
}
