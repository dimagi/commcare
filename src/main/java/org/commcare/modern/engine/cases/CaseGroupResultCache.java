package org.commcare.modern.engine.cases;

import org.commcare.cases.model.Case;
import org.commcare.cases.query.QueryCache;

import java.util.HashMap;
import java.util.LinkedHashSet;

/**
 * Created by ctsims on 1/25/2017.
 */

public class CaseGroupResultCache implements QueryCache {

    public static final int MAX_PREFETCH_CASE_BLOCK = 2000;

    private HashMap<String,LinkedHashSet<Integer>> bulkFetchBodies = new HashMap<>();

    private HashMap<Integer, Case> cachedCases = new HashMap<>();


    public void reportBulkCaseBody(String key, LinkedHashSet<Integer> ids) {
        if(bulkFetchBodies.containsKey(key)) {
            return;
        }
        bulkFetchBodies.put(key, ids);
    }

    public boolean hasMatchingCaseSet(int recordId) {
        return isLoaded(recordId) || getTranche(recordId) != null;
    }

    public LinkedHashSet<Integer> getTranche(int recordId) {
        for(LinkedHashSet<Integer> tranche: bulkFetchBodies.values()) {
            if(tranche.contains(recordId)){
                return tranche;
            }
        }
        return null;
    }

    public boolean isLoaded(int recordId) {
        return cachedCases.containsKey(recordId);
    }

    public HashMap<Integer, Case> getLoadedCaseMap() {
        return cachedCases;
    }

    public Case getLoadedCase(int recordId) {
        return cachedCases.get(recordId);
    }
}
