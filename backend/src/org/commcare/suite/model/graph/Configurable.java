package org.commcare.suite.model.graph;

import java.util.Enumeration;

import org.javarosa.model.Text;

/**
 * Interface to be implemented by any classes in this package that store configuration data using a String => Text mapping.
 *
 * @author jschweers
 */
public interface Configurable {

    public Enumeration getConfigurationKeys();

    public Text getConfiguration(String key);

    public void setConfiguration(String key, Text value);

}
