package org.commcare.util;

import com.google.gson.Gson;
import java.lang.reflect.Type;

/**
 * A Utility to convert Java objects to JSON strings and vice-versa.
 * @author $|-|!˅@M
 */
public class JsonUtil {
    public static String getJsonFromObject(Object object, Type type) {
        Gson gson = new Gson();
        return gson.toJson(object, type);
    }

    public static Object getObjectFromJson(String json, Type type) {
        Gson gson = new Gson();
        return gson.fromJson(json, type);
    }
}

