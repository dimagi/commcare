package org.commcare.cases.util;

import org.commcare.cases.query.*;
import org.commcare.cases.query.IndexedSetMemberLookup;
import org.commcare.cases.query.IndexedValueLookup;
import org.commcare.cases.query.PredicateProfile;
import org.javarosa.core.model.condition.EvaluationContext;
import org.javarosa.core.model.instance.AbstractTreeElement;
import org.javarosa.core.model.instance.TreeReference;
import org.javarosa.core.services.storage.IStorageUtilityIndexed;
import org.javarosa.core.util.DataUtil;
import org.javarosa.xpath.expr.FunctionUtils;
import org.javarosa.xpath.expr.XPathEqExpr;
import org.javarosa.xpath.expr.XPathExpression;
import org.javarosa.xpath.expr.XPathPathExpr;
import org.javarosa.xpath.expr.XPathSelectedFunc;

import java.util.Enumeration;
import java.util.Hashtable;
import java.util.Vector;

/**
 * @author ctsims
 */
public abstract class StorageBackedTreeRoot<T extends AbstractTreeElement> implements AbstractTreeElement<T> {

    protected org.commcare.cases.query.QueryPlanner queryPlanner;
    protected org.commcare.cases.query.handlers.BasicStorageBackedCachingQueryHandler defaultCacher;

    protected Hashtable<Integer, Integer> objectIdMapping;

    protected abstract String getChildHintName();

    protected abstract Hashtable<XPathPathExpr, String> getStorageIndexMap();

    protected abstract IStorageUtilityIndexed<?> getStorage();

    protected abstract void initStorageCache();

    protected String translateFilterExpr(XPathPathExpr expressionTemplate, XPathPathExpr matchingExpr,
                                         Hashtable<XPathPathExpr, String> indices) {
        return indices.get(expressionTemplate);
    }

    @Override
    public Vector<TreeReference> tryBatchChildFetch(String name, int mult,
                                                    Vector<XPathExpression> predicates,
                                                    EvaluationContext evalContext) {
        //Restrict what we'll handle for now. All we want to deal with is predicate expressions on case blocks
        if (!name.equals(getChildHintName()) || mult != TreeReference.INDEX_UNBOUND || predicates == null) {
            return null;
        }

        Hashtable<XPathPathExpr, String> indices = getStorageIndexMap();

        Vector<org.commcare.cases.query.PredicateProfile> profiles = new Vector<>();

        //First, go get a list of predicates that we _might_ be able to evaluate more efficiently
        collectPredicateProfiles(predicates, indices, evalContext, profiles);

        //Now go through each profile and see if we can match / process any of them. If not, we
        // will return null and move on
        Vector<Integer> toRemove = new Vector<>();
        Vector<Integer> selectedElements = processPredicates(toRemove, profiles,
                evalContext.getCurrentQueryContext());

        //if we weren't able to evaluate any predicates, signal that.
        if (selectedElements == null) {
            return null;
        }

        //otherwise, remove all of the predicates we've already evaluated
        for (int i = toRemove.size() - 1; i >= 0; i--) {
            predicates.removeElementAt(toRemove.elementAt(i));
        }

        return buildReferencesFromFetchResults(selectedElements);
    }

