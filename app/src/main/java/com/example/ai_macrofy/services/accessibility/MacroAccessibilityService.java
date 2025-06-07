package com.example.ai_macrofy.services.accessibility;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import com.example.ai_macrofy.services.foreground.MyForegroundService;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MacroAccessibilityService extends AccessibilityService {

    private static final String TAG = "MacroAccessibilityService";
    public static MacroAccessibilityService instance;
    private Handler mainThreadHandler;

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        mainThreadHandler = new Handler(Looper.getMainLooper());
        AccessibilityServiceInfo configInfo = new AccessibilityServiceInfo();
        configInfo.eventTypes = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED | AccessibilityEvent.TYPE_VIEW_CLICKED;
        configInfo.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        configInfo.flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;

        if (getServiceInfo() != null) {
            getServiceInfo().eventTypes = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED | AccessibilityEvent.TYPE_VIEW_CLICKED;
            getServiceInfo().feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
            getServiceInfo().flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        } else {
            setServiceInfo(configInfo);
        }

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
        instance = null;
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "Service destroyed");
        instance = null;
        if (mainThreadHandler != null) {
            mainThreadHandler.removeCallbacksAndMessages(null);
        }
        super.onDestroy();
    }

    public void executeActionsFromJson(String json) {
        if (json == null || json.isEmpty()) {
            Log.w(TAG, "JSON string is null or empty.");
            return;
        }
        try {
            // Clean the JSON string from any potential markdown formatting
            if (json.startsWith("```json")) {
                json = json.substring(7, json.length() - 3).trim();
            } else if (json.startsWith("```")) {
                json = json.substring(3, json.length() - 3).trim();
            }

            JSONObject jsonObject = new JSONObject(json);
            JSONArray actions = jsonObject.optJSONArray("actions");

            if (actions == null || actions.length() == 0) {
                Log.w(TAG, "No actions found in JSON.");
                if (MyForegroundService.instance != null) {
                    MyForegroundService.instance.reportActionCompleted(true, "No actions in JSON");
                }
                return;
            }

            boolean allActionsSuccessful = true;
            String lastFailureReason = "";

            for (int i = 0; i < actions.length(); i++) {
                JSONObject action = actions.getJSONObject(i);
                String type = action.optString("type");
                boolean actionSuccess = false;
                String actionFailureReason = "Unknown failure";

                switch (type) {
                    case "touch":
                        actionSuccess = handleTouch(action);
                        if (!actionSuccess) actionFailureReason = "Touch action failed at specified coordinates.";
                        break;
                    case "input":
                        actionSuccess = handleInput(action);
                        if (!actionSuccess) actionFailureReason = "Input action failed. Check if the target is an editable field.";
                        break;
                    case "scroll":
                        actionSuccess = handleScroll(action);
                        if (!actionSuccess) actionFailureReason = "Scroll action failed.";
                        break;
                    case "long_touch":
                        actionSuccess = handleLongTouch(action);
                        if (!actionSuccess) actionFailureReason = "Long touch action failed.";
                        break;
                    case "drag_and_drop":
                        actionSuccess = handleDragAndDrop(action);
                        if (!actionSuccess) actionFailureReason = "Drag and drop action failed.";
                        break;
                    case "double_tap":
                        actionSuccess = handleDoubleTap(action);
                        if (!actionSuccess) actionFailureReason = "Double tap action failed.";
                        break;
                    case "swipe":
                        actionSuccess = handleSwipe(action);
                        if (!actionSuccess) actionFailureReason = "Swipe action failed.";
                        break;
                    case "gesture":
                        actionSuccess = handleGesture(action);
                        if (!actionSuccess) actionFailureReason = "Gesture action failed.";
                        break;
                    case "wait":
                        actionSuccess = handleWait(action);
                        if (!actionSuccess) actionFailureReason = "Wait action failed.";
                        break;
                    case "open_application":
                        actionSuccess = handleOpenApplication(action);
                        if (!actionSuccess) actionFailureReason = "Open application action failed. Check package name or if app is installed.";
                        break;
                    case "done":
                        handleDone();
                        actionSuccess = true;
                        return;
                    default:
                        Log.w(TAG, "Unknown action type: " + type);
                        actionSuccess = false;
                        actionFailureReason = "Unknown action type: " + type;
                        break;
                }

                if (!actionSuccess) {
                    allActionsSuccessful = false;
                    lastFailureReason = "Action type '" + type + "' failed: " + actionFailureReason;
                    Log.e(TAG, lastFailureReason);
                    break;
                }

                if (!"wait".equals(type) && !"done".equals(type) && actions.length() > 1 && i < actions.length() -1) {
                    SystemClock.sleep(500);
                }
            }

            if (MyForegroundService.instance != null) {
                if (allActionsSuccessful) {
                    MyForegroundService.instance.reportActionCompleted(true, null);
                } else {
                    MyForegroundService.instance.reportActionCompleted(false, lastFailureReason);
                }
            }

        } catch (JSONException e) {
            Log.e(TAG, "JSONException during parsing or executing actions: " + e.getMessage(), e);
            if (MyForegroundService.instance != null) {
                MyForegroundService.instance.reportActionCompleted(false, "JSON parsing error: " + e.getMessage());
            }
        } catch (Exception e) {
            Log.e(TAG, "Exception during executing actions: " + e.getMessage(), e);
            if (MyForegroundService.instance != null) {
                MyForegroundService.instance.reportActionCompleted(false, "Runtime error during action execution: " + e.getMessage());
            }
        }
    }
    private boolean isValidXY(int x, int y) {
        if (x<0){
            return false;
        }
        else if (y<0){
            return false;
        }
        else {
            return true;
        }
    }
    private boolean handleTouch(JSONObject action) throws JSONException {
        JSONObject coordinates = action.getJSONObject("coordinates");
        int x = coordinates.getInt("x");
        int y = coordinates.getInt("y");
        if(isValidXY(x,y)){
            return performTouch(x, y);
        }else {
            return false;
        }
    }

    private boolean handleInput(JSONObject action) throws JSONException {
        String text = action.getString("text");
        JSONObject coordinates = action.getJSONObject("coordinates");
        int x = coordinates.getInt("x");
        int y = coordinates.getInt("y");
        return performInput(text, x, y);
    }

    private boolean handleScroll(JSONObject action) throws JSONException {
        String direction = action.getString("direction");
        JSONObject coordinates = action.optJSONObject("coordinates");
        if (coordinates != null) {
            int x = coordinates.getInt("x");
            int y = coordinates.getInt("y");
            Log.d(TAG, "Targeted scroll requested for direction '" + direction + "' at (" + x + "," + y + ")");
            return performScroll(direction, x, y);
        } else {
            Log.d(TAG, "Generic screen scroll requested for direction '" + direction + "'");
            return performScroll(direction, -1, -1);
        }
    }

    private boolean handleLongTouch(JSONObject action) throws JSONException {
        JSONObject coordinates = action.getJSONObject("coordinates");
        int x = coordinates.getInt("x");
        int y = coordinates.getInt("y");
        long duration = action.getLong("duration");
        return performLongTouch(x, y, duration);
    }

    private boolean handleDragAndDrop(JSONObject action) throws JSONException {
        JSONObject start = action.getJSONObject("start");
        int startX = start.getInt("x");
        int startY = start.getInt("y");
        JSONObject end = action.getJSONObject("end");
        int endX = end.getInt("x");
        int endY = end.getInt("y");
        long duration = action.getLong("duration");
        return performDragAndDrop(startX, startY, endX, endY, duration);
    }

    private boolean handleDoubleTap(JSONObject action) throws JSONException {
        JSONObject coordinates = action.getJSONObject("coordinates");
        int x = coordinates.getInt("x");
        int y = coordinates.getInt("y");
        return performDoubleTap(x, y);
    }

    private boolean handleSwipe(JSONObject action) throws JSONException {
        JSONObject start = action.getJSONObject("start");
        int startX = start.getInt("x");
        int startY = start.getInt("y");
        JSONObject end = action.getJSONObject("end");
        int endX = end.getInt("x");
        int endY = end.getInt("y");
        long duration = action.getLong("duration");
        return performSwipe(startX, startY, endX, endY, duration);
    }

    private boolean handleWait(JSONObject action) throws JSONException {
        long duration = action.getLong("duration");
        if (duration <= 0) {
            Log.w(TAG, "Wait duration must be positive. Received: " + duration);
            return false;
        }
        Log.d(TAG, "Performing wait for " + duration + " ms");
        try {
            SystemClock.sleep(duration);
            Log.d(TAG, "Wait completed after " + duration + " ms");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error during wait: " + e.getMessage(), e);
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private boolean handleOpenApplication(JSONObject action) throws JSONException {
        String packageName = action.optString("application_name");
        if (packageName == null || packageName.isEmpty()) {
            Log.w(TAG, "Application name (package name) is missing for open_application action.");
            mainThreadHandler.post(() -> Toast.makeText(getApplicationContext(), "Error: Application name missing.", Toast.LENGTH_LONG).show());
            return false;
        }

        Log.d(TAG, "Attempting to open application: " + packageName);
        PackageManager pm = getPackageManager();
        Intent intent = pm.getLaunchIntentForPackage(packageName);
        if (intent == null) {
            Log.w(TAG, "Could not create launch intent for package: " + packageName);
            mainThreadHandler.post(() -> Toast.makeText(getApplicationContext(), "App not found: " + packageName, Toast.LENGTH_LONG).show());
            return false;
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
            Log.d(TAG, "Successfully started activity for package: " + packageName);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Could not start application: " + packageName, e);
            mainThreadHandler.post(() -> Toast.makeText(getApplicationContext(), "Failed to open app: " + packageName, Toast.LENGTH_LONG).show());
            return false;
        }
    }

    private boolean handleGesture(JSONObject action) throws JSONException {
        String name = action.getString("name");
        boolean success = false;
        switch (name) {
            case "back":
                success = performGlobalAction(GLOBAL_ACTION_BACK);
                break;
            case "home":
                success = performGlobalAction(GLOBAL_ACTION_HOME);
                break;
            case "recent_apps":
                success = performGlobalAction(GLOBAL_ACTION_RECENTS);
                break;
            default:
                Log.w(TAG, "Unknown gesture name: " + name);
                break;
        }
        return success;
    }

    private void handleDone() {
        Log.d(TAG, "Execution completed (handleDone called).");
        if (MyForegroundService.instance != null) {
            MyForegroundService.instance.stopMacroExecution();
        }
        mainThreadHandler.post(() -> Toast.makeText(getApplicationContext(), "완료했습니다.", Toast.LENGTH_LONG).show());
    }

    private boolean performTouch(int x, int y) {
        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
        if (rootNode != null) {
            AccessibilityNodeInfo targetNode = findNodeAtCoordinates(rootNode, x, y);
            if (targetNode != null && targetNode.isClickable()) {
                Log.d(TAG, "Node found at (" + x + ", " + y + "): " + targetNode.getClassName() + ". Performing ACTION_CLICK.");
                boolean success = targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                if(success) return true;
                Log.w(TAG, "ACTION_CLICK failed on node. Trying gesture.");
            }
        }
        return performGestureTouch(x,y);
    }

    private boolean performGestureTouch(int x, int y) {
        Log.d(TAG, "Performing gesture-based touch at (" + x + ", " + y + ").");
        Path path = new Path();
        path.moveTo((float) x, (float) y);

        GestureDescription.Builder gestureBuilder = new GestureDescription.Builder();
        gestureBuilder.addStroke(new GestureDescription.StrokeDescription(path, 0, 100L));
        return dispatchGesture(gestureBuilder.build(), new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                super.onCompleted(gestureDescription);
                Log.d(TAG, "Touch gesture completed at (" + x + ", " + y + ")");
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                super.onCancelled(gestureDescription);
                Log.w(TAG, "Touch gesture cancelled at (" + x + ", " + y + ")");
            }
        }, null);
    }


    private boolean performInput(String text, int x, int y) {
        Log.d(TAG, "Attempting input '" + text + "' at (" + x + ", " + y + ")");
        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
        if (rootNode != null) {
            AccessibilityNodeInfo targetNode = findNodeAtCoordinatesForInput(rootNode, x, y);
            if (targetNode != null && (targetNode.isEditable() || targetNode.isFocusable())) {
                Log.d(TAG, "Node for input found: " + targetNode.getClassName());
                targetNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                Bundle args = new Bundle();
                args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
                boolean setTextSuccess = targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
                if (!setTextSuccess) {
                    Log.w(TAG, "ACTION_SET_TEXT failed on node.");
                    return false;
                } else {
                    Log.d(TAG, "ACTION_SET_TEXT succeeded.");
                    return true;
                }
            } else {
                Log.w(TAG, "No suitable node found or node not editable/focusable at (" + x + ", " + y + ") for input.");
                return false;
            }
        }
        Log.w(TAG, "Root node is null for input operation.");
        return false;
    }

    private AccessibilityNodeInfo findNodeAtCoordinatesForInput(AccessibilityNodeInfo node, int x, int y) {
        return findNodeAtCoordinates(node, x, y);
    }

    private boolean performDoubleTap(int x, int y) {
        Path path = new Path();
        path.moveTo((float) x, (float) y);
        GestureDescription.Builder gestureBuilder = new GestureDescription.Builder();
        gestureBuilder.addStroke(new GestureDescription.StrokeDescription(path, 0L, 100L));
        gestureBuilder.addStroke(new GestureDescription.StrokeDescription(path, 200L, 100L));
        return dispatchGesture(gestureBuilder.build(), null, null);
    }


    private boolean performLongTouch(int x, int y, long duration) {
        Path path = new Path();
        path.moveTo((float) x, (float) y);
        GestureDescription.Builder gestureBuilder = new GestureDescription.Builder();
        gestureBuilder.addStroke(new GestureDescription.StrokeDescription(path, 0L, duration));
        return dispatchGesture(gestureBuilder.build(), null, null);
    }
    /**
     * [REVISED] Main scroll logic with a candidate prioritization system.
     *//*
    *//**
     * [REVISED] Main scroll logic with a targeted gesture fallback.
     *//*
    private boolean performScroll(String direction, int x, int y) {
        List<AccessibilityNodeInfo> candidates = findScrollableCandidatesAt(x, y);
        AccessibilityNodeInfo bestTarget = chooseBestScrollableNode(candidates, direction);

        if (bestTarget != null) {
            Log.d(TAG, "Best scroll target chosen: " + bestTarget.getClassName());

            // Get bounds before trying the action, in case the node is recycled.
            Rect targetBounds = new Rect();
            bestTarget.getBoundsInScreen(targetBounds);

            // 1. Programmatic scroll attempt
            boolean success = bestTarget.performAction(getScrollActionId(direction));
            bestTarget.recycle();

            if (success) {
                Log.d(TAG, "Programmatic scroll successful.");
                // Recycle any remaining candidates
                for(AccessibilityNodeInfo node : candidates) if(node != null) node.recycle();
                return true;
            }

            // 2. Targeted Gesture Fallback
            Log.w(TAG, "Programmatic scroll failed. Falling back to TARGETED gesture scroll.");
            for(AccessibilityNodeInfo node : candidates) if(node != null) node.recycle();
            return performGestureScroll(direction, targetBounds);

        } else {
            Log.d(TAG, "No suitable scrollable node found. Falling back to GLOBAL gesture scroll.");
            return performGestureScroll(direction, null); // No target, so use global gesture
        }
    }

    *//**
     * [NEW] Chooses the best scrollable node from a list of candidates based on priority.
     *//*
    private AccessibilityNodeInfo chooseBestScrollableNode(List<AccessibilityNodeInfo> candidates, String direction) {
        if (candidates.isEmpty()) return null;

        boolean isHorizontal = "left".equalsIgnoreCase(direction) || "right".equalsIgnoreCase(direction);

        // 1st Priority: Specific vertical scrollers (RecyclerView, ScrollView, ListView)
        for (AccessibilityNodeInfo node : candidates) {
            CharSequence className = node.getClassName();
            if (className != null && (className.toString().contains("RecyclerView") || className.toString().contains("ScrollView") || className.toString().contains("ListView"))) {
                Log.d(TAG, "Choosing priority 1 target: " + className);
                candidates.remove(node); // Remove to avoid double recycling
                return node;
            }
        }

        // 2nd Priority: ViewPager, but only for horizontal scrolls
        if (isHorizontal) {
            for (AccessibilityNodeInfo node : candidates) {
                CharSequence className = node.getClassName();
                if (className != null && className.toString().contains("ViewPager")) {
                    Log.d(TAG, "Choosing priority 2 target (ViewPager for horizontal scroll): " + className);
                    candidates.remove(node);
                    return node;
                }
            }
        }

        // 3rd Priority: Any other scrollable view (that isn't a ViewPager if scrolling vertically)
        for (AccessibilityNodeInfo node : candidates) {
            if(isHorizontal) { // For horizontal, any remaining scrollable is fine
                Log.d(TAG, "Choosing priority 3 target (any scrollable for horizontal): " + node.getClassName());
                candidates.remove(node);
                return node;
            } else { // For vertical, avoid ViewPager
                CharSequence className = node.getClassName();
                if(className != null && !className.toString().contains("ViewPager")) {
                    Log.d(TAG, "Choosing priority 3 target (non-ViewPager for vertical): " + node.getClassName());
                    candidates.remove(node);
                    return node;
                }
            }
        }

        return null; // No suitable node found
    }

    *//**
     * [REVISED] Performs a gesture scroll. If bounds are provided, it's a targeted gesture.
     * Otherwise, it's a global gesture on the whole screen.
     * @param direction "up", "down", "left", or "right"
     * @param bounds The specific Rect of the target view to scroll within, or null for global.
     *//*
    private boolean performGestureScroll(String direction, Rect bounds) {
        Rect scrollBounds = new Rect();

        if (bounds != null && !bounds.isEmpty()) {
            Log.d(TAG, "Performing targeted gesture within bounds: " + bounds.toShortString());
            scrollBounds.set(bounds);
        } else {
            AccessibilityNodeInfo rootNode = getRootInActiveWindow();
            if (rootNode == null) return false;
            rootNode.getBoundsInScreen(scrollBounds);
            rootNode.recycle();
            Log.d(TAG, "Performing global gesture within screen bounds: " + scrollBounds.toShortString());
        }

        int startX, startY, endX, endY;
        // Use percentages of the TARGET's dimensions, not the whole screen
        switch (direction.toLowerCase()) {
            case "down":
                startX = endX = scrollBounds.centerX();
                startY = scrollBounds.top + (int) (scrollBounds.height() * 0.7f);
                endY = scrollBounds.top + (int) (scrollBounds.height() * 0.3f);
                break;
            case "up":
                startX = endX = scrollBounds.centerX();
                startY = scrollBounds.top + (int) (scrollBounds.height() * 0.3f);
                endY = scrollBounds.top + (int) (scrollBounds.height() * 0.7f);
                break;
            case "right":
                startY = endY = scrollBounds.centerY();
                startX = scrollBounds.left + (int) (scrollBounds.width() * 0.8f);
                endX = scrollBounds.left + (int) (scrollBounds.width() * 0.2f);
                break;
            case "left":
                startY = endY = scrollBounds.centerY();
                startX = scrollBounds.left + (int) (scrollBounds.width() * 0.2f);
                endX = scrollBounds.left + (int) (scrollBounds.width() * 0.8f);
                break;
            default: return false;
        }

        Log.d(TAG, "Gesture scroll " + direction + ": from (" + startX + ", " + startY + ") to (" + endX + ", " + endY + ")");
        Path path = new Path();
        path.moveTo(startX, startY);
        path.lineTo(endX, endY);
        return dispatchGesture(new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(path, 0L, 400L)).build(), null, null);
    }

    private int getScrollActionId(String direction) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            switch (direction.toLowerCase()) {
                case "down": return AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.getId();
                case "up": return AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.getId();
                case "left": return AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.getId();
                case "right": return AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.getId();
            }
        }
        switch (direction.toLowerCase()) {
            case "down": case "right": return AccessibilityNodeInfo.ACTION_SCROLL_FORWARD;
            case "up": case "left": return AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
        }
        return -1;
    }

    // --- Find Node Methods ---

    *//**
     * [NEW] Finds all scrollable ancestors of a point and returns them as a list for prioritization.
     *//*
    private List<AccessibilityNodeInfo> findScrollableCandidatesAt(int x, int y) {
        if (x < 0 || y < 0) return Collections.emptyList();

        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
        if (rootNode == null) return Collections.emptyList();

        AccessibilityNodeInfo deepestNode = findDeepestNodeAt(rootNode, x, y);
        rootNode.recycle(); // Done with the root
        if (deepestNode == null) return Collections.emptyList();

        List<AccessibilityNodeInfo> candidates = new ArrayList<>();
        AccessibilityNodeInfo parent = deepestNode;
        while (parent != null) {
            if (parent.isScrollable()) {
                candidates.add(AccessibilityNodeInfo.obtain(parent));
            }
            parent = parent.getParent();
        }
        deepestNode.recycle();
        // The list now contains scrollable nodes from deepest to highest ancestor.
        return candidates;
    }

    private AccessibilityNodeInfo findDeepestNodeAt(AccessibilityNodeInfo node, int x, int y) {
        if (node == null) return null;
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (!bounds.contains(x, y)) {
            return null;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            AccessibilityNodeInfo foundInChild = findDeepestNodeAt(child, x, y);
            if(child != null) child.recycle(); // Recycle child after use
            if (foundInChild != null) {
                return foundInChild;
            }
        }
        return AccessibilityNodeInfo.obtain(node);
    }
    // --- Logging and Debugging Methods ---

    private void logNodeDetails(AccessibilityNodeInfo node) {
        if (node == null) {
            Log.d(TAG, "[Node Details] Node is null.");
            return;
        }
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);

        Log.d(TAG, "[Node Details] Class Name: " + node.getClassName());
        Log.d(TAG, "[Node Details] Text: " + node.getText());
        Log.d(TAG, "[Node Details] Content Desc: " + node.getContentDescription());
        Log.d(TAG, "[Node Details] Bounds in Screen: " + bounds.toShortString());
        Log.d(TAG, "[Node Details] Is Scrollable: " + node.isScrollable());
        Log.d(TAG, "[Node Details] Is Enabled: " + node.isEnabled());
        Log.d(TAG, "[Node Details] Is Visible to User: " + node.isVisibleToUser());
        Log.d(TAG, "[Node Details] Supported Actions: " + getActionNames(node.getActionList()));
    }

    private String getActionNames(List<AccessibilityNodeInfo.AccessibilityAction> actionList) {
        if (actionList == null || actionList.isEmpty()) return "NONE";
        StringBuilder names = new StringBuilder();
        for (AccessibilityNodeInfo.AccessibilityAction action : actionList) {
            names.append(getActionName(action)).append(", ");
        }
        return names.toString();
    }

    private String getActionName(AccessibilityNodeInfo.AccessibilityAction action) {
        int id = action.getId();
        if (id == AccessibilityNodeInfo.ACTION_FOCUS) return "FOCUS";
        if (id == AccessibilityNodeInfo.ACTION_CLEAR_FOCUS) return "CLEAR_FOCUS";
        if (id == AccessibilityNodeInfo.ACTION_SELECT) return "SELECT";
        if (id == AccessibilityNodeInfo.ACTION_CLICK) return "CLICK";
        if (id == AccessibilityNodeInfo.ACTION_LONG_CLICK) return "LONG_CLICK";
        if (id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) return "SCROLL_FORWARD";
        if (id == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) return "SCROLL_BACKWARD";
        if (id == AccessibilityNodeInfo.ACTION_COPY) return "COPY";
        if (id == AccessibilityNodeInfo.ACTION_PASTE) return "PASTE";
        if (id == AccessibilityNodeInfo.ACTION_SET_TEXT) return "SET_TEXT";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.getId()) return "SCROLL_UP";
            if (id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.getId()) return "SCROLL_DOWN";
            if (id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.getId()) return "SCROLL_LEFT";
            if (id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.getId()) return "SCROLL_RIGHT";
        }
        return action.getLabel() != null ? action.getLabel().toString() : "CUSTOM_ACTION_ID_" + id;
    }*/

    /**
     * The final, most robust scroll logic.
     */
    private boolean performScroll(String direction, int x, int y) {
        List<AccessibilityNodeInfo> candidates = findScrollableCandidatesAt(x, y);
        AccessibilityNodeInfo bestTarget = chooseBestScrollableNode(candidates, direction);
        logNodeDetails(bestTarget);

        if (bestTarget != null) {
            Log.d(TAG, "Best scroll target chosen: " + bestTarget.getClassName());
            Rect targetBounds = new Rect();
            bestTarget.getBoundsInScreen(targetBounds);

            // 1. Programmatic scroll attempt
            boolean success = bestTarget.performAction(getScrollActionId(direction));

            if (success) {
                Log.d(TAG, "Programmatic scroll successful.");
                bestTarget.recycle();
                // Recycle remaining candidates
                for(AccessibilityNodeInfo node : candidates) if(node != null) node.recycle();
                return true;
            }

            // 2. Targeted Gesture Fallback
            Log.w(TAG, "Programmatic scroll failed. Falling back to TARGETED gesture scroll.");
            bestTarget.recycle();
            for(AccessibilityNodeInfo node : candidates) if(node != null) node.recycle();
            return performGestureScroll(direction, targetBounds);

        } else {
            Log.d(TAG, "No suitable scrollable node found. Falling back to GLOBAL gesture scroll.");
            return performGestureScroll(direction, null);
        }
    }

    private AccessibilityNodeInfo chooseBestScrollableNode(List<AccessibilityNodeInfo> candidates, String direction) {
        if (candidates.isEmpty()) return null;
        boolean isHorizontal = "left".equalsIgnoreCase(direction) || "right".equalsIgnoreCase(direction);

        // 1st Priority: Specific scrollers like RecyclerView, ScrollView, ListView
        for (AccessibilityNodeInfo node : candidates) {
            CharSequence className = node.getClassName();
            if (className != null && (className.toString().contains("RecyclerView") || className.toString().contains("ScrollView") || className.toString().contains("ListView"))) {
                Log.d(TAG, "Choosing priority 1 target: " + className);
                candidates.remove(node);
                return node;
            }
        }

        // 2nd Priority: ViewPager, but ONLY for horizontal scrolls
        if (isHorizontal) {
            for (AccessibilityNodeInfo node : candidates) {
                if (node.getClassName() != null && node.getClassName().toString().contains("ViewPager")) {
                    Log.d(TAG, "Choosing priority 2 target (ViewPager for horizontal): " + node.getClassName());
                    candidates.remove(node);
                    return node;
                }
            }
        }

        // 3rd Priority: Any other scrollable view, avoiding ViewPagers for vertical scrolls
        for (AccessibilityNodeInfo node : candidates) {
            if (isHorizontal || (node.getClassName() != null && !node.getClassName().toString().contains("ViewPager"))) {
                Log.d(TAG, "Choosing priority 3 target: " + node.getClassName());
                candidates.remove(node);
                return node;
            }
        }
        return null; // No suitable node found
    }

    private boolean performGestureScroll(String direction, Rect bounds) {
        Rect scrollBounds = new Rect();
        if (bounds != null && !bounds.isEmpty()) {
            Log.d(TAG, "Performing targeted gesture within bounds: " + bounds.toShortString());
            scrollBounds.set(bounds);
        } else {
            AccessibilityNodeInfo rootNode = getRootInActiveWindow();
            if (rootNode == null) return false;
            rootNode.getBoundsInScreen(scrollBounds);
            rootNode.recycle();
            Log.d(TAG, "Performing global gesture within screen bounds: " + scrollBounds.toShortString());
        }

        int startX, startY, endX, endY;
        switch (direction.toLowerCase()) {
            case "down":
                startX = endX = scrollBounds.centerX();
                startY = scrollBounds.top + (int) (scrollBounds.height() * 0.8f); // Start lower
                endY = scrollBounds.top + (int) (scrollBounds.height() * 0.2f); // End higher
                break;
            case "up":
                startX = endX = scrollBounds.centerX();
                startY = scrollBounds.top + (int) (scrollBounds.height() * 0.2f);
                endY = scrollBounds.top + (int) (scrollBounds.height() * 0.8f);
                break;
            case "right":
                startY = endY = scrollBounds.centerY();
                startX = scrollBounds.left + (int) (scrollBounds.width() * 0.8f);
                endX = scrollBounds.left + (int) (scrollBounds.width() * 0.2f);
                break;
            case "left":
                startY = endY = scrollBounds.centerY();
                startX = scrollBounds.left + (int) (scrollBounds.width() * 0.2f);
                endX = scrollBounds.left + (int) (scrollBounds.width() * 0.8f);
                break;
            default: return false;
        }

        Log.d(TAG, "Gesture scroll " + direction + ": from (" + startX + ", " + startY + ") to (" + endX + ", " + endY + ")");
        Path path = new Path();
        path.moveTo(startX, startY);
        path.lineTo(endX, endY);
        return dispatchGesture(new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(path, 0L, 1600L)).build(), null, null);
    }

    private int getScrollActionId(String direction) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            switch (direction.toLowerCase()) {
                case "down": return AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.getId();
                case "up": return AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.getId();
                case "left": return AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.getId();
                case "right": return AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.getId();
            }
        }
        switch (direction.toLowerCase()) {
            case "down": case "right": return AccessibilityNodeInfo.ACTION_SCROLL_FORWARD;
            case "up": case "left": return AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
        }
        return -1;
    }

    // --- Find Node Methods ---
    private List<AccessibilityNodeInfo> findScrollableCandidatesAt(int x, int y) {
        if (x < 0 || y < 0) return Collections.emptyList();
        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
        if (rootNode == null) return Collections.emptyList();
        AccessibilityNodeInfo deepestNode = findDeepestNodeAt(rootNode, x, y);
        if (deepestNode == null) return Collections.emptyList();

        List<AccessibilityNodeInfo> candidates = new ArrayList<>();
        AccessibilityNodeInfo parent = deepestNode;
        while (parent != null) {
            if (parent.isScrollable()) {
                candidates.add(AccessibilityNodeInfo.obtain(parent));
            }
            AccessibilityNodeInfo oldParent = parent;
            parent = parent.getParent();
            if(oldParent != deepestNode) oldParent.recycle();
        }
        deepestNode.recycle();
        return candidates;
    }

    private AccessibilityNodeInfo findDeepestNodeAt(AccessibilityNodeInfo node, int x, int y) {
        if (node == null) return null;
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (!bounds.contains(x, y)) {
            return null;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            AccessibilityNodeInfo foundInChild = findDeepestNodeAt(child, x, y);
            if(child != null) child.recycle();
            if (foundInChild != null) {
                return foundInChild;
            }
        }
        return AccessibilityNodeInfo.obtain(node);
    }

