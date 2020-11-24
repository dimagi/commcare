package org.commcare.cases.query;

import org.javarosa.core.model.condition.EvaluationContext;
import org.javarosa.core.model.trace.EvaluationTrace;
import org.javarosa.xpath.expr.XPathExpression;

import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Vector;

/**
 * Class that loads, runs, and manages the QueryHandlers during storage lookups
 *
 * Created by ctsims on 1/25/2017.
 */

public class QueryPlanner {

    private List<QueryHandler> handlers = new Vector<>();

    /**
     * @param profiles the predicate profiles to be attempted to run
     * @param currentQueryContext the QueryContext of the current lookup
     * @return null if the query could not be handled by this planner
     *
     * Note: Should profiles that have been run should be removed by the handler
     */
    public Collection<Integer> attemptProfiledQuery(Vector<PredicateProfile> profiles,
                                                QueryContext currentQueryContext){
        for (int i = 0 ; i < handlers.size() ; ++i) {
            QueryHandler handler = handlers.get(i);
            Object queryPlan = handler.profileHandledQuerySet(profiles);
            if (queryPlan != null) {
                Collection<Integer> retVal = handler.loadProfileMatches(queryPlan, currentQueryContext);
                if (retVal != null) {
                    handler.updateProfiles(queryPlan, profiles);
                    return retVal;
                }
            }
        }
        return null;
    }

    public void addQueryHandler(QueryHandler handler) {
        handlers.add(handler);
        Collections.sort(handlers, (first, second) -> first.getExpectedRuntime() - second.getExpectedRuntime());
    }


    public Collection<PredicateProfile> collectPredicateProfiles(
            Vector<XPathExpression> predicates, QueryContext queryContext,
            EvaluationContext evalContext) {
        if (predicates == null) {
            return null;
        }
        Vector<PredicateProfile> returnProfile = new Vector<>();
        for (int i = 0; i < handlers.size(); ++i) {
            Collection<PredicateProfile> profile =
                    handlers.get(i).collectPredicateProfiles(predicates, queryContext, evalContext);
            if (profile != null) {
                returnProfile.addAll(profile);
            }
        }
        return returnProfile;
    }


}
