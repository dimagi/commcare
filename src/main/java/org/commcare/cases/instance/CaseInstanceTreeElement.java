package org.commcare.cases.instance;

import org.commcare.cases.model.Case;
import org.commcare.cases.query.IndexedSetMemberLookup;
import org.commcare.cases.query.IndexedValueLookup;
import org.commcare.cases.query.NegativeIndexedValueLookup;
import org.commcare.cases.query.PredicateProfile;
import org.commcare.cases.query.QueryContext;
import org.commcare.cases.query.QueryPlanner;
import org.commcare.cases.query.handlers.ModelQueryLookupHandler;
import org.commcare.cases.query.queryset.CaseModelQuerySetMatcher;
import org.commcare.modern.engine.cases.CaseIndexQuerySetTransform;
import org.commcare.modern.engine.cases.CaseIndexTable;
import org.commcare.modern.engine.cases.query.CaseIndexPrefetchHandler;
import org.javarosa.core.model.instance.AbstractTreeElement;
import org.javarosa.core.model.instance.TreeReference;
import org.javarosa.core.model.utils.CacheHost;
import org.javarosa.core.services.storage.IStorageIterator;
import org.javarosa.core.services.storage.IStorageUtilityIndexed;
import org.javarosa.core.util.DataUtil;
import org.javarosa.model.xform.XPathReference;
import org.javarosa.xpath.expr.XPathPathExpr;

import java.util.Collection;
import java.util.Hashtable;
import java.util.LinkedHashSet;
import java.util.Vector;

import javax.annotation.Nullable;

import sun.rmi.runtime.Log;

/**
 * The root element for the <casedb> abstract type. All children are
 * nodes in the case database. Depending on instantiation, the <casedb>
 * may include only a subset of the full db.
 *
 * @author ctsims
 */
public class CaseInstanceTreeElement extends StorageInstanceTreeElement<Case, CaseChildElement> implements CacheHost {

    public static final String MODEL_NAME = "casedb";

    //Xpath parsing is sllllllloooooooowwwwwww
    public final static XPathPathExpr CASE_ID_EXPR = XPathReference.getPathExpr("@case_id");
    public final static XPathPathExpr CASE_ID_EXPR_TWO = XPathReference.getPathExpr("./@case_id");
    private final static XPathPathExpr CASE_TYPE_EXPR = XPathReference.getPathExpr("@case_type");
    private final static XPathPathExpr CASE_STATUS_EXPR = XPathReference.getPathExpr("@status");
    private final static XPathPathExpr CASE_INDEX_EXPR = XPathReference.getPathExpr("index/*");
    private final static XPathPathExpr OWNER_ID_EXPR = XPathReference.getPathExpr("@owner_id");
    private final static XPathPathExpr EXTERNAL_ID_EXPR = XPathReference.getPathExpr("@external_id");
    private final static XPathPathExpr CATEGORY_EXPR = XPathReference.getPathExpr("@category");
    private final static XPathPathExpr STATE_EXPR = XPathReference.getPathExpr("@state");


    private final Hashtable<Integer, Integer> multiplicityIdMapping = new Hashtable<>();

    @Nullable
    private final CaseIndexTable caseIndexTable;

    //We're storing this here for now because this is a safe lifecycle object that must represent
    //a single snapshot of the case database, but it could be generalized later.
    private Hashtable<String, LinkedHashSet<Integer>> mIndexCache = new Hashtable<>();

    public CaseInstanceTreeElement(AbstractTreeElement instanceRoot,
                                   IStorageUtilityIndexed<Case> storage){
        this(instanceRoot, storage, null);
    }

    public CaseInstanceTreeElement(AbstractTreeElement instanceRoot,
                                   IStorageUtilityIndexed<Case> storage, CaseIndexTable caseIndexTable) {
        super(instanceRoot, storage, MODEL_NAME, "case");
        this.caseIndexTable = caseIndexTable;
    }

