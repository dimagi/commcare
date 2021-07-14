package org.javarosa.xpath;

import java.text.MessageFormat;

public class XPathException extends RuntimeException {

    // A reference to the "Source" of this message helpful
    // for tracking down where the invalid xpath was declared
    private String sourceRef;
    private String prefix;

    public XPathException() {
    }

    public XPathException(String s) {
        super(s);
    }

    public XPathException(Throwable cause) {
        super(cause);
    }

    public void setMessagePrefix(String prefix){
        this.prefix = prefix;
    }

    public void setSource(String source) {
        this.sourceRef = source;
    }

    public String getSource() {
        return sourceRef;
    }

    @Override
    public String getMessage() {
        if (prefix != null) {
            return MessageFormat.format("{0}\n{1}", prefix, super.getMessage());
        }
        if (sourceRef != null) {
            return MessageFormat.format(
                "The problem was located in {0}:\n{1}", sourceRef, super.getMessage()
            );
        } else {
            return super.getMessage();
        }
    }
}
