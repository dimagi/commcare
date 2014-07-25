package org.commcare.suite.model;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.Vector;

import org.commcare.suite.model.graph.GraphData;
import org.commcare.suite.model.graph.PointData;
import org.commcare.suite.model.graph.SeriesData;
import org.javarosa.core.model.condition.EvaluationContext;
import org.javarosa.core.model.instance.AbstractTreeElement;
import org.javarosa.core.model.instance.TreeReference;
import org.javarosa.core.util.externalizable.DeserializationException;
import org.javarosa.core.util.externalizable.Externalizable;
import org.javarosa.core.util.externalizable.PrototypeFactory;
import org.javarosa.xpath.XPathParseTool;
import org.javarosa.xpath.expr.XPathExpression;
import org.javarosa.xpath.parser.XPathSyntaxException;

public class GraphTemplate implements Externalizable, IDetailTemplate {
	private Vector<Series> series;
	private Hashtable<String, Text> configuration;
	
	public GraphTemplate() {
		series = new Vector<Series>();
		configuration = new Hashtable<String, Text>();
	}
	
	public void addSeries(Series s) {
		series.addElement(s);
	}
	
	public Text getConfiguration(String key) {
		return configuration.get(key);
	}
	
	public void setConfiguration(String key, Text value) {
		configuration.put(key, value);
	}
	
	public void readExternal(DataInputStream in, PrototypeFactory pf) throws IOException, DeserializationException {
		// TODO Auto-generated method stub

	}

	public void writeExternal(DataOutputStream out) throws IOException {
		// TODO Auto-generated method stub
	}

	public GraphData evaluate(EvaluationContext context) {
		GraphData data = new GraphData();
		evaluateSeries(data, context);
		evaluateConfiguration(data, context);
		return data;
	}
	
	private void evaluateConfiguration(GraphData graphData, EvaluationContext context) {
		Enumeration e = configuration.keys();
		while (e.hasMoreElements()) {
			String key = (String) e.nextElement();
			graphData.setConfiguration(key, configuration.get(key).evaluate(context));
		}
	}
	
	private void evaluateSeries(GraphData graphData, EvaluationContext context) {
		try {
			for (Series s : series) {
				Hashtable<String, String> functions = new Hashtable<String, String>(3);
				functions.put("x", s.getX());
				functions.put("y", s.getY());
				if (s.getRadius() != null) {
					functions.put("radius", s.getRadius());
				}
				Hashtable<String, XPathExpression> parse = new Hashtable<String, XPathExpression>(functions.size());
				Enumeration e = functions.keys();
				while (e.hasMoreElements()) {
					String dimension = (String) e.nextElement();
					String function = functions.get(dimension);
					if (function != null) {
						parse.put(dimension, XPathParseTool.parseXPath("string(" + function + ")"));
					}
				}
				
				Vector<TreeReference> refList = context.expandReference(s.getNodeSet());
				SeriesData seriesData = new SeriesData();
				for (TreeReference ref : refList) {
					EvaluationContext refContext = new EvaluationContext(context, ref);
					Enumeration f = parse.keys();
					Hashtable<String, Double> doubles = new Hashtable<String, Double>(parse.size());
					while (f.hasMoreElements()) {
						String dimension = (String) f.nextElement();
						String value = (String) parse.get(dimension).eval(refContext.getMainInstance(), refContext);
						if (value.length() > 0) {
							doubles.put(dimension, Double.valueOf(value));
						}
					}
					if (doubles.containsKey("x") && doubles.containsKey("y")) {
						if (doubles.containsKey("radius")) {
							seriesData.addPoint(new PointData(doubles.get("x"), doubles.get("y"), doubles.get("radius")));
						}
						else {
							seriesData.addPoint(new PointData(doubles.get("x"), doubles.get("y")));
						}
					}
				}
				graphData.addSeries(seriesData);
			}
		}
		catch (XPathSyntaxException e) {
			e.printStackTrace();
		}
	}

}
