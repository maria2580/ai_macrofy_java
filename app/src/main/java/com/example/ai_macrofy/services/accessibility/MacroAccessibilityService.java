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
import android.os.Message;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import com.example.ai_macrofy.services.foreground.MyForegroundService;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.Deque;

public class MacroAccessibilityService extends AccessibilityService {

    private static final String TAG = "MacroAccessibilityService";
    public static MacroAccessibilityService instance;
    private Handler actionHandler;
    private static final int MSG_EXECUTE_NEXT_ACTION = 1;
    private JSONArray actionsQueue;
    private int currentActionIndex = 0;

    // 제스처 완료 후 다음 액션을 실행하기 위한 콜백
    private final GestureResultCallback gestureResultCallback = new GestureResultCallback() {
        @Override
        public void onCompleted(GestureDescription gestureDescription) {
            super.onCompleted(gestureDescription);
            Log.i(TAG, "Gesture COMPLETED for action index: " + (currentActionIndex - 1));
            // 제스처 완료 후 100ms 딜레이를 주어 UI가 안정될 시간을 줌
            actionHandler.sendEmptyMessageDelayed(MSG_EXECUTE_NEXT_ACTION, 100);
        }

        @Override
        public void onCancelled(GestureDescription gestureDescription) {
            super.onCancelled(gestureDescription);
            String failureMessage = "Gesture CANCELLED for action index: " + (currentActionIndex - 1);
            Log.e(TAG, failureMessage);
            if (MyForegroundService.instance != null) {
                MyForegroundService.instance.reportActionCompleted(false, failureMessage);
            }
        }
    };

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        // 액션을 순차적으로 처리하기 위한 핸들러 초기화
        actionHandler = new Handler(Looper.getMainLooper()) {
            @Override
            public void handleMessage(Message msg) {
                if (msg.what == MSG_EXECUTE_NEXT_ACTION) {
                    executeNextAction();
                }
            }
        };
        instance = this;
        Log.d(TAG, "Service connected and handler initialized.");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override
    public void onInterrupt() {
        Log.w(TAG, "Service interrupted");
        if (actionHandler != null) actionHandler.removeCallbacksAndMessages(null);
        instance = null;
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "Service destroyed");
        if (actionHandler != null) actionHandler.removeCallbacksAndMessages(null);
        instance = null;
        super.onDestroy();
    }

    /**
     * AI로부터 받은 JSON 액션 목록을 실행합니다.
     * @param json 실행할 액션이 담긴 JSON 문자열
     */
    public void executeActionsFromJson(String json) {
        if (json == null || json.isEmpty()) {
            Log.w(TAG, "JSON string is null or empty.");
            return;
        }
        try {
            // 마크다운 형식 정리
            if (json.startsWith("```json")) {
                json = json.substring(7, json.length() - 3).trim();
            } else if (json.startsWith("```")) {
                json = json.substring(3, json.length() - 3).trim();
            }

            JSONObject jsonObject = new JSONObject(json);
            actionsQueue = jsonObject.optJSONArray("actions");

            if (actionsQueue == null || actionsQueue.length() == 0) {
                Log.w(TAG, "No actions found in JSON to execute.");
                if (MyForegroundService.instance != null) {
                    MyForegroundService.instance.reportActionCompleted(true, "No actions in JSON");
                }
                return;
            }

            // 액션 큐 실행 시작
            currentActionIndex = 0;
            actionHandler.sendEmptyMessage(MSG_EXECUTE_NEXT_ACTION);

        } catch (JSONException e) {
            Log.e(TAG, "JSONException parsing actions: " + e.getMessage(), e);
            if (MyForegroundService.instance != null) {
                MyForegroundService.instance.reportActionCompleted(false, "JSON parsing error: " + e.getMessage());
            }
        }
    }

    /**
     * 액션 큐에서 다음 액션을 꺼내 실행합니다.
     */
    private void executeNextAction() {
        if (actionsQueue == null || currentActionIndex >= actionsQueue.length()) {
            Log.d(TAG, "All actions executed successfully.");
            if (MyForegroundService.instance != null) {
                MyForegroundService.instance.reportActionCompleted(true, null);
            }
            return;
        }

        try {
            JSONObject action = actionsQueue.getJSONObject(currentActionIndex);
            currentActionIndex++; // 다음 액션을 위해 인덱스 증가

            if (!handleAction(action)) {
                String failureMessage = "Action failed: " + action.optString("type");
                Log.e(TAG, failureMessage);
                if (MyForegroundService.instance != null) {
                    MyForegroundService.instance.reportActionCompleted(false, failureMessage);
                }
            }
        } catch (JSONException e) {
            Log.e(TAG, "JSONException during executing next action: " + e.getMessage(), e);
            if (MyForegroundService.instance != null) {
                MyForegroundService.instance.reportActionCompleted(false, "JSON parsing error: " + e.getMessage());
            }
        }
    }

    /**
     * 개별 액션을 타입에 따라 처리합니다.
     * @param action 처리할 액션의 JSONObject
     * @return 액션 처리가 성공적으로 시작되었는지 여부
     */
    private boolean handleAction(JSONObject action) throws JSONException {
        String type = action.optString("type");
        Log.d(TAG, "Handling action " + (currentActionIndex) + "/" + actionsQueue.length() + ": " + type);

        // 제스처 기반 액션들은 콜백에서 다음 액션을 호출하므로, 여기서 sendEmptyMessage를 호출하지 않음
        switch (type) {
            case "scroll": return handleScroll(action);
            case "swipe": case "drag_and_drop": return handleSwipe(action);
            case "touch": return handleTouch(action);
            case "long_touch": return handleLongTouch(action);
            case "double_tap": return handleDoubleTap(action);

            // 즉시 완료되는 액션들은 여기서 다음 액션을 호출
            case "input":
                boolean inputSuccess = handleInput(action);
                if (inputSuccess) actionHandler.sendEmptyMessageDelayed(MSG_EXECUTE_NEXT_ACTION, 500);
                return inputSuccess;
            case "gesture":
                boolean gestureSuccess = handleGlobalGesture(action);
                if (gestureSuccess) actionHandler.sendEmptyMessageDelayed(MSG_EXECUTE_NEXT_ACTION, 500);
                return gestureSuccess;
            case "wait":
                actionHandler.sendEmptyMessageDelayed(MSG_EXECUTE_NEXT_ACTION, action.getLong("duration"));
                return true;
            case "open_application":
                boolean openSuccess = handleOpenApplication(action);
                if(openSuccess) actionHandler.sendEmptyMessageDelayed(MSG_EXECUTE_NEXT_ACTION, 1500); // 앱 전환 시간 대기
                return openSuccess;
            case "done":
                handleDone();
                return true;
            default:
                Log.e(TAG, "Unknown action type: " + type);
                return false;
        }
    }


    private boolean handleTouch(JSONObject action) throws JSONException {
        JSONObject coordinates = action.getJSONObject("coordinates");
        int x = coordinates.getInt("x");
        int y = coordinates.getInt("y");

        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
        if (rootNode != null) {
            AccessibilityNodeInfo targetNode = findClickableNodeAtCoordinates(rootNode, x, y);
            rootNode.recycle();
            if (targetNode != null) {
                Log.i(TAG, "Found clickable node. Performing ACTION_CLICK.");
                boolean success = targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                targetNode.recycle();
                if (success) {
                    actionHandler.sendEmptyMessageDelayed(MSG_EXECUTE_NEXT_ACTION, 500);
                    return true;
                }
                Log.w(TAG, "ACTION_CLICK failed. Falling back to gesture.");
            }
        }

        Log.w(TAG, "No clickable node found or action failed. Using dispatchGesture as fallback.");
        return performGestureTouch(x, y, 100L);
    }

    private boolean handleLongTouch(JSONObject action) throws JSONException {
        JSONObject coordinates = action.getJSONObject("coordinates");
        int x = coordinates.getInt("x");
        int y = coordinates.getInt("y");
        long duration = action.getLong("duration");
        return performGestureTouch(x, y, duration);
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

    private boolean handleInput(JSONObject action) throws JSONException {
        String text = action.getString("text");
        JSONObject coordinates = action.getJSONObject("coordinates");
        int x = coordinates.getInt("x");
        int y = coordinates.getInt("y");
        return performInput(text, x, y);
    }
    /**
     * 'scroll' 액션을 처리합니다.
     * 중심 좌표와 거리를 기반으로 스와이프 제스처를 생성합니다.
     */
    private boolean handleScroll(JSONObject action) throws JSONException {
        String direction = action.getString("direction");
        JSONObject coordinates = action.getJSONObject("coordinates");
        int centerX = coordinates.getInt("x");
        int centerY = coordinates.getInt("y");
        // 'distance' 필드가 없을 경우 기본값 1000으로 설정
        int distance = action.optInt("distance", 1000);

        Log.d(TAG, "Handling scroll. Direction: " + direction + ", Center: (" + centerX + "," + centerY + "), Distance: " + distance);

        return performScroll(direction, centerX, centerY, distance);
    }

    private boolean handleGlobalGesture(JSONObject action) throws JSONException {
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

    private boolean handleOpenApplication(JSONObject action) throws JSONException {
        String packageName = action.optString("application_name");
        if (packageName == null || packageName.isEmpty()) {
            return false;
        }
        Intent intent = getPackageManager().getLaunchIntentForPackage(packageName);
        if (intent == null) {
            return false;
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        return true;
    }

    private void handleDone() {
        Log.d(TAG, "Execution completed.");
        if (MyForegroundService.instance != null) {
            MyForegroundService.instance.stopMacroExecution();
        }
        actionHandler.post(() -> Toast.makeText(getApplicationContext(), "All actions completed.", Toast.LENGTH_LONG).show());
    }
    private boolean performGestureTouch(int x, int y, long duration) {
        if (x < 0 || y < 0) return false;
        Path path = new Path();
        path.moveTo((float) x, (float) y);
        GestureDescription.Builder gestureBuilder = new GestureDescription.Builder();
        gestureBuilder.addStroke(new GestureDescription.StrokeDescription(path, 0L, duration));
        return dispatchGesture(gestureBuilder.build(), gestureResultCallback, null);
    }
    private boolean performDoubleTap(int x, int y) {
        if (x < 0 || y < 0) return false;
        Path path = new Path();
        path.moveTo((float) x, (float) y);
        GestureDescription.Builder gestureBuilder = new GestureDescription.Builder();
        gestureBuilder.addStroke(new GestureDescription.StrokeDescription(path, 0L, 100L));
        gestureBuilder.addStroke(new GestureDescription.StrokeDescription(path, 150L, 100L));
        return dispatchGesture(gestureBuilder.build(), gestureResultCallback, null);
    }

    private boolean performSwipe(int startX, int startY, int endX, int endY, long duration) {
        if (startX < 0 || startY < 0 || endX < 0 || endY < 0) return false;
        Path path = new Path();
        path.moveTo((float) startX, (float) startY);
        path.lineTo((float) endX, (float) endY);
        GestureDescription.Builder gestureBuilder = new GestureDescription.Builder();
        gestureBuilder.addStroke(new GestureDescription.StrokeDescription(path, 0L, duration));
        return dispatchGesture(gestureBuilder.build(), gestureResultCallback, null);
    }
    private boolean performInput(String text, int x, int y) {
        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
        if (rootNode == null) return false;
        AccessibilityNodeInfo targetNode = findClickableNodeAtCoordinates(rootNode, x, y); // Find a node to tap first
        rootNode.recycle();

        if (targetNode != null && targetNode.isEditable()) {
            targetNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            Bundle args = new Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
            boolean success = targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
            targetNode.recycle();
            return success;
        }
        if(targetNode != null) targetNode.recycle();
        return false;
    }
    private boolean performScroll(String direction, int centerX, int centerY, int distance) {
        Path path = new Path();
        int halfDistance = distance / 2;
        int startX, startY, endX, endY;

        switch (direction.toLowerCase()) {
            case "down":
                startX = endX = centerX;
                startY = centerY + halfDistance;
                endY = centerY - halfDistance;
                break;
            case "up":
                startX = endX = centerX;
                startY = centerY - halfDistance;
                endY = centerY + halfDistance;
                break;
            case "left":
                startY = endY = centerY;
                startX = centerX + halfDistance;
                endX = centerX - halfDistance;
                break;
            case "right":
                startY = endY = centerY;
                startX = centerX - halfDistance;
                endX = centerX + halfDistance;
                break;
            default:
                Log.e(TAG, "Unsupported scroll direction: " + direction);
                return false;
        }

        path.moveTo(startX, startY);
        path.lineTo(endX, endY);

        long duration = 200; // 일반적인 스크롤 시간
        GestureDescription.Builder gestureBuilder = new GestureDescription.Builder();
        gestureBuilder.addStroke(new GestureDescription.StrokeDescription(path, 0, duration));

        Log.i(TAG, "Dispatching scroll gesture. Start:(" + startX + "," + startY + "), End:(" + endX + "," + endY + ")");
        return dispatchGesture(gestureBuilder.build(), gestureResultCallback, null);
    }

    private AccessibilityNodeInfo findClickableNodeAtCoordinates(AccessibilityNodeInfo parentNode, int x, int y) {
        if (parentNode == null) return null;

        Deque<AccessibilityNodeInfo> deque = new ArrayDeque<>();
        deque.add(AccessibilityNodeInfo.obtain(parentNode));

        AccessibilityNodeInfo bestMatch = null;

        while (!deque.isEmpty()) {
            AccessibilityNodeInfo currentNode = deque.poll();
            if (currentNode == null) continue;

            Rect currentBounds = new Rect();
            currentNode.getBoundsInScreen(currentBounds);

            if (currentBounds.contains(x, y) && currentNode.isClickable()) {
                if (bestMatch == null) {
                    bestMatch = AccessibilityNodeInfo.obtain(currentNode);
                } else {
                    Rect bestMatchBounds = new Rect();
                    bestMatch.getBoundsInScreen(bestMatchBounds);
                    if ((long)currentBounds.width() * currentBounds.height() < (long)bestMatchBounds.width() * bestMatchBounds.height()) {
                        bestMatch.recycle();
                        bestMatch = AccessibilityNodeInfo.obtain(currentNode);
                    }
                }
            }

            for (int i = 0; i < currentNode.getChildCount(); i++) {
                AccessibilityNodeInfo child = currentNode.getChild(i);
                if (child != null) {
                    deque.add(AccessibilityNodeInfo.obtain(child));
                }
            }
            currentNode.recycle();
        }
        return bestMatch;
    }
}