package org.javarosa.core.model.utils;

import org.commcare.cases.query.QueryContext;
import org.commcare.cases.query.ScopeLimitedReferenceRequestCache;
import org.javarosa.core.model.ItemsetBinding;
import org.javarosa.core.model.SelectChoice;
import org.javarosa.core.model.condition.EvaluationContext;
import org.javarosa.core.model.instance.DataInstance;
import org.javarosa.core.model.instance.FormInstance;
import org.javarosa.core.model.instance.TreeElement;
import org.javarosa.core.model.instance.TreeReference;
import org.javarosa.core.model.trace.ReducingTraceReporter;
import org.javarosa.xpath.XPathException;
import org.javarosa.xpath.analysis.AnalysisInvalidException;
import org.javarosa.xpath.analysis.TreeReferenceAccumulatingAnalyzer;

import java.util.HashSet;
import java.util.Set;
import java.util.Vector;

import javax.annotation.Nullable;

import datadog.trace.api.Trace;

// helper class for common functions related to @code{ItemsetBinding}
public class ItemSetUtils {

    public static void populateDynamicChoices(ItemsetBinding itemset,
                                              EvaluationContext evaluationContext) {
        populateDynamicChoices(itemset, null, evaluationContext, null, false);
    }

    /**
     * Identify the itemset in the backend model, and create a set of SelectChoice
     * objects at the current question reference based on the data in the model.
     *
     * Will modify the itemset binding to contain the relevant choices
     *
     * @param itemset The binding for an itemset, where the choices will be populated
     * @param curQRef A reference to the current question's element, which will be
     *                used to determine the values to be chosen from.
     */
    public static void populateDynamicChoices(ItemsetBinding itemset, @Nullable TreeReference curQRef,
                                              EvaluationContext evaluationContext, @Nullable FormInstance mainInstance, boolean profileEnabled) {
        DataInstance formInstance;
        if (itemset.nodesetRef.getInstanceName() != null) {
            formInstance = evaluationContext.getInstance(itemset.nodesetRef.getInstanceName());
            if (formInstance == null) {
                throw new XPathException("Instance " + itemset.nodesetRef.getInstanceName() + " not found");
            }
        } else {
            formInstance = mainInstance;
        }

        if (formInstance == null) {
            throw new XPathException("No instance definition available to populate items found at '" + itemset.nodesetRef + "'");
        }

        EvaluationContext ec;
        if (curQRef == null) {
            ec = evaluationContext;
        } else {
            ec = new EvaluationContext(evaluationContext, itemset.contextRef.contextualize(curQRef));
        }

        ReducingTraceReporter reporter = null;
        if (profileEnabled) {
            reporter = new ReducingTraceReporter(false);
            ec.setDebugModeOn(reporter);
        }

        ec = getPotentiallyLimitedScopeContext(ec, itemset);

        Vector<TreeReference> matches = itemset.nodesetExpr.evalNodeset(formInstance, ec);

        if (reporter != null) {
            InstrumentationUtils.printAndClearTraces(reporter, "itemset expansion");
        }

        if (matches == null) {
            String instanceName = itemset.nodesetRef.getInstanceName();
            if (instanceName == null) {
                // itemset references a path rooted in the main instance
                throw new XPathException("No items found at '" + itemset.nodesetRef + "'");
            } else {
                // itemset references a path rooted in a lookup table
                throw new XPathException("Make sure the '" + instanceName +
                        "' lookup table is available, and that its contents are accessible to the current user.");
            }
        }

        Vector<SelectChoice> choices = new Vector<>();
        //Escalate the new context if our result set is substantial, this will prevent reverting
        //from a bulk read mode to a scanned read mode
        QueryContext newContext = ec.getCurrentQueryContext()
                .checkForDerivativeContextAndReturn(matches.size());
        ec.setQueryContext(newContext);

        for (int i = 0; i < matches.size(); i++) {
            choices.addElement(buildSelectChoice(matches.elementAt(i), itemset, formInstance,
                    mainInstance, ec, i));
        }
        if (reporter != null) {
            InstrumentationUtils.printAndClearTraces(reporter, "ItemSet Field Population");
        }

        itemset.setChoices(choices);
    }

