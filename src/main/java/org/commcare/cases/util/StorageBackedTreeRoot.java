package org.commcare.cases.util;

import org.commcare.cases.query.*;
import org.commcare.cases.query.IndexedSetMemberLookup;
import org.commcare.cases.query.IndexedValueLookup;
import org.commcare.cases.query.PredicateProfile;
import org.commcare.cases.query.handlers.BasicStorageBackedCachingQueryHandler;
import org.commcare.cases.query.handlers.LogicalValueIndexHandler;
import org.commcare.modern.engine.cases.RecordSetResultCache;
import org.commcare.modern.util.PerformanceTuningUtil;
import org.javarosa.core.model.condition.EvaluationContext;
import org.javarosa.core.model.instance.AbstractTreeElement;
import org.javarosa.core.model.instance.TreeReference;
import org.javarosa.core.model.trace.EvaluationTrace;
import org.javarosa.core.services.storage.IStorageUtilityIndexed;
import org.javarosa.core.util.DataUtil;
import org.javarosa.xpath.expr.FunctionUtils;
import org.javarosa.xpath.expr.XPathEqExpr;
import org.javarosa.xpath.expr.XPathExpression;
import org.javarosa.xpath.expr.XPathPathExpr;
import org.javarosa.xpath.expr.XPathSelectedFunc;

import java.util.Collection;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Vector;

/**
 * @author ctsims
 */
public abstract class StorageBackedTreeRoot<T extends AbstractTreeElement> implements AbstractTreeElement<T> {

    protected QueryPlanner queryPlanner;
    protected BasicStorageBackedCachingQueryHandler defaultCacher;

    protected final Hashtable<Integer, Integer> objectIdMapping = new Hashtable<>();

    /**
     * Super basic cache for Key/Index responses from the DB
     */
    private Hashtable<String, LinkedHashSet<Integer>> mIndexResultCache = new Hashtable<>();

    /**
     * Get the key/value meta lookups for the most recent batch fetch. Used to prime a couple
     * of other caches, although this should eventually migrate to use the new cueing framework
     */
    protected String[][] mMostRecentBatchFetch = null;


    protected abstract String getChildHintName();

    protected abstract Hashtable<XPathPathExpr, String> getStorageIndexMap();

    protected abstract IStorageUtilityIndexed<?> getStorage();

    protected abstract void initStorageCache();

    protected String translateFilterExpr(XPathPathExpr expressionTemplate, XPathPathExpr matchingExpr,
                                         Hashtable<XPathPathExpr, String> indices) {
        return indices.get(expressionTemplate);
    }

    @Override
    public Collection<TreeReference> tryBatchChildFetch(String name, int mult,
                                                        Vector<XPathExpression> predicates,
                                                        EvaluationContext evalContext) {
        //Restrict what we'll handle for now. All we want to deal with is predicate expressions on case blocks
        if (!name.equals(getChildHintName()) || mult != TreeReference.INDEX_UNBOUND || predicates == null) {
            return null;
        }

        Hashtable<XPathPathExpr, String> indices = getStorageIndexMap();

        Vector<PredicateProfile> profiles = new Vector<>();

        QueryContext queryContext = evalContext.getCurrentQueryContext();

        //First, attempt to use 'preferred' optimizations detectable by the query planner
        //using advanced inspection of the predicates

        Vector<PredicateProfile> preferredProfiles = new Vector<>();

        preferredProfiles.addAll(getQueryPlanner().collectPredicateProfiles(
                predicates, queryContext, evalContext));

        //For now we are going to skip looking deeper if we trigger
        //any of the planned optimizations
        if(preferredProfiles.size() > 0) {
            Collection<TreeReference> response = processPredicatesAndPrepareResponse(preferredProfiles,
                    queryContext, predicates);

            //For now if there are any results we should press forward. We don't have a meaningful
            //way to combine these results with native optimizations
            if(response != null) {
                return response;
            }
        }

        //Otherwise, identify predicates that we _might_ be able to evaluate more efficiently
        //based on normal keyed behavior
        collectNativePredicateProfiles(predicates, indices, evalContext, profiles);

        return processPredicatesAndPrepareResponse(profiles, queryContext, predicates);
    }

    private Collection<TreeReference> processPredicatesAndPrepareResponse(Vector<PredicateProfile> profiles,
                                                                          QueryContext queryContext,
                                                                          Vector<XPathExpression> predicates) {
        //Now go through each profile and see if we can match / process any of them. If not, we
        // will return null and move on
        Vector<Integer> toRemove = new Vector<>();
        Collection<Integer> selectedElements = processPredicates(toRemove, profiles, queryContext);

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

    private void collectNativePredicateProfiles(Vector<XPathExpression> predicates,
                                          Hashtable<XPathPathExpr, String> indices,
                                          EvaluationContext evalContext,
                                          Vector<PredicateProfile> optimizations) {

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
                            optimizations.addElement(new IndexedValueLookup(filterIndex, o));

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
            LogicalIndexedValuesLookup lookup = new LogicalValueIndexHandler(indices, getStorage()).
                    getLogicalIndexedValueLookupIfExists(xpe, evalContext);
            if (lookup != null) {
                optimizations.addElement(lookup);
                continue predicate;
            }

            //There's only one case where we want to keep moving along, and we would have triggered it if it were going to happen,
            //so otherwise, just get outta here.
            break;
        }
    }

