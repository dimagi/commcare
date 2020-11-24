package org.commcare.cases.query;

import org.javarosa.core.model.condition.EvaluationContext;
import org.javarosa.xpath.expr.XPathExpression;

import java.util.Collection;
import java.util.List;
import java.util.Vector;

/**
 * A query handler encapsulates piece of code which can inspect predicates for shortcuts in how
 * to evaluate them, and provide an expectaiton of how efficient they will be in doing so, to
 * allow faster potential optimizations to be attempted before slower ones.
 *
 * Query Handlers are specific to a Model (like a StorageBackedTreeRoot) and their responses
 * are expected to be model id's of elements in that model. The integer model ID's are converted
 * to multiplicities by the root.
 *
 * The lifecycle of a query handler executes in order:
 * 1. [Potentially] Collect custom profiles from predicates unique to this handler
 * 2. Given collected profiles, identify whether this handler can match any of them
 * 3. Attempt a match
 * 4. Remove any matched predicates.
 *
 * 3 and 4 are split because there may be cases where #2 identifies a high likelihood of matching
 * a prediate, but a match turns out to not be possible. In those cases it's still important to be
 * able to fail to process the provided predicate query and keep it in the set to be handled by
 * another handler.
 *
 * Note: QueryHandler objects should not store any query specific state. Caches of data and other
 * results-specific information should be stored in QueryCache objects attached to the query context
 *
 * Created by ctsims on 1/25/2017.
 */

public interface QueryHandler<T> {

    /**
     * @return a numeric (lower-is-better) estimate of how fast this handler is.
     *
     * Right now this is a very rough estimate and should simply be compared against the limited
     * set of other query handlers.
     *
     * TODO: This should be revised in a way that can be based on more meaningful context
     */
    int getExpectedRuntime();

    /**
     * Optional Method: Return 'null' if your query handler can operate with the existing
     * predicate profiles
     *
     * Provided with a list of predicates and a set of contexts, parse out predicates that this
     * handler will uniquely be able to process.
     *
     * NOTE: Currently if this returns values, we skip evaluation of other profiles due to
     * limitations in how we can prevent lengthy operations from occuring when generating them.
     *
     * In the future, we should make profiles "lazy" to allow collecting profiles and matching
     * them with potential optimizations.
     **/
    Collection<PredicateProfile> collectPredicateProfiles(
            Vector<XPathExpression> predicates, QueryContext context,
            EvaluationContext evaluationContext);

    /**
     * Given a set of predicate profiles, identify whether this handler can provide an optimized
     * lookup for any of them. If so, return an object representing the query to be run, it will
     * be passed back in the loadProfileMatches method.
     *
     * @param profiles pending predicate profiles which havent' been processed/handled
     * @return a querySet object to be passed back into this handler specifying the query to be run.
     * often a single (or collection of) predicate profiles.
     */
    T profileHandledQuerySet(Vector<PredicateProfile> profiles);

    /**
     * Given a querySet to be matched from the profileHandledQuerySet method and a context,
     * generate a list of matching model ID's.
     *
     * If for some reason the query set cannot be run against the current context, this method
     * can return null, which will signal the the query couldn't be run and no predicates have
     * been evaluated.
     */
    Collection<Integer> loadProfileMatches(T querySet, QueryContext queryContext);

    /**
     * Given a succesful profile match, this method updates the predicateprofiles to remove profiles
     * that no longer need to be evaluated.
     *
     * This method will not be run if loadProfileMatches returned null to signal a failure
     */
    void updateProfiles(T querySet, Vector<PredicateProfile> profiles);


}
