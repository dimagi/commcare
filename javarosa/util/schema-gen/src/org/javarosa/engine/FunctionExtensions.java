package org.javarosa.engine;

import org.javarosa.core.model.condition.EvaluationContext;
import org.javarosa.core.model.condition.IFunctionHandler;
import org.javarosa.core.model.data.GeoPointData;
import org.javarosa.core.model.instance.InstanceInitializationFactory;
import org.javarosa.core.model.instance.TreeReference;
import org.javarosa.engine.xml.XmlUtil;
import org.javarosa.model.xform.DataModelSerializer;
import org.javarosa.xpath.XPathLazyNodeset;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Date;
import java.util.Vector;

/**
 * Custom functions to be used in form player debug tools.
 *
 * Allows users to:
 * - override 'today()' and 'now()' with custom dates.
 * - perform introspection on xpath references with 'print()'
 * - override 'here()' with custom location.
 *
 * @author Phillip Mates (pmates@dimagi.com)
 */
public class FunctionExtensions {
    protected static class TodayFunc implements IFunctionHandler {
        private final String name;
        private final Date date;

        protected TodayFunc(String name, Date date) {
            this.name = name;
            this.date = date;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public Vector getPrototypes() {
            Vector<Class[]> p = new Vector<>();
            p.addElement(new Class[0]);
            return p;
        }

        @Override
        public boolean rawArgs() {
            return false;
        }

        @Override
        public Object eval(Object[] args, EvaluationContext ec) {
            if (date != null) {
                return date;
            } else {
                return new Date();
            }
        }
    }

    static class PrintFunc implements IFunctionHandler {
        private final InstanceInitializationFactory iif;

        protected PrintFunc(InstanceInitializationFactory iif) {
            this.iif = iif;
        }

        @Override
        public String getName() {
            return "print";
        }

        @Override
        public Vector getPrototypes() {
            Vector<Class[]> p = new Vector<>();
            p.addElement(new Class[0]);
            return p;
        }

        @Override
        public boolean rawArgs() {
            return true;
        }

        @Override
        public Object eval(Object[] args, EvaluationContext ec) {
            Vector<TreeReference> refs = ((XPathLazyNodeset)args[0]).getReferences();

            for (TreeReference ref : refs) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                DataModelSerializer serializer;
                try {
                    serializer = new DataModelSerializer(bos, iif);
                    serializer.serialize(ec.resolveReference(ref));
                    System.out.println(XmlUtil.getPrettyXml(bos.toByteArray()));
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }

            return "";
        }
    }

    public static class HereDummyFunc implements IFunctionHandler {
        private final double lat;
        private final double lon;

        public HereDummyFunc(double lat, double lon) {
            this.lat = lat;
            this.lon = lon;
        }

        @Override
        public String getName() {
            return "here";
        }

        @Override
        public Vector getPrototypes() {
            Vector<Class[]> p = new Vector<>();
            p.addElement(new Class[0]);
            return p;
        }

        @Override
        public boolean rawArgs() {
            return false;
        }

        @Override
        public Object eval(Object[] args, EvaluationContext ec) {
            return new GeoPointData(new double[]{lat, lon, 0, 10}).getDisplayText();
        }
    }
}
