package org.javarosa.core.log;

import org.javarosa.core.util.SortedIntSet;

/**
 * @author ctsims
 */
public abstract class StreamLogSerializer {

    private final SortedIntSet logIDs;
    private Purger purger = null;

    public interface Purger {
        void purge(SortedIntSet IDs);
    }

    public StreamLogSerializer() {
        logIDs = new SortedIntSet();
    }

    public final void addLog(int id) {
        logIDs.add(id);
    }

    public void setPurger(Purger purger) {
        this.purger = purger;
    }

    public void purge() {
        //The purger is optional, not mandatory.
        if (purger != null) {
            this.purger.purge(logIDs);
        }
    }
}
