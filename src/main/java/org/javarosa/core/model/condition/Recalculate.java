package org.javarosa.core.model.condition;

import org.javarosa.core.model.Constants;
import org.javarosa.core.model.FormDef;
import org.javarosa.core.model.data.BooleanData;
import org.javarosa.core.model.data.DateData;
import org.javarosa.core.model.data.DateTimeData;
import org.javarosa.core.model.data.DecimalData;
import org.javarosa.core.model.data.IAnswerData;
import org.javarosa.core.model.data.IntegerData;
import org.javarosa.core.model.data.LongData;
import org.javarosa.core.model.data.SelectMultiData;
import org.javarosa.core.model.data.SelectOneData;
import org.javarosa.core.model.data.StringData;
import org.javarosa.core.model.data.TimeData;
import org.javarosa.core.model.data.UncastData;
import org.javarosa.core.model.instance.FormInstance;
import org.javarosa.core.model.instance.TreeReference;
import org.javarosa.core.model.utils.DateUtils;
import org.javarosa.xpath.XPathException;

import java.util.Date;

public class Recalculate extends Triggerable {
    @SuppressWarnings("unused")
    public Recalculate() {
        // for externalization
    }

    public Recalculate(IConditionExpr expr, TreeReference contextRef) {
        super(expr, contextRef);
    }

    @Override
    public Object eval(FormInstance model, EvaluationContext ec) {
        try {
            return expr.evalRaw(model, ec);
        } catch (XPathException e) {
            e.setMessagePrefix("Calculation Error: Error in calculation for " + contextRef.toString(true));
            throw e;
        }
    }

    @Override
    public void apply(TreeReference ref, Object result, FormInstance model, FormDef f) {
        int dataType = f.getMainInstance().resolveReference(ref).getDataType();
        f.setAnswer(wrapData(result, dataType), ref);
    }

    @Override
    public boolean canCascade() {
        return true;
    }

    //droos 1/29/10: we need to come up with a consistent rule for whether the resulting data is determined
    //by the type of the instance node, or the type of the expression result. right now it's a mix and a mess
    //note a caveat with going solely by instance node type is that untyped nodes default to string!

    //for now, these are the rules:
    // if node type == bool, convert to boolean (for numbers, zero = f, non-zero = t; empty string = f, all other datatypes -> error)
    // if numeric data, convert to int if node type is int OR data is an integer; else convert to double
    // if string data or date data, keep as is
    // if NaN or empty string, null

    /**
     * convert the data object returned by the xpath expression into an IAnswerData suitable for
     * storage in the FormInstance
     */
    public static IAnswerData wrapData(Object val, int dataType) {
        if ((val instanceof String && ((String)val).length() == 0) ||
                (val instanceof Double && ((Double)val).isNaN())) {
            return null;
        }

        if (Constants.DATATYPE_BOOLEAN == dataType || val instanceof Boolean) {
            //ctsims: We should really be using the boolean datatype for real, it's
            //necessary for backend calculations and XSD compliance

            boolean b;

            if (val instanceof Boolean) {
                b = (Boolean)val;
            } else if (val instanceof Double) {
                Double d = (Double)val;
                b = Math.abs(d) > 1.0e-12 && !Double.isNaN(d);
            } else if (val instanceof String) {
                String s = (String)val;
                b = s.length() > 0;
            } else {
                throw new RuntimeException("unrecognized data representation while trying to convert to BOOLEAN");
            }

            return new BooleanData(b);
        } else if (val instanceof Double) {
            double d = (Double)val;
            long l = (long)d;
            boolean isIntegral = Math.abs(d - l) < 1.0e-9;
            if (Constants.DATATYPE_INTEGER == dataType ||
                    (isIntegral && (Integer.MAX_VALUE >= l) && (Integer.MIN_VALUE <= l))) {
                return new IntegerData((int)d);
            } else if (Constants.DATATYPE_LONG == dataType || isIntegral) {
                return new LongData((long)d);
            } else {
                return new DecimalData(d);
            }
        } else if (dataType == Constants.DATATYPE_CHOICE) {
            return new SelectOneData().cast(new UncastData(String.valueOf(val)));
        } else if (dataType == Constants.DATATYPE_CHOICE_LIST) {
            return new SelectMultiData().cast(new UncastData(String.valueOf(val)));
        } else if (val instanceof String) {
            return new StringData((String)val);
        } else if (val instanceof Date) {
            if (dataType == Constants.DATATYPE_DATE_TIME || DateUtils.hasTimeComponent((Date)val)) {
                return new DateData((Date)val);
            } else if (dataType == Constants.DATATYPE_TIME) {
                return new TimeData((Date)val);
            } else {
                return new DateTimeData((Date)val);
            }
        } else {
            throw new RuntimeException("unrecognized data type in 'calculate' expression: " + val.getClass().getName());
        }
    }


    /**
     * Recalculates are equal based on their triggers and identity as a calculate triggerable
     */
    @Override
    public boolean equals(Object o) {
        if (o instanceof Recalculate) {
            Recalculate r = (Recalculate)o;
            return (this == r || super.equals(r));
        }
        return false;
    }

    @Override
    public int hashCode() {
        return "calculate".hashCode() ^ super.hashCode();
    }


    @Override
    public String getDebugLabel() {
        return "calculate";
    }
}