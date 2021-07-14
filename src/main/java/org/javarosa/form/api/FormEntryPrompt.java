package org.javarosa.form.api;

import org.javarosa.core.model.Constants;
import org.javarosa.core.model.FormDef;
import org.javarosa.core.model.FormIndex;
import org.javarosa.core.model.ItemsetBinding;
import org.javarosa.core.model.QuestionDef;
import org.javarosa.core.model.QuestionString;
import org.javarosa.core.model.SelectChoice;
import org.javarosa.core.model.condition.Constraint;
import org.javarosa.core.model.condition.EvaluationContext;
import org.javarosa.core.model.condition.pivot.ConstraintHint;
import org.javarosa.core.model.condition.pivot.UnpivotableExpressionException;
import org.javarosa.core.model.data.IAnswerData;
import org.javarosa.core.model.data.SelectMultiData;
import org.javarosa.core.model.data.SelectOneData;
import org.javarosa.core.model.data.helper.Selection;
import org.javarosa.core.model.instance.TreeElement;
import org.javarosa.core.model.instance.TreeReference;
import org.javarosa.core.services.Logger;
import org.javarosa.core.util.NoLocalizedTextException;
import org.javarosa.core.util.UnregisteredLocaleException;
import org.javarosa.xform.parse.XFormParser;

import java.util.Vector;

import datadog.trace.api.Trace;

/**
 * This class gives you all the information you need to display a question when
 * your current FormIndex references a QuestionEvent.
 *
 * @author Yaw Anokwa
 */
public class FormEntryPrompt extends FormEntryCaption {

    TreeElement mTreeElement;
    Vector<SelectChoice> populatedDynamicChoices;

    /**
     * This empty constructor exists for convenience of any supertypes of this prompt
     */
    protected FormEntryPrompt() {
    }

    /**
     * Creates a FormEntryPrompt for the element at the given index in the form.
     */
    public FormEntryPrompt(FormDef form, FormIndex index) {
        super(form, index);
        if (!(element instanceof QuestionDef)) {
            throw new IllegalArgumentException("FormEntryPrompt can only be created for QuestionDef elements");
        }
        this.mTreeElement = form.getMainInstance().resolveReference(index.getReference());
    }

    public int getControlType() {
        return getQuestion().getControlType();
    }

    public int getDataType() {
        return mTreeElement.getDataType();
    }

    //note: code overlap with FormDef.copyItemsetAnswer
    public IAnswerData getAnswerValue() {
        QuestionDef q = getQuestion();

        ItemsetBinding itemset = q.getDynamicChoices();
        if (itemset != null) {
            if (itemset.valueRef != null) {
                Vector<SelectChoice> choices = getSelectChoices();
                Vector<String> preselectedValues = new Vector<>();

                //determine which selections are already present in the answer
                if (itemset.copyMode) {
                    TreeReference destRef = itemset.getDestRef().contextualize(mTreeElement.getRef());
                    Vector<TreeReference> subNodes = form.getEvaluationContext().expandReference(destRef);
                    for (int i = 0; i < subNodes.size(); i++) {
                        TreeElement node = form.getMainInstance().resolveReference(subNodes.elementAt(i));
                        String value = itemset.getRelativeValue().evalReadable(form.getMainInstance(), new EvaluationContext(form.getEvaluationContext(), node.getRef()));
                        preselectedValues.addElement(value);
                    }
                } else {
                    Vector<Selection> sels = new Vector<>();
                    IAnswerData data = mTreeElement.getValue();
                    if (data instanceof SelectMultiData) {
                        sels = (Vector<Selection>)data.getValue();
                    } else if (data instanceof SelectOneData) {
                        sels = new Vector<>();
                        sels.addElement((Selection)data.getValue());
                    }
                    for (int i = 0; i < sels.size(); i++) {
                        preselectedValues.addElement(sels.elementAt(i).xmlValue);
                    }
                }

                //populate 'selection' with the corresponding choices (matching 'value') from the dynamic choiceset
                Vector<Selection> selection = new Vector<>();
                for (int i = 0; i < preselectedValues.size(); i++) {
                    String value = preselectedValues.elementAt(i);
                    SelectChoice choice = null;
                    for (int j = 0; j < choices.size(); j++) {
                        SelectChoice ch = choices.elementAt(j);
                        if (value.equals(ch.getValue())) {
                            choice = ch;
                            break;
                        }
                    }
                    //if it's a dynamic question, then there's a good choice what they selected last time
                    //will no longer be an option this go around
                    if (choice != null) {
                        selection.addElement(choice.selection());
                    }
                }

                //convert to IAnswerData
                if (selection.size() == 0) {
                    return null;
                } else if (q.getControlType() == Constants.CONTROL_SELECT_MULTI) {
                    return new SelectMultiData(selection);
                } else if (q.getControlType() == Constants.CONTROL_SELECT_ONE) {
                    return new SelectOneData(selection.elementAt(0)); //do something if more than one selected?
                } else {
                    throw new RuntimeException("can't happen");
                }
            } else {
                return null; //cannot map up selections without <value>
            }
        } else { //static choices
            return mTreeElement.getValue();
        }
    }


