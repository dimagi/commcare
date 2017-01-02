package org.javarosa.xpath.expr;

import org.javarosa.core.model.condition.EvaluationContext;
import org.javarosa.core.model.instance.DataInstance;
import org.javarosa.xpath.parser.XPathSyntaxException;

/**
 * Conditional function that is an alternative to nested if-statements
 *
 * @author Phillip Mates (pmates@dimagi.com)
 */
public class XPathCondFunc extends XPathFuncExpr {
    public static final String NAME = "cond";
    // expects at least 3 arguments
    private static final int EXPECTED_ARG_COUNT = -1;

    public XPathCondFunc() {
        name = NAME;
        expectedArgCount = EXPECTED_ARG_COUNT;
    }

    public XPathCondFunc(XPathExpression[] args) throws XPathSyntaxException {
        super(NAME, args, EXPECTED_ARG_COUNT, false);
    }

    @Override
    protected void validateArgCount() throws XPathSyntaxException {
        if (args.length < 3) {
            throw new XPathSyntaxException(name + "() function requires at least 3 arguments. " + args.length + " arguments provided.");
        } else if (args.length % 2 != 1) {
            throw new XPathSyntaxException(name + "() function requires an odd number of arguments. " + args.length + " arguments provided.");
        }
    }

    @Override
    public Object evalBody(DataInstance model, EvaluationContext evalContext) {
        for (int i = 0; i < args.length - 2; i+=2) {
            if (FunctionUtils.toBoolean(args[i].eval(model, evalContext))) {
                return args[i+1].eval(model, evalContext);
            }
        }

        return args[args.length-1].eval(model, evalContext);
    }

    @Override
    public String getDocumentation() {
        return getDocHeader()
                + "Behavior: Takes a set of test/expression pairs along with a default expression. The test conditions are evaluated in sequence and once one returns to true, 'cond' evaluates and returns the value of the corresponding expression and doesn't evaluate any of the other tests or expressions. If none of the test conditions evaluate to true, the default expression is returned.\n"
                + "Return: Will return the value corresponding to one of the expression or the default expression.\n"
                + "Arguments:  Any number of test condition & expression pairs along with a default expression.\n"
                + "Syntax: cond(first_condition, value_if_first_true, second_condition, value_if_second_true, ..., default_value)\n"
                + "Example:  This function is useful for avoiding nested if-statements. Instead of writing if(data/mother_is_pregnant = \"yes\", \"Is Pregnant\", if(/data/mother_has_young_children = \"yes\", \"Newborn Child Care\", \"Not Tracked\")) you can write cond(data/mother_is_pregnant = \"yes\", \"Is Pregnant\", /data/mother_has_young_children = \"yes\", \"Newborn Child Care\", \"Not Tracked\")\n"
                + "Since: This function is available on CommCare 2.31 and later";
    }
}
