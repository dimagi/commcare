package org.javarosa.xform.schema;

import org.javarosa.xform.parse.XFormParseException;
import org.javarosa.xform.parse.XFormParserReporter;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

/**
 * @author ctsims
 */
public class JSONReporter extends XFormParserReporter {
    final ArrayList<JSONObject> problems = new ArrayList<>();

    boolean passedValidation = false;
    private String failureReason;
    private boolean failureExpected;

    public JSONReporter() {

    }

    public void setPassed() {
        passedValidation = true;
    }

    public void setFailed(XFormParseException e) {
         this.passedValidation = false;
         this.failureExpected = true;
         this.failureReason = e.getMessage();
    }

    public void setFailed(Exception e) {
        this.passedValidation = false;
        this.failureExpected =false;
        this.failureReason = e.getMessage();
    }

    @Override
    public void warning(String type, String message, String xmlLocation) {
        JSONObject problem = new JSONObject();
        problem.put("type", type);
        problem.put("message",  message);
        if(xmlLocation != null) {
            problem.put("xml_location",  xmlLocation);
        }
        problem.put("fatal", false);
        problems.add(problem);
    }

    @Override
    public void error(String message) {
        JSONObject problem = new JSONObject();
        problem.put("type", TYPE_ERROR);
        problem.put("message",  message);
        problem.put("fatal", false);
        problems.add(problem);
    }

    public String generateJSONReport() {
        JSONObject report = new JSONObject();
        report.put("validated", passedValidation);
        if(!passedValidation) {
            report.put("fatal_error", failureReason);
            report.put("fatal_error_expected", failureExpected);
        }

        JSONArray problem = new JSONArray();
        for(JSONObject error : problems) {
            problem.put(error);
        }

        report.put("problems", problems);

        return report.toString();
    }
}
