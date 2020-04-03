package org.commcare.core.network;

import java.io.IOException;

/**
 * Exception wrapper which communicates that an exception is the result of the current network
 * being behind a captive portal which
 *
 * @author Clayton Sims (csims@dimagi.com)
 */

public class CaptivePortalRedirectException extends IOException {
    public CaptivePortalRedirectException(IOException sourceException) {
        super("The current network is not connected to the internet. You may need to log in from " +
                "a web browser");
        this.initCause(sourceException);
    }
}
