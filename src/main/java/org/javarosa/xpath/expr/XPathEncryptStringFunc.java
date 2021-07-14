package org.javarosa.xpath.expr;


import org.commcare.util.EncryptionUtils;
import org.javarosa.core.model.condition.EvaluationContext;
import org.javarosa.core.model.instance.DataInstance;
import org.javarosa.xpath.XPathException;
import org.javarosa.xpath.parser.XPathSyntaxException;

import static org.commcare.util.EncryptionUtils.encrypt;

public class XPathEncryptStringFunc extends XPathFuncExpr {
    public static final String NAME = "encrypt-string";
    private static final int EXPECTED_ARG_COUNT = 3;

    public XPathEncryptStringFunc() {
        name = NAME;
        expectedArgCount = EXPECTED_ARG_COUNT;
    }

    public XPathEncryptStringFunc(XPathExpression[] args) throws XPathSyntaxException {
        super(NAME, args, EXPECTED_ARG_COUNT, true);
    }

    @Override
    public Object evalBody(DataInstance model, EvaluationContext evalContext, Object[] evaluatedArgs) {
        return encryptString(evaluatedArgs[0], evaluatedArgs[1], evaluatedArgs[2]);
    }

    /**
     * Encrypt a message with the given algorithm and key.
     *
     * @param message   a message to be encrypted
     * @param key       the key used for encryption
     * @param algorithm the encryption algorithm to use
     */
    private static String encryptString(Object o1, Object o2, Object o3) {
        String message = FunctionUtils.toString(o1);
        String key = FunctionUtils.toString(o2);
        String algorithm = FunctionUtils.toString(o3);

        if (!algorithm.equals("AES")) {
            throw new XPathException("Unknown algorithm \"" + algorithm +
                    "\" for " + NAME);
        }

        try {
            return encrypt(message, key);
        } catch (EncryptionUtils.EncryptionException e) {
            throw new XPathException(e);
        }
    }
}