// 아래 두 메소드를 MacroAccessibilityService 클래스 내부에 추가하세요.

    /**
     * 노드의 상세 정보를 Logcat에 출력하는 헬퍼 메소드
     * @param node 분석할 노드
     */
    private void logNodeDetails(AccessibilityNodeInfo node) {
        if (node == null) {
            Log.d(TAG, "[Node Details] Node is null.");
            return;
        }
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);

        Log.d(TAG, "[Node Details] Class Name: " + node.getClassName());
        Log.d(TAG, "[Node Details] Text: " + node.getText());
        Log.d(TAG, "[Node Details] Content Desc: " + node.getContentDescription());
        Log.d(TAG, "[Node Details] Bounds in Screen: " + bounds.toShortString());
        Log.d(TAG, "[Node Details] Is Scrollable: " + node.isScrollable());
        Log.d(TAG, "[Node Details] Is Enabled: " + node.isEnabled());
        Log.d(TAG, "[Node Details] Is Visible to User: " + node.isVisibleToUser());
        Log.d(TAG, "[Node Details] Child Count: " + node.getChildCount());
        Log.d(TAG, "[Node Details] Supported Actions: " + getActionNames(node.getActionList()));
    }

    /**
     * AccessibilityAction 리스트를 사람이 읽을 수 있는 문자열로 변환합니다.
     * @param actionList 변환할 액션 리스트
     * @return 액션 이름들의 문자열
     */
    private String getActionNames(java.util.List<AccessibilityNodeInfo.AccessibilityAction> actionList) {
        if (actionList == null || actionList.isEmpty()) {
            return "NONE";
        }
        StringBuilder names = new StringBuilder();
        for (AccessibilityNodeInfo.AccessibilityAction action : actionList) {
            int id = action.getId();
            String name;
            switch (id) {
                case AccessibilityNodeInfo.ACTION_FOCUS: name = "FOCUS"; break;
                case AccessibilityNodeInfo.ACTION_CLEAR_FOCUS: name = "CLEAR_FOCUS"; break;
                case AccessibilityNodeInfo.ACTION_SELECT: name = "SELECT"; break;
                case AccessibilityNodeInfo.ACTION_CLICK: name = "CLICK"; break;
                case AccessibilityNodeInfo.ACTION_LONG_CLICK: name = "LONG_CLICK"; break;
                case AccessibilityNodeInfo.ACTION_SCROLL_FORWARD: name = "SCROLL_FORWARD"; break;
                case AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD: name = "SCROLL_BACKWARD"; break;
                case AccessibilityNodeInfo.ACTION_COPY: name = "COPY"; break;
                case AccessibilityNodeInfo.ACTION_PASTE: name = "PASTE"; break;
                case AccessibilityNodeInfo.ACTION_SET_TEXT: name = "SET_TEXT"; break;
                default:
                    name = action.getLabel() != null ? action.getLabel().toString() : "CUSTOM_ACTION_ID_" + id;
                    break;
            }
            names.append(name).append(", ");
        }
        return names.toString();
    }

    private boolean performSwipe(int startX, int startY, int endX, int endY, long duration) {
        Path path = new Path();
        path.moveTo((float) startX, (float) startY);
        path.lineTo((float) endX, (float) endY);
        GestureDescription.Builder gestureBuilder = new GestureDescription.Builder();
        gestureBuilder.addStroke(new GestureDescription.StrokeDescription(path, 0L, duration));
        return dispatchGesture(gestureBuilder.build(), null, null);
    }

    private boolean performDragAndDrop(int startX, int startY, int endX, int endY, long duration) {
        Path path = new Path();
        path.moveTo((float) startX, (float) startY);
        path.lineTo((float) endX, (float) endY);
        GestureDescription.Builder gestureBuilder = new GestureDescription.Builder();
        gestureBuilder.addStroke(new GestureDescription.StrokeDescription(path, 0L, duration));
        return dispatchGesture(gestureBuilder.build(), null, null);
    }

    private AccessibilityNodeInfo findNodeAtCoordinates(AccessibilityNodeInfo parentNode, int x, int y) {
        if (parentNode == null) {
            return null;
        }
        Rect bounds = new Rect();
        parentNode.getBoundsInScreen(bounds);

        for (int i = 0; i < parentNode.getChildCount(); i++) {
            AccessibilityNodeInfo childNode = parentNode.getChild(i);
            if (childNode != null) {
                Rect childBounds = new Rect();
                childNode.getBoundsInScreen(childBounds);
                if (childBounds.contains(x,y)) {
                    AccessibilityNodeInfo foundNode = findNodeAtCoordinates(childNode, x, y);
                    if (foundNode != null) {
                        return foundNode;
                    }
                }
            }
        }
        if (bounds.contains(x, y) && parentNode.isClickable()) {
            return parentNode;
        }
        return null;
    }
}