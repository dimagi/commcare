/**
 *
 */
package org.commcare.xml;

import java.io.IOException;
import java.util.Vector;

import org.commcare.suite.model.Action;
import org.commcare.suite.model.Detail;
import org.commcare.suite.model.DetailField;
import org.javarosa.model.DetailTemplate;
import org.javarosa.model.DisplayUnit;
import org.javarosa.model.Text;
import org.javarosa.xml.ElementParser;
import org.javarosa.xml.TextParser;
import org.javarosa.xml.util.InvalidStructureException;
import org.javarosa.core.model.Constants;
import org.javarosa.core.util.OrderedHashtable;
import org.javarosa.xpath.XPathParseTool;
import org.javarosa.xpath.parser.XPathSyntaxException;
import org.kxml2.io.KXmlParser;
import org.xmlpull.v1.XmlPullParserException;

/**
 * @author ctsims
 */
public class DetailParser extends ElementParser<Detail> {

    public DetailParser(KXmlParser parser) {
        super(parser);
    }

    public Detail parse() throws InvalidStructureException, IOException, XmlPullParserException {
        checkNode("detail");

        String id = parser.getAttributeValue(null, "id");

        Text background = new Text();

        //First fetch the title
        getNextTagInBlock("detail");
        //inside title, should be a text node or a display node as the child
        checkNode("title");
        getNextTagInBlock("title");
        DisplayUnit title;
        if ("text".equals(parser.getName().toLowerCase())) {
            title = new DisplayUnit(new TextParser(parser).parse(), "", "");
        } else {
            title = parseDisplayBlock();
        }
        Action action = null;

        //Now get the headers and templates.
        Vector<Detail> subdetails = new Vector<Detail>();
        Vector<DetailField> fields = new Vector<DetailField>();
        OrderedHashtable<String, String> variables = new OrderedHashtable<String, String>();

        while (nextTagInBlock("detail")) {
            if ("variables".equals(parser.getName().toLowerCase())) {
                while (nextTagInBlock("variables")) {
                    String function = parser.getAttributeValue(null, "function");
                    if (function == null) {
                        throw new InvalidStructureException("No function in variable declaration for variable " + parser.getName(), parser);
                    }
                    try {
                        XPathParseTool.parseXPath(function);
                    } catch (XPathSyntaxException e) {
                        e.printStackTrace();
                        throw new InvalidStructureException("Invalid XPath function " + function + ". " + e.getMessage(), parser);
                    }
                    variables.put(parser.getName(), function);
                }
                continue;
            }
            if (ActionParser.NAME_ACTION.equalsIgnoreCase(parser.getName())) {
                action = new ActionParser(parser).parse();
                continue;
            }
            DetailField.Builder builder = new DetailField().new Builder();

            if (parser.getName().equals("detail")) {
                subdetails.addElement((new DetailParser(parser)).parse());
            } else {
                checkNode("field");
                //Get the fields
                String sortDefault = parser.getAttributeValue(null, "sort");
                if (sortDefault != null && sortDefault.equals("default")) {
                    builder.setSortOrder(1);
                }
                String relevancy = parser.getAttributeValue(null, "relevant");
                if (relevancy != null) {
                    try {
                        XPathParseTool.parseXPath(relevancy);
                        builder.setRelevancy(relevancy);
                    } catch (XPathSyntaxException e) {
                        e.printStackTrace();
                        throw new InvalidStructureException("Bad XPath Expression {" + relevancy + "}", parser);
                    }
                }
                if (nextTagInBlock("field")) {
                    //style
                    if (parser.getName().toLowerCase().equals("style")) {
                        StyleParser styleParser = new StyleParser(builder, parser);
                        styleParser.parse();
                        //Header
                        GridParser gridParser = new GridParser(builder, parser);
                        gridParser.parse();

                        //exit style block
                        parser.nextTag();
                        parser.nextTag();
                    }
                    checkNode("header");

                    builder.setHeaderHint(getWidth());

                    String form = parser.getAttributeValue(null, "form");
                    builder.setHeaderForm(form == null ? "" : form);

                    parser.nextTag();
                    checkNode("text");
                    Text header = new TextParser(parser).parse();
                    builder.setHeader(header);

                } else {
                    throw new InvalidStructureException("Not enough field entries", parser);
                }
                if (nextTagInBlock("field")) {
                    //Template
                    checkNode("template");

                    builder.setTemplateHint(getWidth());

                    String form = parser.getAttributeValue(null, "form");
                    if (form == null) {
                        form = "";
                    }
                    builder.setTemplateForm(form);

                    parser.nextTag();
                    DetailTemplate template;
                    if (form.equals("graph")) {
                        template = new GraphParser(parser).parse();
                    } else {
                        checkNode("text");
                        try {
                            template = new TextParser(parser).parse();
                        } catch (InvalidStructureException ise) {
                            throw new InvalidStructureException("Error in suite detail with id " + id + " : " + ise.getMessage(), parser);
                        }
                    }
                    builder.setTemplate(template);
                } else {
                    throw new InvalidStructureException("detail <field> with no <template>!", parser);
                }
                if (nextTagInBlock("field")) {
                    //sort details
                    checkNode(new String[]{"sort", "background"});

                    String name = parser.getName().toLowerCase();

                    if (name.equals("sort")) {

                        //So in the past we've been fairly flexible about inputs to attributes and such
                        //in case we want to expand their function in the future. These are limited sets,
                        //and it'd be nice to limit their inputs and fail fast, but that also means
                        //we have to be careful about not changing their input values in-major release
                        //version, so we'll be flexible for now.

                        String order = parser.getAttributeValue(null, "order");
                        if (order != null && order != "") {
                            try {
                                builder.setSortOrder(Integer.parseInt(order));
                            } catch (NumberFormatException nfe) {
                                //see above comment
                            }
                        }
                        String direction = parser.getAttributeValue(null, "direction");
                        if ("ascending".equals(direction)) {
                            builder.setSortDirection(DetailField.DIRECTION_ASCENDING);
                        } else if ("descending".equals(direction)) {
                            builder.setSortDirection(DetailField.DIRECTION_DESCENDING);
                        } else {
                            //see above comment. Also note that this catches the null case,
                            //which will need to be caught specially otherwise
                        }

                        //See if there's a sort type
                        String type = parser.getAttributeValue(null, "type");
                        if ("int".equals(type)) {
                            builder.setSortType(Constants.DATATYPE_INTEGER);
                        } else if ("double".equals(type)) {
                            builder.setSortType(Constants.DATATYPE_DECIMAL);
                        } else if ("string".equals(type)) {
                            builder.setSortType(Constants.DATATYPE_TEXT);
                        } else {
                            //see above comment
                        }


                        //See if this has a text value for the sort
                        if (nextTagInBlock("sort")) {
                            //Make sure the internal element _is_ a text
                            checkNode("text");

                            //Get it if so
                            Text sort = new TextParser(parser).parse();
                            builder.setSort(sort);
                        }
                    } else if (name.equals("background")) {
                        if (nextTagInBlock("background")) {
                            checkNode("text");
                            //Get it if so
                            background = new TextParser(parser).parse();
                            builder.setBackground(background);
                        }
                    }
                }
                fields.addElement(builder.build());
            }
        }

        Detail d = new Detail(id, title, subdetails, fields, variables, action);
        return d;
    }

    private int getWidth() throws InvalidStructureException {
        String width = parser.getAttributeValue(null, "width");
        if (width == null) {
            return -1;
        }
        ;

        //Remove the trailing % sign if any
        if (width.indexOf("%") != -1) {
            width = width.substring(0, width.indexOf("%"));
        }
        return this.parseInt(width);
    }

    private int[] toIntArray(Vector<Integer> vector) {
        int[] ret = new int[vector.size()];
        for (int i = 0; i < ret.length; ++i) {
            ret[i] = vector.elementAt(i).intValue();
        }
        return ret;
    }

    private String[] toStringArray(Vector<String> vector) {
        String[] ret = new String[vector.size()];
        for (int i = 0; i < ret.length; ++i) {
            if (vector.elementAt(i).equals("")) {
                ret[i] = null;
            } else {
                ret[i] = vector.elementAt(i);
            }
        }
        return ret;
    }

}