    @Override
    protected CaseChildElement buildElement(StorageInstanceTreeElement<Case, CaseChildElement> storageInstance,
                                            int recordId, String id, int mult) {
        return new CaseChildElement(storageInstance, recordId, null, mult);
    }

    @Override
    protected void initBasicQueryHandlers(QueryPlanner queryPlanner) {
        super.initBasicQueryHandlers(queryPlanner);
        queryPlanner.addQueryHandler(new CaseIndexPrefetchHandler(caseIndexTable));
        CaseModelQuerySetMatcher matcher = new CaseModelQuerySetMatcher(multiplicityIdMapping);
        matcher.addQuerySetTransform(new CaseIndexQuerySetTransform(caseIndexTable));
        queryPlanner.addQueryHandler(new ModelQueryLookupHandler(matcher));
    }

    @Override
    protected int getNumberOfBatchableKeysInProfileSet(Vector<PredicateProfile> profiles) {
        int keysToBatch = 0;
        //Otherwise see how many of these we can bulk process
        for (int i = 0; i < profiles.size(); ++i) {
            //If the current key is an index fetch, we actually can't do it in bulk,
            //so we need to stop
            if (profiles.elementAt(i).getKey().startsWith(Case.INDEX_CASE_INDEX_PRE) ||
                    !(profiles.elementAt(i) instanceof IndexedValueLookup ||
                            profiles.elementAt(i) instanceof NegativeIndexedValueLookup)) {
                break;
            }
            keysToBatch++;
        }
        return keysToBatch;
    }



    @Override
    protected CaseChildElement getChildTemplate() {
        return CaseChildElement.buildCaseChildTemplate(this);
    }

    @Override
    protected String translateFilterExpr(XPathPathExpr expressionTemplate, XPathPathExpr matchingExpr,
                                         Hashtable<XPathPathExpr, String> indices) {
        String filter = super.translateFilterExpr(expressionTemplate, matchingExpr, indices);

        //If we're matching a case index, we've got some magic to take care of. First,
        //generate the expected case ID
        if (expressionTemplate == CASE_INDEX_EXPR) {
            filter += matchingExpr.steps[1].name.name;
        }

        return filter;
    }

    @Override
    protected Hashtable<XPathPathExpr, String> getStorageIndexMap() {
        Hashtable<XPathPathExpr, String> indices = new Hashtable<>();

        //TODO: Much better matching
        indices.put(CASE_ID_EXPR, Case.INDEX_CASE_ID);
        indices.put(CASE_ID_EXPR_TWO, Case.INDEX_CASE_ID);
        indices.put(CASE_TYPE_EXPR, Case.INDEX_CASE_TYPE);
        indices.put(CASE_STATUS_EXPR, Case.INDEX_CASE_STATUS);
        indices.put(CASE_INDEX_EXPR, Case.INDEX_CASE_INDEX_PRE);
        indices.put(OWNER_ID_EXPR, Case.INDEX_OWNER_ID);
        indices.put(EXTERNAL_ID_EXPR, Case.INDEX_EXTERNAL_ID);
        indices.put(CATEGORY_EXPR, Case.INDEX_CATEGORY);
        indices.put(STATE_EXPR, Case.INDEX_STATE);

        return indices;
    }

    public String getStorageCacheName() {
        return CaseInstanceTreeElement.MODEL_NAME;
    }

    @Override
    public String getCacheIndex(TreeReference ref) {
        //NOTE: there's no evaluation here as to whether the ref is suitable
        //we only follow one pattern for now and it's evaluated below.

        loadElements();

        //Testing - Don't bother actually seeing whether this fits
        int i = ref.getMultiplicity(1);
        if (i != -1) {
            Integer val = this.multiplicityIdMapping.get(DataUtil.integer(i));
            if (val == null) {
                return null;
            } else {
                return val.toString();
            }
        }
        return null;
    }

