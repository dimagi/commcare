package org.commcare.session;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;

import org.commcare.cases.util.StringUtils;
import org.commcare.modern.util.Pair;
import org.commcare.suite.model.QueryPrompt;
import org.commcare.suite.model.RemoteQueryDatum;
import org.commcare.suite.model.SessionDatum;
import org.javarosa.core.model.ItemsetBinding;
import org.javarosa.core.model.condition.EvaluationContext;
import org.javarosa.core.model.instance.ExternalDataInstance;
import org.javarosa.core.model.instance.TreeElement;
import org.javarosa.core.model.utils.ItemSetUtils;
import org.javarosa.core.util.OrderedHashtable;
import org.javarosa.xml.ElementParser;
import org.javarosa.xml.TreeElementParser;
import org.javarosa.xml.util.InvalidStructureException;
import org.javarosa.xml.util.UnfullfilledRequirementsException;
import org.javarosa.xpath.XPathException;
import org.javarosa.xpath.expr.FunctionUtils;
import org.javarosa.xpath.expr.XPathExpression;
import org.kxml2.io.KXmlParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Hashtable;

import javax.annotation.Nullable;

/**
 * Manager for remote query datums; get/answer user prompts and build
 * resulting query url.
 *
 * @author Phillip Mates (pmates@dimagi.com)
 */
public class RemoteQuerySessionManager {
    // used to parse multi-select choices
    public static final String MULTI_SELECT_DELIMITER = "#,#";

    private final RemoteQueryDatum queryDatum;
    private final EvaluationContext evaluationContext;
    private final Hashtable<String, String> userAnswers =
            new Hashtable<>();
    private final ArrayList<String> supportedPrompts;

    private RemoteQuerySessionManager(RemoteQueryDatum queryDatum,
                                      EvaluationContext evaluationContext,
                                      ArrayList<String> supportedPrompts) throws XPathException {
        this.queryDatum = queryDatum;
        this.evaluationContext = evaluationContext;
        this.supportedPrompts = supportedPrompts;
        initUserAnswers();
    }

    private void initUserAnswers() throws XPathException {
        OrderedHashtable<String, QueryPrompt> queryPrompts = queryDatum.getUserQueryPrompts();
        for (Enumeration en = queryPrompts.keys(); en.hasMoreElements(); ) {
            String promptId = (String)en.nextElement();
            QueryPrompt prompt = queryPrompts.get(promptId);

            if (isPromptSupported(prompt) && prompt.getDefaultValueExpr() != null) {
                userAnswers.put(prompt.getKey(), FunctionUtils.toString(prompt.getDefaultValueExpr().eval(evaluationContext)));
            }

        }
    }

    public static RemoteQuerySessionManager buildQuerySessionManager(CommCareSession session,
                                                                     EvaluationContext sessionContext,
                                                                     ArrayList<String> supportedPrompts) throws XPathException {
        SessionDatum datum;
        try {
            datum = session.getNeededDatum();
        } catch (IllegalStateException e) {
            // tried loading session info when it wasn't there
            return null;
        }
        if (datum instanceof RemoteQueryDatum) {
            return new RemoteQuerySessionManager((RemoteQueryDatum)datum, sessionContext, supportedPrompts);
        } else {
            return null;
        }
    }

    public OrderedHashtable<String, QueryPrompt> getNeededUserInputDisplays() {
        return queryDatum.getUserQueryPrompts();
    }

    public Hashtable<String, String> getUserAnswers() {
        return userAnswers;
    }

    public void clearAnswers() {
        userAnswers.clear();
    }

    /**
     * Register a non-null value as an answer for the given key.
     * If value is null, removes the corresponding answer
     */
    public void answerUserPrompt(String key, @Nullable String value) {
        if (value == null) {
            userAnswers.remove(key);
        } else {
            userAnswers.put(key, value);
        }
    }

    public URL getBaseUrl() {
        return queryDatum.getUrl();
    }

    /**
     * @param skipDefaultPromptValues don't apply the default value expressions for query prompts
     * @return filters to be applied to case search uri as query params
     */
    public Multimap<String, String> getRawQueryParams(boolean skipDefaultPromptValues) {
        Multimap<String, String> params = ArrayListMultimap.create();
        Multimap<String, XPathExpression> hiddenQueryValues = queryDatum.getHiddenQueryValues();
        for (String key : hiddenQueryValues.keySet()) {
            for (XPathExpression xpathExpression : hiddenQueryValues.get(key)) {
                String evaluatedExpr = evalXpathExpression(xpathExpression, evaluationContext);
                params.put(key, evaluatedExpr);
            }
        }

        if (!skipDefaultPromptValues) {
            for (Enumeration e = userAnswers.keys(); e.hasMoreElements(); ) {
                String key = (String)e.nextElement();
                String value = userAnswers.get(key);
                if (!(params.containsKey(key) && params.get(key).contains(value))) {
                    if (value != null) {
                        params.put(key, userAnswers.get(key));
                    }
                }
            }
        }
        return params;
    }

