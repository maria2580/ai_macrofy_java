package com.example.ai_macrofy.services.accessibility;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class LayoutAccessibilityService extends AccessibilityService {

    private static final String TAG = "LayoutAccessibilityService";
    public static LayoutAccessibilityService instance;

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Log.d(TAG, "Service connected");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Event processing not implemented
    }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "Service interrupted");
        instance = null; // Ensure instance is cleared
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "Service destroyed");
        instance = null;
        super.onDestroy();
    }

    public JSONObject extractLayoutInfo() {
        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
        if (rootNode == null) {
            Log.e(TAG, "Root node is null. Cannot extract layout info.");
            return null;
        }
        try {
            return parseNodeToJson(rootNode);
        } catch (JSONException e) {
            Log.e(TAG, "Failed to parse node to JSON", e);
            return new JSONObject(); // Return empty JSON on error
        } finally {
            // Per documentation, nodes obtained from getRootInActiveWindow() should be recycled.
            // However, child nodes obtained from it should not be recycled if the parent isn't.
            // Careful with recycling: only recycle if you are sure the node and its children
            // are not used elsewhere. The original Kotlin code did not recycle.
            // For safety and to match Kotlin, I'm not recycling here, but be aware.
            // rootNode.recycle();
        }
    }

    private JSONObject parseNodeToJson(AccessibilityNodeInfo node) throws JSONException {
        if (node == null) {
            return new JSONObject();
        }

        JSONObject jsonObject = new JSONObject();
        JSONArray childrenArray = new JSONArray();

        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);

        jsonObject.put("className", node.getClassName() != null ? node.getClassName().toString() : "");
        jsonObject.put("text", node.getText() != null ? node.getText().toString() : "");
        jsonObject.put("contentDescription", node.getContentDescription() != null ? node.getContentDescription().toString() : "");

        int x = (bounds.right + bounds.left) / 2;
        int y = (bounds.bottom + bounds.top) / 2;

        JSONObject coordination = new JSONObject();
        coordination.put("x", x);
        coordination.put("y", y);
        jsonObject.put("coordination", coordination);

        jsonObject.put("clickable", node.isClickable());
        // jsonObject.put("id", node.getViewIdResourceName() != null ? node.getViewIdResourceName() : "");

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo childNode = node.getChild(i);
            if (childNode != null) {
                childrenArray.put(parseNodeToJson(childNode));
                // childNode.recycle(); // Be careful, original Kotlin didn't.
            }
        }
        jsonObject.put("children", childrenArray);
        Log.d(TAG, "parseNodeToJson:"+jsonObject.toString());
        return jsonObject;
    }
}