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

//    public JSONObject extractLayoutInfo() {
//        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
//        if (rootNode == null) {
//            Log.e(TAG, "Root node is null. Cannot extract layout info.");
//            return null;
//        }
//        try {
//            return parseNodeToJson(rootNode);
//        } catch (JSONException e) {
//            Log.e(TAG, "Failed to parse node to JSON", e);
//            return new JSONObject(); // Return empty JSON on error
//        } finally {
//            // Per documentation, nodes obtained from getRootInActiveWindow() should be recycled.
//            // However, child nodes obtained from it should not be recycled if the parent isn't.
//            // Careful with recycling: only recycle if you are sure the node and its children
//            // are not used elsewhere. The original Kotlin code did not recycle.
//            // For safety and to match Kotlin, I'm not recycling here, but be aware.
//            // rootNode.recycle();
//        }
//    }

    public JSONObject extractLayoutInfo() {
        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
        if (rootNode == null) {
            Log.e(TAG, "Root node is null. Cannot extract layout info.");
            return null;
        }

        // 최대 대기 시간 (예: 10초)
        long startTime = System.currentTimeMillis();
        final long MAX_WAIT_TIME = 10000; // 10 seconds

        while (System.currentTimeMillis() - startTime < MAX_WAIT_TIME) {
            // 로딩 중인 요소를 찾습니다.
            if (isLoadingIndicatorPresent(rootNode)) {
                Log.d(TAG, "Loading indicator detected. Waiting...");
                try {
                    Thread.sleep(500); // 0.5초 대기
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    Log.e(TAG, "Waiting interrupted", e);
                    return null; // 대기 중 인터럽트 발생 시 종료
                }
                // 대기 후 루트 노드를 다시 가져와서 최신 상태를 반영
                rootNode = getRootInActiveWindow();
                if (rootNode == null) {
                    Log.e(TAG, "Root node became null after waiting. Cannot extract layout info.");
                    return null;
                }
            } else {
                // 로딩 인디케이터가 없으면 바로 추출
                Log.d(TAG, "No loading indicator found. Proceeding with extraction.");
                break;
            }
        }

        // 최대 대기 시간을 초과했거나 로딩 인디케이터가 사라진 경우
        try {
            return parseNodeToJson(rootNode);
        } catch (JSONException e) {
            Log.e(TAG, "Failed to parse node to JSON", e);
            return new JSONObject(); // Return empty JSON on error
        } finally {
            // ... (recycling logic as before, or carefully considering it)
        }
    }

    /**
     * 주어진 AccessibilityNodeInfo 트리에서 로딩 인디케이터를 찾습니다.
     * 이 메서드는 실제 앱의 UI 특성에 맞게 구현되어야 합니다.
     *
     * @param node 검색을 시작할 노드
     * @return 로딩 인디케이터가 발견되면 true, 그렇지 않으면 false
     */
    private boolean isLoadingIndicatorPresent(AccessibilityNodeInfo node) {
        if (node == null) {
            return false;
        }

        // 1. ProgressBar 클래스 이름을 가진 노드 탐색
        if (node.getClassName() != null && node.getClassName().toString().contains("ProgressBar")) {
            // 추가적으로, visibleToUser 인지 확인하는 것이 좋습니다.
            // if (node.isVisibleToUser()) { return true; }
            return true;
        }

        // 2. 특정 텍스트나 콘텐츠 설명을 가진 로딩 메시지 감지
        if (node.getText() != null && node.getText().toString().toLowerCase().contains("loading")) {
            return true;
        }
        if (node.getContentDescription() != null && node.getContentDescription().toString().toLowerCase().contains("loading")) {
            return true;
        }

        // 3. 자식 노드 재귀적으로 탐색
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (isLoadingIndicatorPresent(child)) {
                // 자식 노드를 사용한 후에는 recycle() 해야 합니다.
                // 하지만 이 경우 재귀 호출이므로, 호출 스택에서 반환될 때마다 recycle() 하지 않고,
                // 최종적으로 이 노드를 더 이상 사용하지 않을 때 recycle() 하는 것이 더 적합합니다.
                // 여기서는 단순히 검색만 하므로 바로 recycle()은 하지 않습니다.
                // 다만, child를 참조하는 로직이 길어지면 직접 recycle()을 고려해야 합니다.
                // AccessibilityNodeInfo.obtain()으로 얻은 것이 아니라면, 해당 노드가 더 이상
                // 필요 없을 때 명시적으로 recycle()을 호출하여 메모리 누수를 방지하는 것이 좋습니다.
                // 하지만 getChild()로 얻은 노드는 부모 노드가 recycle될 때 함께 처리되는 경우가 많으므로
                // 명시적인 recycle()이 불필요할 수도 있습니다.
                // 개발 중인 앱의 특성과 메모리 사용량을 고려하여 결정해야 합니다.
                return true;
            }
            // child.recycle(); // 여기서 바로 recycle()하면 자식 노드의 나머지 탐색에 문제가 생길 수 있습니다.
        }

        return false;
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
        jsonObject.put("visible_to_user",node.isVisibleToUser());
        jsonObject.put("clickable", node.isClickable());
        JSONObject test = new JSONObject();

        test.put("id", node.getViewIdResourceName() != null ? node.getViewIdResourceName() : "");
        //System.out.println(test);
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo childNode = node.getChild(i);
            if (childNode != null) {
                childrenArray.put(parseNodeToJson(childNode));
                // childNode.recycle(); // Be careful, original Kotlin didn't.
            }
        }
        jsonObject.put("children", childrenArray);
        //Log.d(TAG, "parseNodeToJson:"+jsonObject.toString());
        return jsonObject;
    }
}