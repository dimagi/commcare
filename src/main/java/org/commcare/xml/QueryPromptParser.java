package org.commcare.xml;

import org.commcare.suite.model.DisplayUnit;
import org.commcare.suite.model.QueryPrompt;
import org.javarosa.core.model.ItemsetBinding;
import org.javarosa.core.model.instance.TreeReference;
import org.javarosa.xform.parse.ItemSetParsingUtils;
import org.javarosa.xml.util.InvalidStructureException;
import org.javarosa.xml.util.UnfullfilledRequirementsException;
import org.javarosa.xpath.XPathParseTool;
import org.javarosa.xpath.expr.XPathExpression;
import org.javarosa.xpath.parser.XPathSyntaxException;
import org.kxml2.io.KXmlParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;

public class QueryPromptParser extends CommCareElementParser<QueryPrompt> {

    private static final String NAME_PROMPT = "prompt";
    private static final String NAME_DISPLAY = "display";
    private static final String NAME_ITEMSET = "itemset";
    private static final String ATTR_APPEARANCE = "appearance";
    private static final String ATTR_KEY = "key";
    private static final String ATTR_INPUT = "input";
    private static final String ATTR_RECEIVE = "receive";
    private static final String ATTR_HIDDEN = "hidden";
    private static final String ATTR_NODESET = "nodeset";
    private static final String ATTR_DEFAULT = "default";
    private static final String NAME_LABEL = "label";
    private static final String NAME_VALUE = "value";
    private static final String NAME_SORT = "sort";
    private static final String ATTR_REF = "ref";
    private static final String ATTR_ALLOW_BLANK_VALUE = "allow_blank_value";

    public QueryPromptParser(KXmlParser parser) {
        super(parser);
    }

    @Override
    public QueryPrompt parse() throws InvalidStructureException, IOException, XmlPullParserException, UnfullfilledRequirementsException {
        String appearance = parser.getAttributeValue(null, ATTR_APPEARANCE);
        String key = parser.getAttributeValue(null, ATTR_KEY);
        String input = parser.getAttributeValue(null, ATTR_INPUT);
        String receive = parser.getAttributeValue(null, ATTR_RECEIVE);
        String hidden = parser.getAttributeValue(null, ATTR_HIDDEN);
        boolean allowBlankValue = "true".equals(parser.getAttributeValue(null, ATTR_ALLOW_BLANK_VALUE));
        DisplayUnit display = null;
        ItemsetBinding itemsetBinding = null;
        XPathExpression defaultValue = null;

        String defaultValueString = parser.getAttributeValue(null, ATTR_DEFAULT);
        if(defaultValueString != null) {
            try {
                defaultValue = XPathParseTool.parseXPath(defaultValueString);
            } catch (XPathSyntaxException e) {
                InvalidStructureException toThrow = new InvalidStructureException(String.format(
                        "Invalid XPath Expression in QueryPrompt %s",
                        e.getMessage()), parser);
                toThrow.initCause(e);
                throw toThrow;
            }
        }

        while (nextTagInBlock(NAME_PROMPT)) {
            if (NAME_DISPLAY.equals(parser.getName().toLowerCase())) {
                display = parseDisplayBlock();
            } else if (NAME_ITEMSET.equals(parser.getName().toLowerCase())) {
                itemsetBinding = parseItemset();
            }
        }
        return new QueryPrompt(key, appearance, input, receive, hidden, display, itemsetBinding, defaultValue, allowBlankValue);
    }

    private ItemsetBinding parseItemset() throws IOException, XmlPullParserException, InvalidStructureException {
        ItemsetBinding itemset = new ItemsetBinding();
        itemset.contextRef = TreeReference.rootRef();
        String nodesetStr = parser.getAttributeValue(null, ATTR_NODESET);
        ItemSetParsingUtils.setNodeset(itemset, nodesetStr, NAME_ITEMSET);
        while (nextTagInBlock(NAME_ITEMSET)) {
            if (NAME_LABEL.equals(parser.getName())) {
                ItemSetParsingUtils.setLabel(itemset, parser.getAttributeValue(null, ATTR_REF));
            } else if (NAME_VALUE.equals(parser.getName())) {
                ItemSetParsingUtils.setValue(itemset, parser.getAttributeValue(null, ATTR_REF));
            } else if (NAME_SORT.equals(parser.getName())) {
                ItemSetParsingUtils.setSort(itemset, parser.getAttributeValue(null, ATTR_REF));
            }
        }
        return itemset;
    }
}