    protected QueryPlanner getQueryPlanner() {
        if(queryPlanner == null) {
            queryPlanner = new QueryPlanner();
            initBasicQueryHandlers(queryPlanner);
        }
        return queryPlanner;
    }

    protected void initBasicQueryHandlers(QueryPlanner queryPlanner) {
        defaultCacher = new BasicStorageBackedCachingQueryHandler();

        //TODO: Move the actual indexed query optimization used in this
        //method into its own (or a matching) cache method
        queryPlanner.addQueryHandler(defaultCacher);
    }


    private Collection<Integer> processPredicates(Vector<Integer> toRemove,
                                              Vector<PredicateProfile> profiles,
                                              QueryContext currentQueryContext) {
        Collection<Integer> selectedElements = null;
        IStorageUtilityIndexed<?> storage = getStorage();
        int predicatesProcessed = 0;
        while (profiles.size() > 0) {
            int startCount = profiles.size();
            Collection<Integer> plannedQueryResults =
                    this.getQueryPlanner().attemptProfiledQuery(profiles, currentQueryContext);

            if (plannedQueryResults != null) {
                // merge with any other sets of cases
                if (selectedElements == null) {
                    selectedElements = plannedQueryResults;
                } else {
                    selectedElements = DataUtil.intersection(selectedElements, plannedQueryResults);
                }
            } else {
                Collection<Integer> cases = null;
                try {
                    //Get all of the cases that meet this criteria
                    cases = this.getNextIndexMatch(profiles, storage, currentQueryContext);
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

            if(selectedElements != null && selectedElements.size() == 0) {
                //There's nothing left! We can completely wipe the remaining profiles
                profiles.clear();
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

    private Collection<TreeReference> buildReferencesFromFetchResults(Collection<Integer> selectedElements) {
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
     * @param currentQueryContext
     * @return A Vector of integer ID's for records in the provided storage which match one or more of the keys provided.
     * @throws IllegalArgumentException If there was no index matching possible on the provided key and the key/value vectors
     *                                  won't be shortened.
     */
    protected Collection<Integer> getNextIndexMatch(Vector<PredicateProfile> profiles,
                                                    IStorageUtilityIndexed<?> storage,
                                                    QueryContext currentQueryContext) throws IllegalArgumentException {
        int numKeysToProcess = this.getNumberOfBatchableKeysInProfileSet(profiles);

        if(numKeysToProcess  < 1) {
            throw new IllegalArgumentException("No optimization path found for optimization type");
        }


        String[] namesToMatch = new String[numKeysToProcess];
        String[] valuesToMatch = new String[numKeysToProcess];

        String cacheKey = "";
        String keyDescription ="";

        for (int i = numKeysToProcess - 1; i >= 0; i--) {
            namesToMatch[i] = profiles.elementAt(i).getKey();
            valuesToMatch[i] = (String)
                    (((IndexedValueLookup)profiles.elementAt(i)).value);

            cacheKey += "|" + namesToMatch[i] + "=" + valuesToMatch[i];
            keyDescription += namesToMatch[i] + "|";
        }
        mMostRecentBatchFetch = new String[2][];
        mMostRecentBatchFetch[0] = namesToMatch;
        mMostRecentBatchFetch[1] = valuesToMatch;

        String storageTreeName = this.getStorageCacheName();

        LinkedHashSet<Integer> ids;
        if(mIndexResultCache.containsKey(cacheKey)) {
            ids = mIndexResultCache.get(cacheKey);
        } else {
            EvaluationTrace trace = new EvaluationTrace(String.format("Storage [%s] Key Lookup [%s]", storageTreeName, keyDescription));
            ids = new LinkedHashSet<>();
            storage.getIDsForValues(namesToMatch, valuesToMatch, ids);
            trace.setOutcome("Results: " + ids.size());
            currentQueryContext.reportTrace(trace);

            mIndexResultCache.put(cacheKey, ids);
        }

        if(ids.size() > 50 && ids.size() < PerformanceTuningUtil.getMaxPrefetchCaseBlock()) {
            RecordSetResultCache cue = currentQueryContext.getQueryCache(RecordSetResultCache.class);
            cue.reportBulkRecordSet(cacheKey, getStorageCacheName(), ids);
        }

        //Ok, we matched! Remove all of the keys that we matched
        for (int i = 0; i < numKeysToProcess; ++i) {
            profiles.removeElementAt(0);
        }
        return ids;
    }

    /**
     * Provide the number of keys that should be included in a general multi-key metadata lookup
     * from the provided set. Each key in the returned set should be an indexed value lookup
     * which can be matched in flat metadata with no additional processing.
     *
     * @param profiles A set of potential predicate profiles for bulk processing
     * @return The number of elements to process from the provided set. If only the first
     * profile would be processed, for instance, this method should return 1
     */
    protected int getNumberOfBatchableKeysInProfileSet(Vector<PredicateProfile> profiles) {
        int keysToBatch = 0;
        //Otherwise see how many of these we can bulk process
        for (int i = 0; i < profiles.size(); ++i) {
            //If the current key isn't an indexedvalue lookup, we can't process in this step
            if (!(profiles.elementAt(i) instanceof IndexedValueLookup)) {
                break;
            }

            //otherwise, it's now in our queue
            keysToBatch++;
        }
        return keysToBatch;
    }

    /**
     * @return A string which will provide a unique name for the storage that is used in this tree
     * root. Used to differentiate the record ID's retrieved during operations on this root in
     * internal caches
     */
    public abstract String getStorageCacheName();
}
