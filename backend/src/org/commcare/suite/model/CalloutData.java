package org.commcare.suite.model;

import java.util.Hashtable;
import java.util.Vector;

/**
 * Created by wpride1 on 4/17/15.
 */
public class CalloutData {
    private final String actionName;
    private final String image;
    private final String displayName;
    private Hashtable<String, String> extras = new Hashtable<String, String>();
    private Vector<String> responses = new Vector<String>();


    public CalloutData(String actionName, String image, String displayName, Hashtable<String, String> extras, Vector<String> responses) {
        this.actionName = actionName;
        this.image = image;
        this.displayName = displayName;
        this.extras = extras;
        this.responses = responses;
    }

    public String getImage() {
        return image;
    }

    public String getActionName() {
        return actionName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public Hashtable<String, String> getExtras() {
        return extras;
    }

    public Vector<String> getResponses() {
        return responses;
    }
}