    private void collectPredicateProfiles(Vector<XPathExpression> predicates,
                                          Hashtable<XPathPathExpr, String> indices,
                                          EvaluationContext evalContext,
                                          Vector<org.commcare.cases.query.PredicateProfile> optimizations) {
        predicate:
        for (XPathExpression xpe : predicates) {
            //what we want here is a static evaluation of the expression to see if it consists of evaluating
            //something we index with something static.
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
                            optimizations.addElement(new org.commcare.cases.query.IndexedValueLookup(filterIndex, o));

                            continue predicate;
                        }
                    }
                }
            } else if (xpe instanceof XPathSelectedFunc) {
                XPathExpression lookupArg = ((XPathSelectedFunc)xpe).args[1];
                if (lookupArg instanceof XPathPathExpr) {
                    for (Enumeration en = indices.keys(); en.hasMoreElements(); ) {
                        XPathPathExpr expr = (XPathPathExpr)en.nextElement();
                        if (expr.matches(lookupArg)) {
                            String filterIndex = translateFilterExpr(expr, (XPathPathExpr)lookupArg, indices);

                            //TODO: We need a way to determine that this value does not also depend on anything in the current context, not
                            //sure the best way to do that....? Maybe tell the evaluation context to skip out here if it detects a request
                            //to resolve in a certain area?
                            Object o = FunctionUtils.unpack(((XPathSelectedFunc)xpe).args[0].eval(evalContext));

                            optimizations.addElement(new IndexedSetMemberLookup(filterIndex, o));

                            continue predicate;
                        }
                    }
                }
            }


            //There's only one case where we want to keep moving along, and we would have triggered it if it were going to happen,
            //so otherwise, just get outta here.
            break;
        }
    }

    public QueryPlanner getQueryPlanner() {
        if(queryPlanner == null) {
            queryPlanner = new org.commcare.cases.query.QueryPlanner();
            initBasicQueryHandlers(queryPlanner);
        }
        return queryPlanner;
    }

    protected void initBasicQueryHandlers(QueryPlanner queryPlanner) {
        defaultCacher = new org.commcare.cases.query.handlers.BasicStorageBackedCachingQueryHandler();

        //TODO: Move the actual indexed query optimization used in this
        //method into its own (or a matching) cache method
        queryPlanner.addQueryHandler(defaultCacher);
    }


    private Vector<Integer> processPredicates(Vector<Integer> toRemove,
                                              Vector<org.commcare.cases.query.PredicateProfile> profiles,
                                              QueryContext currentQueryContext) {
        Vector<Integer> selectedElements = null;
        IStorageUtilityIndexed<?> storage = getStorage();
        int predicatesProcessed = 0;
        while (profiles.size() > 0) {

            int startCount = profiles.size();

            Vector<Integer> plannedQueryResults =
                    this.getQueryPlanner().attemptProfiledQuery(profiles, currentQueryContext);

            if (plannedQueryResults != null) {
                // merge with any other sets of cases
                if (selectedElements == null) {
                    selectedElements = plannedQueryResults;
                } else {
                    selectedElements = DataUtil.intersection(selectedElements, plannedQueryResults);
                }
            } else {
                Vector<Integer> cases = null;
                try {
                    //Get all of the cases that meet this criteria
                    cases = this.getNextIndexMatch(profiles, storage);
                } catch (IllegalArgumentException IAE) {
                    // Encountered a new index type
                    break;
                }

                // merge with any other sets of cases
                if (selectedElements == null) {
                    selectedElements = cases;
                } else {
                    selectedElements = DataUtil.intersection(selectedElements, cases);
                }
            }

            int numPredicatesRemoved = startCount - profiles.size();
            for (int i = 0; i < numPredicatesRemoved; ++i) {
                //Note that this predicate is evaluated and doesn't need to be evaluated in the future.
                toRemove.addElement(DataUtil.integer(predicatesProcessed));
                predicatesProcessed++;
            }
            currentQueryContext = currentQueryContext.testForInlineScopeEscalation(selectedElements.size());
        }
        return selectedElements;
    }

    private Vector<TreeReference> buildReferencesFromFetchResults(Vector<Integer> selectedElements) {
        TreeReference base = this.getRef();

        initStorageCache();

        Vector<TreeReference> filtered = new Vector<>();
        for (Integer i : selectedElements) {
            //this takes _waaaaay_ too long, we need to refactor this
            TreeReference ref = base.clone();
            int realIndex = objectIdMapping.get(i);
            ref.add(this.getChildHintName(), realIndex);
            filtered.addElement(ref);
        }
        return filtered;
    }

    /**
     * Attempt to process one or more of the elements from the heads of the key/value vector, and return the
     * matching ID's. If an argument is processed, they should be removed from the key/value vector
     *
     * <b>Important:</b> This method and any re-implementations <i>must remove at least one key/value pair
     * from the incoming Vectors</i>, or must throw an IllegalArgumentException to denote that the provided
     * key can't be processed in the current context. The method can optionally remove/process more than one
     * key at a time, but is expected to process at least the first.
     *
     * @param profiles    A vector of pending optimizations to be attempted. The keys should be processed left->right
     * @param storage The storage to be processed
     * @return A Vector of integer ID's for records in the provided storage which match one or more of the keys provided.
     * @throws IllegalArgumentException If there was no index matching possible on the provided key and the key/value vectors
     *                                  won't be shortened.
     */
    protected Vector<Integer> getNextIndexMatch(Vector<PredicateProfile> profiles,
                                                IStorageUtilityIndexed<?> storage) throws IllegalArgumentException {
        if(!(profiles.elementAt(0) instanceof org.commcare.cases.query.IndexedValueLookup)) {
            throw new IllegalArgumentException("No optimization path found for optimization type");
        }

        org.commcare.cases.query.IndexedValueLookup op = (IndexedValueLookup)profiles.elementAt(0);

        //Get matches if it works
        Vector<Integer> returnValue = storage.getIDsForValue(op.key, op.value);

        if(defaultCacher != null) {
            defaultCacher.cacheResult(op.key, op.value, returnValue);
        }

        //If we processed this, pop it off the queue
        profiles.removeElementAt(0);

        return returnValue;
    }
}