    /**
     * Returns an evaluation context which can be used to evaluate the itemset's references, and
     * if possible will be more efficient than the base context provided through static analysis
     * of the itemset expressions.
     */
    private static EvaluationContext getPotentiallyLimitedScopeContext(EvaluationContext questionContext,
                                                                       ItemsetBinding itemset) {
        Set<TreeReference> references;
        try {
            references = pullAllReferencesFromItemset(questionContext, itemset);
        } catch (AnalysisInvalidException e) {
            return questionContext;
        }

        EvaluationContext newContext = questionContext.spawnWithCleanLifecycle();

        QueryContext isolatedContext = newContext.getCurrentQueryContext();
        ScopeLimitedReferenceRequestCache cache = isolatedContext.getQueryCache(ScopeLimitedReferenceRequestCache.class);
        cache.addTreeReferencesToLimitedScope(references);
        return newContext;
    }

    /**
     * Tries to get all of the absolute tree references which are referenced in the itemset, either in
     * the nodeset calculation, or the individual (label, value, etc...) itemset element calculations.
     *
     * If a value is returned, that value should contain all tree references which will need to be
     * evaluated to produce the itemset output
     *
     * @throws AnalysisInvalidException If the itemset's references could not be fully understood
     *                                  or qualified through static evaluation
     */
    private static Set<TreeReference> pullAllReferencesFromItemset(EvaluationContext questionContext, ItemsetBinding itemset)
            throws AnalysisInvalidException {


        Set<TreeReference> references = getAccumulatedReferencesOrThrow(questionContext, itemset.nodesetRef);

        EvaluationContext itemsetSubexpressionContext = new EvaluationContext(questionContext, itemset.nodesetRef);

        references.addAll(getAccumulatedReferencesOrThrow(itemsetSubexpressionContext, itemset.labelRef));
        references.addAll(getAccumulatedReferencesOrThrow(itemsetSubexpressionContext, itemset.valueRef));
        references.addAll(getAccumulatedReferencesOrThrow(itemsetSubexpressionContext, itemset.sortRef));

        return references;
    }

    private static Set<TreeReference> getAccumulatedReferencesOrThrow(EvaluationContext subContext,
                                                                      TreeReference newRef) throws AnalysisInvalidException {
        if (newRef == null) {
            return new HashSet<>();
        }
        TreeReferenceAccumulatingAnalyzer analyzer = new TreeReferenceAccumulatingAnalyzer(subContext);

        Set<TreeReference> newReferences = analyzer.accumulate(newRef);

        if (newReferences == null) {
            throw AnalysisInvalidException.INSTANCE_ITEMSET_ACCUM_FAILURE;
        }
        return newReferences;
    }

    // Builds select choices for a ItemsetBinding @param{itemset} by evaulating it against the given EvaluationContext @param{ec}
    private static SelectChoice buildSelectChoice(TreeReference choiceRef, ItemsetBinding itemset,
                                                  DataInstance formInstance, @Nullable FormInstance mainInstance, EvaluationContext ec, int index) {

        EvaluationContext subContext = new EvaluationContext(ec, choiceRef);

        String label = itemset.labelExpr.evalReadable(formInstance, subContext);

        String value = null;
        TreeElement copyNode = null;

        if (itemset.copyMode && mainInstance != null) {
            copyNode = mainInstance.resolveReference(itemset.copyRef.contextualize(choiceRef));
        }

        if (itemset.valueRef != null) {
            value = itemset.valueExpr.evalReadable(formInstance, subContext);
        }

        SelectChoice choice = new SelectChoice(label, value != null ? value : "dynamic:" + index,
                itemset.labelIsItext);

        choice.setIndex(index);

        if (itemset.copyMode) {
            choice.copyNode = copyNode;
        }

        if (itemset.sortRef != null) {
            String evaluatedSortProperty = itemset.sortExpr.evalReadable(formInstance, subContext);
            choice.setSortProperty(evaluatedSortProperty);
        }
        return choice;
    }

    // Get index of a value for the given itemset,
    // return -1 if value is not present in itemset
    public static int getIndexOf(ItemsetBinding itemsetBinding, String value) {
        for (int i = 0; i < itemsetBinding.getChoices().size(); i++) {
            if (itemsetBinding.getChoices().get(i).getValue().contentEquals(value)) {
                return i;
            }
        }
        return -1;
    }

    // returns labels corresponding to choices associated with the given itemsetBinding
    public static String[] getChoiceLabels(ItemsetBinding itemsetBinding) {
        Vector<SelectChoice> selectChoices = itemsetBinding.getChoices();
        String[] choiceLabels = new String[selectChoices.size()];
        for (int i = 0; i < selectChoices.size(); i++) {
            SelectChoice selectChoice = selectChoices.get(i);
            choiceLabels[i] = selectChoice.getLabelInnerText();
        }
        return choiceLabels;
    }
}