    public static String evalXpathExpression(XPathExpression expr,
                                             EvaluationContext evaluationContext) {
        return FunctionUtils.toString(expr.eval(evaluationContext));
    }

    /**
     * @return Data instance built from xml stream or the error message raised during parsing
     */
    public Pair<ExternalDataInstance, String> buildExternalDataInstance(InputStream instanceStream) {
        TreeElement root;
        try {
            KXmlParser baseParser = ElementParser.instantiateParser(instanceStream);
            root = new TreeElementParser(baseParser, 0, queryDatum.getDataId()).parse();
        } catch (InvalidStructureException | IOException
                | XmlPullParserException | UnfullfilledRequirementsException e) {
            e.printStackTrace();
            return new Pair<>(null, e.getMessage());
        }
        return new Pair<>(ExternalDataInstance.buildFromRemote(queryDatum.getDataId(), root, queryDatum.useCaseTemplate()), "");
    }

    /**
     * @return Data instance built from xml root or the error message raised during parsing
     */
    public ExternalDataInstance buildExternalDataInstance(TreeElement root) {
        return ExternalDataInstance.buildFromRemote(queryDatum.getDataId(), root, queryDatum.useCaseTemplate());
    }

    public void populateItemSetChoices(QueryPrompt queryPrompt) {
        EvaluationContext evalContextWithAnswers = evaluationContext.spawnWithCleanLifecycle();
        evalContextWithAnswers.setVariables(userAnswers);
        ItemSetUtils.populateDynamicChoices(queryPrompt.getItemsetBinding(), evalContextWithAnswers);
    }

    // loops over query prompts and validates selection until all selections are valid
    public void refreshItemSetChoices(Hashtable<String, String> userAnswers) {
        OrderedHashtable<String, QueryPrompt> userInputDisplays = getNeededUserInputDisplays();
        boolean dirty = true;
        int index = 0;
        while (dirty) {
            if (index == userInputDisplays.size()) {
                // loop has already run as many times as no of questions and we are still dirty
                throw new RuntimeException("Invalid itemset state encountered while trying to refresh itemset choices");
            }
            dirty = false;
            for (Enumeration en = userInputDisplays.keys(); en.hasMoreElements(); ) {
                String promptId = (String)en.nextElement();
                QueryPrompt queryPrompt = userInputDisplays.get(promptId);
                if (queryPrompt.isSelect()) {
                    String answer = userAnswers.get(promptId);
                    populateItemSetChoices(queryPrompt);
                    String[] selectedChoices = extractSelectChoices(answer);
                    ArrayList<String> validSelectedChoices = new ArrayList<>();
                    for (String selectedChoice : selectedChoices) {
                        if (checkForValidSelectValue(queryPrompt.getItemsetBinding(), selectedChoice)) {
                            validSelectedChoices.add(selectedChoice);
                        } else {
                            dirty = true;
                        }
                    }
                    if (validSelectedChoices.size() > 0) {
                        userAnswers.put(promptId, String.join(RemoteQuerySessionManager.MULTI_SELECT_DELIMITER, validSelectedChoices));
                    } else {
                        // no value
                        userAnswers.remove(promptId);
                    }
                }
            }
            index++;
        }
    }

    public boolean isPromptSupported(QueryPrompt queryPrompt) {
        return queryPrompt.getInput() == null || supportedPrompts.indexOf(queryPrompt.getInput()) != -1;
    }

    // checks if @param{value} is one of the select choices give in @param{items}
    private boolean checkForValidSelectValue(ItemsetBinding itemsetBinding, String value) {
        // blank is always a valid choice
        if (StringUtils.isEmpty(value)) {
            return true;
        }
        return ItemSetUtils.getIndexOf(itemsetBinding, value) != -1;
    }

    public boolean doDefaultSearch() {
        return queryDatum.doDefaultSearch();
    }

    // Converts a string containing space separated list of select choices
    // into a string array of individual choices
    public static String[] extractSelectChoices(String answer) {
        if (answer == null) {
            return new String[]{};
        }
        return answer.split(MULTI_SELECT_DELIMITER);
    }
}