    public String getAnswerText() {
        IAnswerData data = this.getAnswerValue();

        if (data == null)
            return null;
        else {
            String text;

            //csims@dimagi.com - Aug 11, 2010 - Added special logic to
            //capture and display the appropriate value for selections
            //and multi-selects.
            if (data instanceof SelectOneData) {
                text = this.getSelectItemText((Selection)data.getValue());
            } else if (data instanceof SelectMultiData) {
                String returnValue = "";
                Vector<Selection> values = (Vector<Selection>)data.getValue();
                for (Selection value : values) {
                    returnValue += this.getSelectItemText(value) + " ";
                }
                text = returnValue;
            } else {
                text = data.getDisplayText();
            }

            if (getControlType() == Constants.CONTROL_SECRET) {
                String obfuscated = "";
                for (int i = 0; i < text.length(); ++i) {
                    obfuscated += "*";
                }
                text = obfuscated;
            }
            return text;
        }
    }

    public String getConstraintText(){
        return getConstraintText(null);
    }

    public String getConstraintText(IAnswerData attemptedValue) {
        // new constraint spec uses "alert" form XForm spec 8.2.4
        // http://www.w3.org/TR/xforms/#ui-commonelems
        String newConstraintMsg =  this.localizeText(getQuestion().getQuestionString(XFormParser.CONSTRAINT_ELEMENT));
        if(newConstraintMsg != null){
            return newConstraintMsg;
        }
        //default to old logic
        return getConstraintText(null, attemptedValue);
    }

    public String getConstraintText(String textForm, IAnswerData attemptedValue) {
        // if doesn't exist, use the old logic
        if (mTreeElement.getConstraint() == null) {
            return null;
        } else {
            EvaluationContext ec = new EvaluationContext(form.exprEvalContext, mTreeElement.getRef());
            if (textForm != null) {
                ec.setOutputTextForm(textForm);
            }
            if (attemptedValue != null) {
                ec.isConstraint = true;
                ec.candidateValue = attemptedValue;
            }
            return mTreeElement.getConstraint().getConstraintMessage(ec, form.getMainInstance(), textForm);
        }
    }

    public Vector<SelectChoice> getSelectChoices() { return getSelectChoices(true); }

    @Trace
    public Vector<SelectChoice> getSelectChoices(boolean shouldAttemptDynamicPopulation) {
        QuestionDef q = getQuestion();
        ItemsetBinding itemset = q.getDynamicChoices();
        if (itemset != null) {
            if (populatedDynamicChoices == null && shouldAttemptDynamicPopulation) {
                form.populateDynamicChoices(itemset, mTreeElement.getRef());
                populatedDynamicChoices = itemset.getChoices();
            }
            return populatedDynamicChoices;
        } else {
            // static choices
            return q.getChoices();
        }
    }

