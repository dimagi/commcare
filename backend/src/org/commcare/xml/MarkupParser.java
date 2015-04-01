package org.commcare.xml;

import java.io.IOException;

import org.commcare.suite.model.DetailField.Builder;
import org.javarosa.xml.ElementParser;
import org.javarosa.xml.util.InvalidStructureException;
import org.kxml2.io.KXmlParser;
import org.xmlpull.v1.XmlPullParserException;

public class MarkupParser extends ElementParser<Integer> {

    Builder builder;

    public MarkupParser(Builder builder, KXmlParser parser) {
        super(parser);
        this.builder = builder;
    }

    public Integer parse() throws InvalidStructureException, IOException, XmlPullParserException {

        parser.nextTag();

        checkNode("css");
        String id = parser.getAttributeValue(null, "id");
        builder.setCssID(id);

        //exit grid block
        parser.nextTag();

        return new Integer(1);
    }
}
