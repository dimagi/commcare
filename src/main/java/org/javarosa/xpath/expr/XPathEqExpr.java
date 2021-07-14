package org.javarosa.xpath.expr;

import org.javarosa.core.model.condition.EvaluationContext;
import org.javarosa.core.model.instance.DataInstance;
import org.javarosa.core.util.externalizable.DeserializationException;
import org.javarosa.core.util.externalizable.ExtUtil;
import org.javarosa.core.util.externalizable.PrototypeFactory;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public class XPathEqExpr extends XPathBinaryOpExpr {
    public static final int EQ = 0;
    public static final int NEQ = 1;
    private boolean isEqOp;

    @SuppressWarnings("unused")
    public XPathEqExpr() {
    } //for deserialization

    public XPathEqExpr(int op, XPathExpression a, XPathExpression b) {
        super(op, a, b);

        isEqOp = (op == EQ);
    }

    @Override
    protected Object evalRaw(DataInstance model, EvaluationContext evalContext) {
        Object aval = FunctionUtils.unpack(a.eval(model, evalContext));
        Object bval = FunctionUtils.unpack(b.eval(model, evalContext));
        boolean eq = testEquality(aval, bval);

        return isEqOp == eq;
    }

    @Override
    public String toString() {
        return super.toString(isEqOp ? "==" : "!=");
    }

    @Override
    public void readExternal(DataInputStream in, PrototypeFactory pf) throws IOException, DeserializationException {
        isEqOp = ExtUtil.readBool(in);
        readExpressions(in, pf);

        if (isEqOp) {
            op = XPathEqExpr.EQ;
        } else {
            op = XPathEqExpr.NEQ;
        }
        cacheState = (CacheableExprState)ExtUtil.read(in, CacheableExprState.class, pf);
    }

    @Override
    public void writeExternal(DataOutputStream out) throws IOException {
        ExtUtil.writeBool(out, isEqOp);
        writeExpressions(out);
        ExtUtil.write(out, cacheState);
    }

    /**
     * Test two XPath Objects for equality the same way that they would be tested
     * if they were the result of an equality operation
     *
     * @param aval XPath Value
     * @param bval XPath Value
     * @return true if the two values are equal, false otherwise
     */
    public static boolean testEquality(Object aval, Object bval) {
        boolean eq = false;

        if (aval == null && bval == null) {
            eq = true;
        } else if (aval == null || bval == null) {
            eq = false;
        } else if (aval instanceof Boolean || bval instanceof Boolean) {
            if (!(aval instanceof Boolean)) {
                aval = FunctionUtils.toBoolean(aval);
            } else if (!(bval instanceof Boolean)) {
                bval = FunctionUtils.toBoolean(bval);
            }

            boolean ba = (Boolean)aval;
            boolean bb = (Boolean)bval;
            eq = (ba == bb);
        } else if (aval instanceof Double || bval instanceof Double) {
            if (!(aval instanceof Double)) {
                aval = FunctionUtils.toNumeric(aval);
            } else if (!(bval instanceof Double)) {
                bval = FunctionUtils.toNumeric(bval);
            }

            double fa = (Double)aval;
            double fb = (Double)bval;
            eq = Math.abs(fa - fb) < 1.0e-12;
        } else {
            aval = FunctionUtils.toString(aval);
            bval = FunctionUtils.toString(bval);
            eq = (aval.equals(bval));
        }
        return eq;
    }

    @Override
    public String toPrettyString() {
        String prettyA = a.toPrettyString();
        String prettyB = b.toPrettyString();

        if (isEqOp) {
            return prettyA + " = " + prettyB;
        } else {
            return prettyA + " != " + prettyB;
        }
    }

    @Override
    public boolean equals(Object o) {
        return (this == o) ||
                ((o instanceof XPathEqExpr) && binOpEquals((XPathBinaryOpExpr)o));
    }
}