    public Vector<SelectChoice> getOldSelectChoices() {
        return getSelectChoices(false);
    }

    /**
     * @return If this prompt has all of the same display content as a previous prompt that had
     * the given question text and select choices
     */
    public boolean hasSameDisplayContent(String questionTextForOldPrompt,
                                         Vector<SelectChoice> selectChoicesForOldPrompt) {
        return questionTextIsUnchanged(questionTextForOldPrompt) &&
                selectChoicesAreUnchanged(selectChoicesForOldPrompt);
    }

    private boolean selectChoicesAreUnchanged(Vector<SelectChoice> choicesForOld) {
        Vector<SelectChoice> choicesForThis = getSelectChoices();
        if (choicesForOld == null) {
            return choicesForThis == null;
        } else {
            return choicesForOld.equals(choicesForThis);
        }
    }

    private boolean questionTextIsUnchanged(String oldQuestionText) {
        String newQuestionText = getQuestionText();
        if (newQuestionText == null) {
            return oldQuestionText == null;
        } else {
            return newQuestionText.equals(oldQuestionText);
        }
    }

    public boolean isRequired() {
        return mTreeElement.isRequired();
    }

    public boolean isReadOnly() {
        return !mTreeElement.isEnabled();
    }

    public QuestionDef getQuestion() {
        return (QuestionDef)element;
    }

    /**
     * Get hint text (helper text displayed along with question).
     * ONLY RELEVANT to Question elements!
     * Will throw runTimeException if this is called for anything that isn't a Question.
     * Returns null if no hint text is available
     */
    public String getHintText() {
        if (!(element instanceof QuestionDef)) {
            throw new RuntimeException("Can't get HintText for Elements that are not Questions!");
        }

        QuestionDef qd = (QuestionDef)element;
        return localizeText(qd.getQuestionString(XFormParser.HINT_ELEMENT));
    }

