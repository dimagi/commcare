package org.commcare.cases.query.handlers;

import org.commcare.cases.query.IndexedSetMemberLookup;
import org.commcare.cases.query.IndexedValueLookup;
import org.commcare.cases.query.LogicalIndexedValuesLookup;
import org.commcare.cases.query.PredicateProfile;
import org.commcare.cases.query.QueryContext;
import org.commcare.cases.query.QueryHandler;
import org.commcare.cases.query.queryset.ModelQueryLookup;
import org.commcare.cases.query.queryset.ModelQuerySetMatcher;
import org.commcare.cases.query.queryset.QuerySetLookup;
import org.commcare.cases.util.StorageBackedTreeRoot;
import org.javarosa.core.model.condition.EvaluationContext;
import org.javarosa.core.model.instance.TreeReference;
import org.javarosa.core.model.trace.EvaluationTrace;
import org.javarosa.core.services.storage.IStorageUtilityIndexed;
import org.javarosa.core.util.DataUtil;
import org.javarosa.xpath.expr.FunctionUtils;
import org.javarosa.xpath.expr.XPathBoolExpr;
import org.javarosa.xpath.expr.XPathEqExpr;
import org.javarosa.xpath.expr.XPathExpression;
import org.javarosa.xpath.expr.XPathPathExpr;

import java.util.Collection;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Vector;

import kotlin.collections.IndexedValue;

/**
 * Optimizes predicates which are single logical operations that combine indexed values.
 *
 * Essentially enables one to query
 *
 * [index = a OR index = b]
 *
 * with a query optimization plan of
 *
 * O([index=a]) + O([index=b])
 *
 * Created by ctsims on 5/26/2020.
 */

public class LogicalValueIndexHandler implements QueryHandler<LogicalIndexedValuesLookup> {
    Hashtable<XPathPathExpr, String> indices;

    private IStorageUtilityIndexed<?> storage;

    public LogicalValueIndexHandler(Hashtable<XPathPathExpr, String> indices,
                                    IStorageUtilityIndexed<?> storage) {
         this.indices = indices;
         this.storage = storage;
    }

    @Override
    public int getExpectedRuntime() {
        return 1;
    }

    @Override
    public LogicalIndexedValuesLookup profileHandledQuerySet(Vector<PredicateProfile> profiles) {
        if (profiles.get(0) instanceof LogicalIndexedValuesLookup) {
            return (LogicalIndexedValuesLookup)profiles.get(0);
        }
        return null;
    }

    @Override
    public Collection<Integer> loadProfileMatches(LogicalIndexedValuesLookup lookupExpr, QueryContext queryContext) {
        if (lookupExpr.getOperator() == XPathBoolExpr.AND) {
            LinkedHashSet<Integer> ids = new LinkedHashSet<>();

            String[] namesToMatch = new String[2];
            String[] valuesToMatch = new String[2];

            namesToMatch[0] = lookupExpr.getA().key;
            valuesToMatch[0] = (String)lookupExpr.getA().value;

            namesToMatch[1] = lookupExpr.getB().key;
            valuesToMatch[1] = (String)lookupExpr.getB().value;

            String cacheKey = namesToMatch[0] + "=" + valuesToMatch[0] +
                    " AND " + namesToMatch[1] + "=" + valuesToMatch[1];

            EvaluationTrace trace =
                    new EvaluationTrace("Logical Combination Lookup |" + cacheKey);

            List<Integer> results = storage.getIDsForValues(namesToMatch, valuesToMatch, ids);

            trace.setOutcome("Results: " + ids.size());
            queryContext.reportTrace(trace);

            return results;
        } else if (lookupExpr.getOperator() == XPathBoolExpr.OR) {
            LinkedHashSet<Integer> ids = new LinkedHashSet<>();

            String cacheKey = lookupExpr.getA().key + "=" + lookupExpr.getA().value +
                    " OR " + lookupExpr.getB().key + "=" + lookupExpr.getB().value;

            EvaluationTrace trace =
                new EvaluationTrace("Logical Combination Lookup |" + cacheKey);

            storage.getIDsForValues(new String[] {lookupExpr.getA().key}, new String[] {(String)lookupExpr.getA().value}, ids);

            storage.getIDsForValues(new String[] {lookupExpr.getB().key}, new String[] {(String)lookupExpr.getB().value}, ids);

            trace.setOutcome("Matches: " + ids.size());
            queryContext.reportTrace(trace);

            return ids;
        }
        return null;
    }

    @Override
    public void updateProfiles(LogicalIndexedValuesLookup querySet, Vector<PredicateProfile> profiles) {
        profiles.remove(querySet);
    }

    @Override
    public Collection<PredicateProfile> collectPredicateProfiles(Vector<XPathExpression> predicates,
                                                                 QueryContext context,
                                                                 EvaluationContext evalContext) {

        LogicalIndexedValuesLookup lookup =
                getLogicalIndexedValueLookupIfExists(predicates.elementAt(0), evalContext);

        if (lookup == null) {
            return null;
        }

        Vector<PredicateProfile> newProfile = new Vector<>();
        newProfile.add(lookup);
        return newProfile;

    }

    public LogicalIndexedValuesLookup getLogicalIndexedValueLookupIfExists(XPathExpression inExpr,
                                                                            EvaluationContext evalContext) {
        if(!(inExpr instanceof XPathBoolExpr)) {
            return null;
        }

        XPathBoolExpr expr = (XPathBoolExpr)inExpr;

        IndexedValueLookup aLookup = identifyIndexedValuePredicate(expr.a, evalContext);

        if(aLookup == null) {
            return null;
        }

        IndexedValueLookup bLookup = identifyIndexedValuePredicate(expr.b, evalContext);

        if(bLookup == null) {
            return null;
        }
        LogicalIndexedValuesLookup lookup =
                new LogicalIndexedValuesLookup(aLookup, bLookup, expr.op);

        return lookup;
    }

    public IndexedValueLookup identifyIndexedValuePredicate(XPathExpression xpe,
                                                            EvaluationContext evalContext) {
        if (xpe instanceof XPathEqExpr && ((XPathEqExpr)xpe).op == XPathEqExpr.EQ) {
            XPathExpression left = ((XPathEqExpr)xpe).a;
            if (left instanceof XPathPathExpr) {

                for (Enumeration en = indices.keys(); en.hasMoreElements(); ) {
                    XPathPathExpr expr = (XPathPathExpr)en.nextElement();
                    if (expr.matches(left)) {
                        String filterIndex = translateFilterExpr(expr, (XPathPathExpr)left, indices);

                        //TODO: We need a way to determine that this value does not also depend on anything in the current context, not
                        //sure the best way to do that....? Maybe tell the evaluation context to skip out here if it detects a request
                        //to resolve in a certain area?
                        Object o = FunctionUtils.unpack(((XPathEqExpr)xpe).b.eval(evalContext));
                        return new IndexedValueLookup(filterIndex, o);
                    }
                }
            }
        }
        return null;
    }

    private String translateFilterExpr(XPathPathExpr expressionTemplate, XPathPathExpr matchingExpr,
                                         Hashtable<XPathPathExpr, String> indices) {
        return indices.get(expressionTemplate);
    }
}