    @Override
    public boolean isReferencePatternCachable(TreeReference ref) {
        //we only support one pattern here, a raw, qualified
        //reference to an element at the case level with no
        //predicate support. The ref basically has to be a raw
        //pointer to one of this instance's children
        if (!ref.isAbsolute()) {
            return false;
        }

        if (ref.hasPredicates()) {
            return false;
        }
        if (ref.size() != 2) {
            return false;
        }

        if (!"casedb".equalsIgnoreCase(ref.getName(0))) {
            return false;
        }
        if (!"case".equalsIgnoreCase(ref.getName(1))) {
            return false;
        }
        return ref.getMultiplicity(1) >= 0;
    }

    @Override
    public String[][] getCachePrimeGuess() {
        return mMostRecentBatchFetch;
    }

    @Override
    protected Collection<Integer> getNextIndexMatch(Vector<PredicateProfile> profiles,
                                                    IStorageUtilityIndexed<?> storage,
                                                    QueryContext currentQueryContext) throws IllegalArgumentException {
        //If the index object starts with "case-in-" it's actually a case index query and we need to run
        //this over the case index table
        String firstKey = profiles.elementAt(0).getKey();
        if (firstKey.startsWith(Case.INDEX_CASE_INDEX_PRE)) {
            return performCaseIndexQuery(firstKey, profiles);
        }
        return super.getNextIndexMatch(profiles, storage, currentQueryContext);
    }

    @Override
    protected synchronized void loadElements() {
        if (elements != null) {
            return;
        }
        elements = new Vector<>();

        int mult = 0;

        for (IStorageIterator i = storage.iterate(false); i.hasMore(); ) {
            int id = i.nextID();
            elements.add(buildElement(this, id, null, mult));
            objectIdMapping.put(DataUtil.integer(id), DataUtil.integer(mult));
            multiplicityIdMapping.put(DataUtil.integer(mult), DataUtil.integer(id));
            mult++;
        }
    }

    private LinkedHashSet<Integer> performCaseIndexQuery(String firstKey, Vector<PredicateProfile> optimizations) {
        //CTS - March 9, 2015 - Introduced a small cache for child index queries here because they
        //are a frequent target of bulk operations like graphing which do multiple requests across the
        //same query.

        PredicateProfile op = optimizations.elementAt(0);

        //TODO: This should likely be generalized for a number of other queries with bulk/nodeset
        //returns
        String indexName = firstKey.substring(Case.INDEX_CASE_INDEX_PRE.length());

        String indexCacheKey = null;

        LinkedHashSet<Integer> matchingCases = null;

        if (op instanceof IndexedValueLookup) {

            IndexedValueLookup iop = (IndexedValueLookup)op;

            String value = (String)iop.value;

            //TODO: Evaluate whether our indices could contain "|" but I don't imagine how they could.
            indexCacheKey = indexName + "|" + value;

            //Check whether we've got a cache of this index.
            if (mIndexCache.containsKey(indexCacheKey)) {
                //remove the match from the inputs
                optimizations.removeElementAt(0);
                return mIndexCache.get(indexCacheKey);
            }

            matchingCases = caseIndexTable.getCasesMatchingIndex(indexName, value);
        } else if (op instanceof IndexedSetMemberLookup) {
            IndexedSetMemberLookup sop = (IndexedSetMemberLookup)op;
            matchingCases = caseIndexTable.getCasesMatchingValueSet(indexName, sop.valueSet);
        } else {
            throw new IllegalArgumentException("No optimization path found for optimization type");
        }

        //Clear the most recent index and wipe it, because there is no way it is going to be useful
        //after this
        mMostRecentBatchFetch = new String[4][];

        //remove the match from the inputs
        optimizations.removeElementAt(0);

        if (indexCacheKey != null) {
            //For now we're only going to run this on very small data sets because we don't
            //want to manage this too explicitly until we generalize. Almost all results here
            //will be very very small either way (~O(10's of cases)), so given that this only
            //exists across one session that won't get out of hand
            if (matchingCases.size() < 50) {
                //Should never hit this, but don't wanna have any runaway memory if we do.
                if (mIndexCache.size() > 100) {
                    mIndexCache.clear();
                }

                mIndexCache.put(indexCacheKey, matchingCases);
            }
        }
        return matchingCases;
    }
}
