package org.commcare.util.screen;
import datadog.trace.api.Trace;

import org.commcare.session.CommCareSession;

import java.io.PrintStream;

/**
 * A compound screen is a controller for screens with internal state that requires navigation.
 *
 * Generally a compound screen will keep track of internal state and dispatch appropriate subscreens
 *
 * Created by ctsims on 8/20/2015.
 */
public abstract class CompoundScreenHost extends Screen {

    /**
     * NOTE: This must be non-null as soon as the compound screen init() is finished.
     *
     * @return The current subscreen to be receiving/submitting input from the CLI.
     */
    public abstract Subscreen getCurrentScreen();

    @Override
    public void prompt(PrintStream out) throws CommCareSessionException {
        getCurrentScreen().prompt(out);
    }

    @Override
    public String[] getOptions(){
        return getCurrentScreen().getOptions();
    }

    @Trace
    @Override
    public final boolean handleInputAndUpdateSession(CommCareSession session, String input, boolean allowAutoLaunch) throws CommCareSessionException {
        if (getCurrentScreen().handleInputAndUpdateHost(input, this, allowAutoLaunch)) {
            this.updateSession(session);
            return false;
        }
        return true;
    }

    /**
     * Once a subscreen has indicated that the compound screen is ready to update the session and
     * relinquish control, the parent screen should use this method to update the session
     * appropriately
     */
    protected abstract void updateSession(CommCareSession session);

}
