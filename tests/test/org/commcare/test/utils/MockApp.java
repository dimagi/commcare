package org.commcare.test.utils;

import java.lang.IllegalArgumentException;

import org.commcare.resources.model.UnresolvedResourceException;
import org.commcare.util.CommCareConfigEngine;
import org.commcare.util.mocks.LivePrototypeFactory;
import org.commcare.util.mocks.MockDataUtils;
import org.commcare.util.mocks.MockUserDataSandbox;
import org.commcare.util.mocks.SessionWrapper;
import org.javarosa.core.util.externalizable.PrototypeFactory;
import org.javarosa.xml.util.UnfullfilledRequirementsException;

/**
 * A mock app is a quick test wrapper that makes it easy to start playing with a live instance
 * of a CommCare app including the session and user data that goes along with it.
 *
 * To use this class, make a copy of the /template/ app in the test resources and extend the
 * config and data in that copy, then pass its resource path to the constructor.
 *
 * Created by ctsims on 8/14/2015.
 */
public class MockApp {
    private final MockUserDataSandbox mSandbox;
    private final PrototypeFactory mPrototypeFactory;
    private final CommCareConfigEngine mEngine;
    private final SessionWrapper mSessionWrapper;

    /**
     * Creates and initializes a mockapp that is located at the provided Java Resource path.
     *
     * @param resourcePath The resource path to a an app template. Needs to contain a leading and
     *                     trailing slash, like /path/app/
     */
    public MockApp(String resourcePath) throws Exception {
        if(!(resourcePath.startsWith("/") && resourcePath.endsWith("/"))) {
            throw new IllegalArgumentException("Invalid resource path for a mock app " + resourcePath);
        }
        mPrototypeFactory = setupStaticStorage();
        mSandbox =  new MockUserDataSandbox(mPrototypeFactory);
        mEngine = new CommCareConfigEngine(mPrototypeFactory);


        mEngine.installAppFromReference("jr://resource" + resourcePath + "profile.ccpr");
        mEngine.initEnvironment();
        MockDataUtils.parseIntoSandbox(this.getClass().getResourceAsStream(resourcePath + "user_restore.xml"), mSandbox);

        mSessionWrapper = new SessionWrapper(mEngine.getPlatform(), mSandbox);
    }


    private static PrototypeFactory setupStaticStorage() {
        LivePrototypeFactory prototypeFactory = new LivePrototypeFactory();
        //Set up our storage
        PrototypeFactory.setStaticHasher(prototypeFactory);
        return prototypeFactory;
    }

    public SessionWrapper getSession() {
        return mSessionWrapper;
    }

}
