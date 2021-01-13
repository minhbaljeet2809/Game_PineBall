package com.dozingcatsoftware.bouncy.util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class JSONUtils {

    /**
     * Nếu param là JSONArray hoặc JSONObject, trả về danh sách hoặc list tưởng ứng. nếu param
     * ilà JSONObject.NULL, trả null. Nếu không, trả về argument không đổi.
     */
    public static Object objectFromJSONItem(Object jsonItem) {
        if (jsonItem == JSONObject.NULL) {
            return null;
        }
        if (jsonItem instanceof JSONArray) {
            return listFromJSONArray((JSONArray) jsonItem);
        }
        if (jsonItem instanceof JSONObject) {
            return mapFromJSONObject((JSONObject) jsonItem);
        }
        return jsonItem;
    }
    /**
    Trả về một danh sách dodoois tượng giống với jsonArray.
    Chuyển đối JSONArray và JSONObject thành danh sách đối tượng
    */

    public static List<Object> listFromJSONArray(JSONArray jsonArray) {
        List<Object> result = new ArrayList<>();
        try {
            for (int i = 0; i < jsonArray.length(); i++) {
                Object obj = objectFromJSONItem(jsonArray.get(i));
                result.add(obj);
            }
        } catch (JSONException ex) {
            throw new RuntimeException(ex);
        }
        return result;
    }


    public static Map<String, Object> mapFromJSONObject(JSONObject jsonObject) {
        Map<String, Object> result = new HashMap<>();
        try {
            for (Iterator<String> ki = jsonObject.keys(); ki.hasNext(); ) {
                String key = ki.next();
                Object value = objectFromJSONItem(jsonObject.get(key));
                result.put(key, value);
            }
        } catch (JSONException ex) {
            throw new RuntimeException(ex);
        }
        return result;
    }

    /** Parses the string argument as a JSON object and converts to a Map. */
    public static Map<String, Object> mapFromJSONString(String jsonString) {
        try {
            return mapFromJSONObject(new JSONObject(jsonString));
        } catch (JSONException ex) {
            throw new RuntimeException(ex);
        }
    }
}
