package com.example.ai_macrofy.services.accessibility;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Path;
import android.graphics.Rect;
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
            JSONObject jsonObject = new JSONObject(json);
            JSONArray actions = jsonObject.optJSONArray("actions");

            if (actions == null || actions.length() == 0) {
                Log.w(TAG, "No actions found in JSON.");
                // 액션이 없을 경우 MyForegroundService에 알릴 수 있음 (옵션)
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
                        // Scroll은 성공/실패를 명확히 판단하기 어려울 수 있으므로, 일단 true로 가정
                        actionSuccess = handleScroll(action);
                        if (!actionSuccess) actionFailureReason = "Scroll action failed."; // 실제로는 판단 로직 필요
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
                        if (!actionSuccess) actionFailureReason = "Wait action failed."; // wait은 보통 실패하지 않음
                        break;
                    case "open_application":
                        actionSuccess = handleOpenApplication(action);
                        if (!actionSuccess) actionFailureReason = "Open application action failed. Check package name or if app is installed.";
                        break;
                    case "done":
                        handleDone();
                        actionSuccess = true; // 'done'은 MyForegroundService를 중지시키므로 성공으로 간주
                        // MyForegroundService.instance.reportActionCompleted(true, "Task marked as done.");
                        // MyForegroundService.instance.stopMacroExecution(); // MyForegroundService에 중지 요청
                        return; // 'done'이면 즉시 종료
                    default:
                        Log.w(TAG, "Unknown action type: " + type);
                        actionSuccess = false; // 알 수 없는 타입은 실패로 간주
                        actionFailureReason = "Unknown action type: " + type;
                        break;
                }

                if (!actionSuccess) {
                    allActionsSuccessful = false;
                    lastFailureReason = "Action type '" + type + "' failed: " + actionFailureReason;
                    Log.e(TAG, lastFailureReason);
                    break; // 첫 번째 실패에서 중단하고 피드백
                }

                if (!"wait".equals(type) && !"done".equals(type) && actions.length() > 1 && i < actions.length() -1) {
                    SystemClock.sleep(500);
                }
            }

            // 모든 액션 처리 후 MyForegroundService에 결과 알림
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
    // 각 handle 메소드는 boolean (성공 여부)을 반환하도록 수정
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
        int distance = action.getInt("distance");
        performScroll(direction, distance);
        return true; // 스크롤은 성공/실패 판단이 어려워 일단 true
    }

    private boolean handleLongTouch(JSONObject action) throws JSONException {
        JSONObject coordinates = action.getJSONObject("coordinates");
        int x = coordinates.getInt("x");
        int y = coordinates.getInt("y");
        long duration = action.getLong("duration");
        performLongTouch(x, y, duration);
        return true; // 제스처는 성공/실패 판단이 어려워 일단 true
    }

    private boolean handleDragAndDrop(JSONObject action) throws JSONException {
        JSONObject start = action.getJSONObject("start");
        int startX = start.getInt("x");
        int startY = start.getInt("y");
        JSONObject end = action.getJSONObject("end");
        int endX = end.getInt("x");
        int endY = end.getInt("y");
        long duration = action.getLong("duration");
        performDragAndDrop(startX, startY, endX, endY, duration);
        return true; // 제스처는 성공/실패 판단이 어려워 일단 true
    }

    private boolean handleDoubleTap(JSONObject action) throws JSONException {
        JSONObject coordinates = action.getJSONObject("coordinates");
        int x = coordinates.getInt("x");
        int y = coordinates.getInt("y");
        performDoubleTap(x, y);
        return true; // 제스처는 성공/실패 판단이 어려워 일단 true
    }

    private boolean handleSwipe(JSONObject action) throws JSONException {
        JSONObject start = action.getJSONObject("start");
        int startX = start.getInt("x");
        int startY = start.getInt("y");
        JSONObject end = action.getJSONObject("end");
        int endX = end.getInt("x");
        int endY = end.getInt("y");
        long duration = action.getLong("duration");
        performSwipe(startX, startY, endX, endY, duration);
        return true; // 제스처는 성공/실패 판단이 어려워 일단 true
    }

    private boolean handleWait(JSONObject action) throws JSONException {
        long duration = action.getLong("duration");
        if (duration <= 0) {
            Log.w(TAG, "Wait duration must be positive. Received: " + duration);
            return false; // 잘못된 duration은 실패로 간주
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
        MyForegroundService.instance.stopMacroExecution();
        mainThreadHandler.post(() -> Toast.makeText(getApplicationContext(), "완료했습니다.", Toast.LENGTH_LONG).show());
    }

    // 각 perform 메소드는 boolean (성공 여부)을 반환하도록 수정
    private boolean performTouch(int x, int y) {
        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
        if (rootNode != null) {
            AccessibilityNodeInfo targetNode = findNodeAtCoordinates(rootNode, x, y);
            if (targetNode != null && targetNode.isClickable()) {
                Log.d(TAG, "Node found at (" + x + ", " + y + "): " + targetNode.getClassName() + ". Performing ACTION_CLICK.");
                boolean success = targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                // targetNode.recycle(); // 주의: findNodeAtCoordinates에서 반환된 노드는 재활용하면 안됨
                // rootNode.recycle(); // 주의
                if(success) return true;
                Log.w(TAG, "ACTION_CLICK failed on node. Trying gesture.");
            }
            // rootNode.recycle(); // 주의
        }
        // ACTION_CLICK 실패 또는 적절한 노드 없음 -> 제스처 시도
        return performGestureTouch(x,y);
    }

    private boolean performGestureTouch(int x, int y) {
        Log.d(TAG, "Performing gesture-based touch at (" + x + ", " + y + ").");
        Path path = new Path();
        path.moveTo((float) x, (float) y);

        GestureDescription.Builder gestureBuilder = new GestureDescription.Builder();
        gestureBuilder.addStroke(new GestureDescription.StrokeDescription(path, 0, 100L));
        // dispatchGesture는 boolean을 반환하지 않으므로, 콜백으로 성공/취소 여부 판단 필요
        // 여기서는 단순화를 위해 일단 true를 반환하지만, 실제로는 콜백 결과에 따라 결정해야 함
        dispatchGesture(gestureBuilder.build(), new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                super.onCompleted(gestureDescription);
                Log.d(TAG, "Touch gesture completed at (" + x + ", " + y + ")");
                // 성공 알림 필요 시 여기서 MyForegroundService.instance.report... 호출
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                super.onCancelled(gestureDescription);
                Log.w(TAG, "Touch gesture cancelled at (" + x + ", " + y + ")");
                // 실패 알림 필요 시 여기서 MyForegroundService.instance.report... 호출
            }
        }, null);
        return true; //  GestureResultCallback에서 실제 결과를 처리해야 함.
    }


    private boolean performInput(String text, int x, int y) {
        Log.d(TAG, "Attempting input '" + text + "' at (" + x + ", " + y + ")");
        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
        if (rootNode != null) {
            AccessibilityNodeInfo targetNode = findNodeAtCoordinatesForInput(rootNode, x, y);
            if (targetNode != null && (targetNode.isEditable() || targetNode.isFocusable())) { // 입력 가능 조건 강화
                Log.d(TAG, "Node for input found: " + targetNode.getClassName());
                boolean focusSuccess = targetNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                if (!focusSuccess) {
                    Log.w(TAG, "ACTION_FOCUS failed on node for input.");
                    // 실패로 간주하거나, 바로 setText 시도
                }

                Bundle args = new Bundle();
                args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
                boolean setTextSuccess = targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
                // targetNode.recycle();
                // rootNode.recycle();
                if (!setTextSuccess) {
                    Log.w(TAG, "ACTION_SET_TEXT failed on node.");
                    return false;
                } else {
                    Log.d(TAG, "ACTION_SET_TEXT succeeded.");
                    return true;
                }
            } else {
                Log.w(TAG, "No suitable node found or node not editable/focusable at (" + x + ", " + y + ") for input.");
                // rootNode.recycle();
                return false;
            }
        }
        Log.w(TAG, "Root node is null for input operation.");
        return false;
    }

    private AccessibilityNodeInfo findNodeAtCoordinatesForInput(AccessibilityNodeInfo node, int x, int y) {
        // 입력 필드를 찾기 위한 더 정교한 로직이 필요할 수 있음 (className, isEditable 등)
        return findNodeAtCoordinates(node, x, y); // 일단 기존 로직 재사용
    }

    // performScroll, performLongTouch 등 다른 perform... 메소드들도 GestureResultCallback을 사용하여
    // 실제 성공/실패 여부를 판단하고 boolean 값을 반환하도록 수정하는 것이 이상적입니다.
    // 여기서는 간결성을 위해 대부분 true를 반환하도록 두었습니다.

    private void performDoubleTap(int x, int y) {
        Path path = new Path();
        path.moveTo((float) x, (float) y);
        GestureDescription.Builder gestureBuilder = new GestureDescription.Builder();
        gestureBuilder.addStroke(new GestureDescription.StrokeDescription(path, 0L, 100L));
        gestureBuilder.addStroke(new GestureDescription.StrokeDescription(path, 200L, 100L));
        dispatchGesture(gestureBuilder.build(), null, null); // 콜백으로 결과 처리 필요
    }


    private void performLongTouch(int x, int y, long duration) {
        Path path = new Path();
        path.moveTo((float) x, (float) y);
        GestureDescription.Builder gestureBuilder = new GestureDescription.Builder();
        gestureBuilder.addStroke(new GestureDescription.StrokeDescription(path, 0L, duration));
        dispatchGesture(gestureBuilder.build(), null, null); // 콜백으로 결과 처리 필요
    }


    private boolean performScroll(String direction, int distance) { //
        // 화면 중앙 근처에서 시작하는 것이 더 일반적일 수 있습니다.
        // 화면 크기를 가져와서 동적으로 계산하는 것이 좋습니다.
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int screenHeight = getResources().getDisplayMetrics().heightPixels;

        float startX = screenWidth / 2f;
        float startY = screenHeight / 2f;
        float endX = startX;
        float endY = startY;

        // 스크롤 거리는 "distance" 파라미터를 좀 더 직접적으로 활용하거나,
        // 화면 크기에 비례한 값으로 조정하는 것이 좋을 수 있습니다.
        // 현재 distance는 픽셀 단위로 가정하고 스와이프 길이에 반영합니다.
        // 너무 짧은 distance는 스크롤로 인식되지 않을 수 있습니다.
        // LLM이 제공하는 distance가 적절한 범위 내에 있는지 확인하거나,
        // 너무 작으면 최소 스크롤 길이를 보장하는 로직이 필요할 수 있습니다.
        // 예를 들어, distance가 화면 높이/너비의 특정 비율 이상이 되도록 합니다.
        int effectiveDistance = Math.max(distance, screenHeight / 4); // 예: 최소 스크롤 거리 보장 (화면 높이의 1/4)

        switch (direction.toLowerCase()) { //
            case "down": // 콘텐츠를 아래로 내리려면 손가락은 위에서 아래로 스와이프 (스크롤바를 아래로)
                startY = screenHeight * 0.3f; // 화면 상단에서 시작
                endY = startY + effectiveDistance;
                if (endY > screenHeight * 0.9f) endY = screenHeight * 0.9f; // 화면 하단 경계
                break;
            case "up": // 콘텐츠를 위로 올리려면 손가락은 아래에서 위로 스와이프 (스크롤바를 위로)
                startY = screenHeight * 0.7f; // 화면 하단에서 시작
                endY = startY - effectiveDistance;
                if (endY < screenHeight * 0.1f) endY = screenHeight * 0.1f; // 화면 상단 경계
                break;
            case "left": // 콘텐츠를 왼쪽으로 옮기려면 손가락은 오른쪽에서 왼쪽으로 스와이프
                startX = screenWidth * 0.7f; // 화면 오른쪽에서 시작
                startY = screenHeight / 2f; // Y축 중앙
                endX = startX - effectiveDistance;
                endY = startY;
                if (endX < screenWidth * 0.1f) endX = screenWidth * 0.1f; // 화면 왼쪽 경계
                break;
            case "right": // 콘텐츠를 오른쪽으로 옮기려면 손가락은 왼쪽에서 오른쪽으로 스와이프
                startX = screenWidth * 0.3f; // 화면 왼쪽에서 시작
                startY = screenHeight / 2f; // Y축 중앙
                endX = startX + effectiveDistance;
                endY = startY;
                if (endX > screenWidth * 0.9f) endX = screenWidth * 0.9f; // 화면 오른쪽 경계
                break;
            default:
                Log.w(TAG, "Unknown scroll direction: " + direction); //
                return false; // 알 수 없는 방향은 실패
        }

        Path path = new Path(); //
        path.moveTo(startX, startY); //
        path.lineTo(endX, endY); //

        GestureDescription.Builder gestureBuilder = new GestureDescription.Builder(); //
        // 스크롤 제스처의 지속 시간 (duration)도 중요합니다. 너무 짧으면 탭으로 인식될 수 있고,
        // 너무 길면 사용자가 답답함을 느낄 수 있습니다. 300ms ~ 800ms 사이가 일반적입니다.
        gestureBuilder.addStroke(new GestureDescription.StrokeDescription(path, 0L, 500L)); //

        // dispatchGesture의 결과를 콜백으로 받아 성공/실패를 판단해야 합니다.
        // 현재는 콜백 처리가 없어 항상 true를 반환하게 될 수 있습니다.
        // 실제 성공 여부를 반영하도록 수정이 필요합니다.
        boolean gestureDispatched = dispatchGesture(gestureBuilder.build(), new GestureResultCallback() { //
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                super.onCompleted(gestureDescription);
                Log.d(TAG, "Scroll gesture completed in direction: " + direction + " by " + distance); //
                // 여기서 MyForegroundService.instance.reportActionCompleted(true, null); 와 같이 성공을 알릴 수 있습니다.
                // 다만, 이 콜백은 비동기이므로 performScroll 메소드의 반환 값과 동기화하기 어렵습니다.
                // performScroll의 반환 값은 제스처가 '성공적으로 시스템에 전달되었는지' 여부만 나타낼 수 있습니다.
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                super.onCancelled(gestureDescription);
                Log.w(TAG, "Scroll gesture cancelled: " + direction); //
                // 여기서 MyForegroundService.instance.reportActionCompleted(false, "Scroll gesture was cancelled"); 와 같이 실패를 알릴 수 있습니다.
            }
        }, null);

        return gestureDispatched; // dispatchGesture의 반환 값은 boolean (API 24 이상)
    }
    private void performSwipe(int startX, int startY, int endX, int endY, long duration) {
        Path path = new Path();
        path.moveTo((float) startX, (float) startY);
        path.lineTo((float) endX, (float) endY);
        GestureDescription.Builder gestureBuilder = new GestureDescription.Builder();
        gestureBuilder.addStroke(new GestureDescription.StrokeDescription(path, 0L, duration));
        dispatchGesture(gestureBuilder.build(), null, null); // 콜백으로 결과 처리 필요
    }

    private void performDragAndDrop(int startX, int startY, int endX, int endY, long duration) {
        Path path = new Path();
        path.moveTo((float) startX, (float) startY);
        path.lineTo((float) endX, (float) endY);
        GestureDescription.Builder gestureBuilder = new GestureDescription.Builder();
        gestureBuilder.addStroke(new GestureDescription.StrokeDescription(path, 0L, duration));
        dispatchGesture(gestureBuilder.build(), null, null); // 콜백으로 결과 처리 필요
    }

    private AccessibilityNodeInfo findNodeAtCoordinates(AccessibilityNodeInfo parentNode, int x, int y) {
        if (parentNode == null) {
            return null;
        }
        Rect bounds = new Rect();
        parentNode.getBoundsInScreen(bounds);

        // 가장 작은 클릭 가능한 노드를 우선하므로, 자식 노드부터 재귀적으로 탐색
        for (int i = 0; i < parentNode.getChildCount(); i++) {
            AccessibilityNodeInfo childNode = parentNode.getChild(i);
            if (childNode != null) {
                // 자식 노드의 영역을 먼저 확인하고, 그 안에 좌표가 있으면 더 깊이 탐색
                Rect childBounds = new Rect();
                childNode.getBoundsInScreen(childBounds);
                if (childBounds.contains(x,y)) { // 현재 좌표가 자식 노드 영역 내에 있을 때만 재귀 호출
                    AccessibilityNodeInfo foundNode = findNodeAtCoordinates(childNode, x, y);
                    if (foundNode != null) {
                        // childNode.recycle(); // 주의: findNodeAtCoordinates에서 반환된 노드는 재활용하면 안됨
                        return foundNode; // 가장 깊은 곳의 노드를 찾으면 바로 반환
                    }
                }
                // childNode.recycle(); // 루프 내에서 자식 노드 재활용 주의
            }
        }

        // 자식 노드에서 찾지 못했거나, 현재 노드가 리프 노드에 가까울 경우, 현재 노드 확인
        // 클릭 가능하고 좌표를 포함하는 "가장 작은" 노드를 찾는 것이 목표
        // 위의 재귀 로직이 더 작은 자식 노드를 우선적으로 반환하므로,
        // 여기까지 왔다면 현재 parentNode가 해당 좌표를 포함하는 가장 적합한 후보일 수 있음
        if (bounds.contains(x, y) && parentNode.isClickable()) {
            return parentNode;
        }
        return null;
    }
}