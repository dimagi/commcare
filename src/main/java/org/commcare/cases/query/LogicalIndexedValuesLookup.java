package org.commcare.cases.query;

import org.javarosa.xpath.expr.XPathBoolExpr;
import org.javarosa.xpath.expr.XPathOpExpr;

/**
 *
 * Captures logical expressions which combine indexed value lookups
 *
 * Created by ctsims on 05/22/2020
 */

public class LogicalIndexedValuesLookup implements PredicateProfile {
    IndexedValueLookup a;
    IndexedValueLookup b;

    int operator;

    public LogicalIndexedValuesLookup(IndexedValueLookup a, IndexedValueLookup b, int operator) {
        this.a = a;
        this.b = b;
        if(operator != XPathBoolExpr.OR && operator!= XPathBoolExpr.AND) {
            throw new RuntimeException("Must be OR | AND");
        }
        this.operator = operator;
    }

    public IndexedValueLookup getA() {
        return a;
    }

    public IndexedValueLookup getB() {
        return b;
    }

    public int getOperator() {
        return operator;
    }

    @Override
    public String getKey() {
        return a.getKey();
    }
}