    /**
     * Determine if this prompt has any help, whether text or multimedia.
     */
    public boolean hasHelp() {

        if(this.getQuestion().getQuestionString(XFormParser.HELP_ELEMENT) != null){
            return true;
        }

        Vector<String> forms = new Vector<>();
        forms.addElement(TEXT_FORM_AUDIO);
        forms.addElement(TEXT_FORM_IMAGE);
        forms.addElement(TEXT_FORM_VIDEO);
        for (String form : forms) {
            String media = getHelpMultimedia(form);
            if (media != null && !"".equals(media)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Get help text (helper text displayed when requested by user).
     * ONLY RELEVANT to Question elements!
     * Will throw runTimeException if this is called for anything that isn't a Question.
     * Returns null if no hint text is available
     */
    public String getHelpText() {
        if (!(element instanceof QuestionDef)) {
            throw new RuntimeException("Can't get HelpText for Elements that are not Questions!");
        }

        QuestionDef qd = (QuestionDef)element;
        return localizeText(qd.getQuestionString(XFormParser.HELP_ELEMENT));
    }

    /**
     * Helper for getHintText, getHelpText, getConstraintText. Tries to localize text form textID,
     * falls back to innerText if not available.
     * It may throw XPathException.
     */
    private String localizeText(QuestionString mQuestionString) {

        if(mQuestionString == null){return null;}

        String fallbackText = mQuestionString.getTextFallback();
        try {
            if (mQuestionString.getTextId() != null) {
                fallbackText = getQuestionText(mQuestionString.getTextId());
            } else {
                fallbackText = substituteStringArgs(mQuestionString.getTextInner());
            }
        } catch (NoLocalizedTextException nlt) {
            //use fallback
        } catch (UnregisteredLocaleException ule) {
            System.err.println("Warning: No Locale set yet (while attempting to localizeText())");
        }

        return fallbackText;
    }

    /**
     * Get a particular type of multimedia help associated with this question.
     *
     * @param form TEXT_FORM_AUDIO, etc.
     */
    public String getHelpMultimedia(String form) {
        if (!(element instanceof QuestionDef)) {
            throw new RuntimeException("Can't get HelpText for Elements that are not Questions!");
        }
        String textID = ((QuestionDef)element).getHelpTextID();
        if (textID == null) {
            return null;
        }
        return this.getSpecialFormQuestionText(textID, form);
    }


    /**
     * Attempts to return the specified Item (from a select or 1select) text.
     * Will check for text in the following order:<br/>
     * Localized Text (long form) -> Localized Text (no special form) <br />
     * If no textID is available, method will return this item's labelInnerText.
     *
     * @param sel the selection (item), if <code>null</code> will throw a IllegalArgumentException
     * @return Question Text.  <code>null</code> if no text for this element exists (after all fallbacks).
     * @throws IllegalArgumentException if Selection is <code>null</code>
     */
    public String getSelectItemText(Selection sel) {
        //throw tantrum if this method is called when it shouldn't be or sel==null
        if (!(getFormElement() instanceof QuestionDef))
            throw new RuntimeException("Can't retrieve question text for non-QuestionDef form elements!");
        if (sel == null) throw new IllegalArgumentException("Cannot use null as an argument!");

        //Just in case the selection hasn't had a chance to be initialized yet.
        if (sel.index == -1) {
            sel.attachChoice(this.getQuestion());
        }

        //check for the null id case and return labelInnerText if it is so.
        String tid = sel.choice.getTextID();
        if (tid == null || "".equals(tid)) {
            return substituteStringArgs(sel.choice.getLabelInnerText());
        }

        //otherwise check for 'long' form of the textID, then for the default form and return
        String returnText;
        returnText = getIText(tid, "long");
        if (returnText == null) returnText = getIText(tid, null);

        return substituteStringArgs(returnText);
    }

    public String getSelectChoiceText(SelectChoice selection) {
        return getSelectItemText(selection.selection());
    }

    /**
     * This method is generally used to retrieve special forms for a
     * (select or 1select) item, e.g. "audio", "video", etc.
     *
     * @param sel  - The Item whose text you're trying to retrieve.
     * @param form - Special text form of Item you're trying to retrieve.
     * @return Special Form Text. <code>null</code> if no text for this element exists (with the specified special form).
     * @throws IllegalArgumentException if <code>sel == null</code>
     */
    public String getSpecialFormSelectItemText(Selection sel, String form) {
        if (sel == null)
            throw new IllegalArgumentException("Cannot use null as an argument for Selection!");

        //Just in case the selection hasn't had a chance to be initialized yet.
        if (sel.index == -1) {
            sel.attachChoice(this.getQuestion());
        }

        String textID = sel.choice.getTextID();
        if (textID == null || textID.equals("")) return null;

        String returnText = getIText(textID, form);

        return substituteStringArgs(returnText);


    }

    public String getSpecialFormSelectChoiceText(SelectChoice sel, String form) {
        return getSpecialFormSelectItemText(sel.selection(), form);
    }

    public String getSelectItemMarkdownText(SelectChoice sel){
        return getSpecialFormSelectChoiceText(sel, FormEntryCaption.TEXT_FORM_MARKDOWN);
    }

    public void requestConstraintHint(ConstraintHint hint) throws UnpivotableExpressionException {
        //NOTE: Technically there's some rep exposure, here. People could use this mechanism to expose the instance.
        //We could hide it by dispatching hints through a final abstract class instead.
        Constraint c = mTreeElement.getConstraint();
        if (c != null) {
            hint.init(new EvaluationContext(form.exprEvalContext, mTreeElement.getRef()), c.constraint, this.form.getMainInstance());
        } else {
            //can't pivot what ain't there.
            throw new UnpivotableExpressionException();
        }
    }

}

