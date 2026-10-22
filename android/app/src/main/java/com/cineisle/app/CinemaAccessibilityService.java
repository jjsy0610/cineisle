package com.cineisle.app;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Bitmap;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.Executors;
import org.json.JSONObject;

public class CinemaAccessibilityService extends AccessibilityService {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private String foregroundPackage = "";
    private boolean loopStarted = false;

    private final Runnable screenshotLoop = new Runnable() {
        @Override public void run() {
            tryUploadScreenshot();
            handler.postDelayed(this, 5000);
        }
    };

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        setStatus("无障碍服务已连接，等待截图请求");
        startLoop();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event != null && event.getPackageName() != null) {
            foregroundPackage = event.getPackageName().toString();
        }
        startLoop();
    }

    @Override public void onInterrupt() {}

    private void startLoop() {
        if (loopStarted) return;
        loopStarted = true;
        handler.postDelayed(screenshotLoop, 1500);
    }

    private void tryUploadScreenshot() {
        android.content.SharedPreferences sp = getSharedPreferences("cineisle", 0);
        String serverUrl = sp.getString("serverUrl", "");
        String roomId = sp.getString("roomId", "");
        String token = sp.getString("token", "");
        String name = sp.getString("name", "观影人");
        String assistantName = helperName(sp.getString("assistantName", "观影助手"));
        if (serverUrl.length() == 0) { setStatus("截图等待：后端地址为空"); return; }
        if (roomId.length() == 0) { setStatus("截图等待：还没有进入房间"); return; }

        String activePackage = activePackageName();
        if (!getPackageName().equals(activePackage)) {
            setStatus("自动截图已暂停：映屿不在前台");
            return;
        }
        if (!sp.getBoolean("captureBoundsValid", false)) {
            setStatus("截图等待：放映幕还没有准备好");
            return;
        }

        long localReq = sp.getLong("screenshotRequestId", 0);
        long handledLocalReq = sp.getLong("lastHandledScreenshotRequestId", 0);
        boolean localForce = localReq > 0 && localReq != handledLocalReq;
        if (localForce) {
            sp.edit().putLong("lastHandledScreenshotRequestId", localReq).apply();
        }

        boolean remoteForce = checkRemoteRequest(serverUrl, roomId, token, sp, assistantName);
        boolean auto = sp.getBoolean("autoScreenshot", false);
        if (!auto && !localForce && !remoteForce) return;

        // v0.5.2：只在映屿位于前台、且播放器公布了有效边界时截图；
        // 上传前还会裁切到放映幕，避免把聊天抽屉、通知或其他 App 画面交给服务器。

        boolean force = localForce || remoteForce;
        long now = System.currentTimeMillis();
        long last = sp.getLong("lastScreenshotUploadMs", 0);
        long intervalMs = Math.max(10000, sp.getLong("screenshotIntervalMs", 15000));
        if (!force && now - last < intervalMs) return;
        sp.edit().putLong("lastScreenshotUploadMs", now).apply();
        takeAndUpload(serverUrl, roomId, token, name, force ? "accessibility-request" : "accessibility-low-frequency");
    }

    private String activePackageName() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null && root.getPackageName() != null) {
                return root.getPackageName().toString();
            }
        } catch(Exception ignored) {}
        return foregroundPackage == null ? "" : foregroundPackage;
    }

    private String helperName(String s) {
        s = s == null ? "" : s.trim();
        return s.length() > 0 ? s : "观影助手";
    }

    private boolean checkRemoteRequest(String serverUrl, String roomId, String token, android.content.SharedPreferences sp, String assistantName) {
        try {
            String since = sp.getString("lastRemoteScreenshotRequestId", "");
            String qs = "?since=" + java.net.URLEncoder.encode(since, "UTF-8");
            if (token != null && token.length() > 0) qs += "&token=" + java.net.URLEncoder.encode(token, "UTF-8");
            URL url = new URL(serverUrl + "/api/rooms/" + roomId + "/screenshot-request" + qs);
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setRequestMethod("GET");
            c.setConnectTimeout(5000);
            c.setReadTimeout(5000);
            if (token != null && token.length() > 0) {
            c.setRequestProperty("Authorization", "Bearer " + token);
            c.setRequestProperty("X-CineIsle-Token", token);
        }
            int code = c.getResponseCode();
            if (code >= 400) return false;
            String text = readAll(c.getInputStream());
            JSONObject obj = new JSONObject(text);
            if (!obj.optBoolean("pending", false)) return false;
            String requestId = obj.optString("requestId", "");
            if (requestId.length() == 0 || requestId.equals(since)) return false;
            sp.edit().putString("lastRemoteScreenshotRequestId", requestId).apply();
            setStatus("收到" + helperName(assistantName) + "截图请求：" + requestId);
            return true;
        } catch(Exception ignored) {
            return false;
        }
    }

    private String readAll(InputStream is) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) >= 0) bos.write(buf, 0, n);
        return bos.toString("UTF-8");
    }

    private void takeAndUpload(final String serverUrl, final String roomId, final String token, final String name, final String source) {
        if (Build.VERSION.SDK_INT < 30) { setStatus("截图失败：系统版本低于 Android 11"); return; }
        try {
            setStatus("正在请求系统截图…");
            takeScreenshot(Display.DEFAULT_DISPLAY, Executors.newSingleThreadExecutor(), new AccessibilityService.TakeScreenshotCallback() {
                @Override public void onSuccess(AccessibilityService.ScreenshotResult result) {
                    try {
                        HardwareBuffer buffer = result.getHardwareBuffer();
                        Bitmap hw = Bitmap.wrapHardwareBuffer(buffer, result.getColorSpace());
                        if (hw == null) { buffer.close(); setStatus("截图失败：bitmap 为空"); return; }
                        Bitmap bitmap = hw.copy(Bitmap.Config.ARGB_8888, false);
                        buffer.close();
                        Bitmap cropped = cropToPlayer(bitmap);
                        if (cropped == null) {
                            bitmap.recycle();
                            setStatus("截图暂停：无法确定放映幕边界");
                            getSharedPreferences("cineisle", 0).edit().putLong("lastScreenshotUploadMs", 0).apply();
                            return;
                        }
                        if (cropped != bitmap) bitmap.recycle();
                        Bitmap small = resize(cropped, 720);
                        if (small != cropped) cropped.recycle();
                        ByteArrayOutputStream bos = new ByteArrayOutputStream();
                        small.compress(Bitmap.CompressFormat.JPEG, 55, bos);
                        int w = small.getWidth();
                        int h = small.getHeight();
                        small.recycle();
                        String base64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
                        upload(serverUrl, roomId, token, name, base64, w, h, source);
                    } catch(Exception e) {
                        setStatus("截图处理失败：" + e.getClass().getSimpleName());
                        getSharedPreferences("cineisle", 0).edit().putLong("lastScreenshotUploadMs", 0).apply();
                    }
                }

                @Override public void onFailure(int errorCode) {
                    setStatus("系统截图失败：code=" + errorCode + "；请确认无障碍里允许截图能力");
                    getSharedPreferences("cineisle", 0).edit().putLong("lastScreenshotUploadMs", 0).apply();
                }
            });
        } catch(Exception e) {
            setStatus("截图调用失败：" + e.getClass().getSimpleName());
            getSharedPreferences("cineisle", 0).edit().putLong("lastScreenshotUploadMs", 0).apply();
        }
    }

    private Bitmap resize(Bitmap src, int maxWidth) {
        if (src.getWidth() <= maxWidth) return src;
        int w = maxWidth;
        int h = Math.max(1, Math.round(src.getHeight() * (maxWidth / (float)src.getWidth())));
        return Bitmap.createScaledBitmap(src, w, h, true);
    }

    private Bitmap cropToPlayer(Bitmap src) {
        try {
            android.content.SharedPreferences sp = getSharedPreferences("cineisle", 0);
            if (!sp.getBoolean("captureBoundsValid", false)) return null;
            int screenW = sp.getInt("captureScreenWidth", 0);
            int screenH = sp.getInt("captureScreenHeight", 0);
            int left = sp.getInt("captureLeft", -1);
            int top = sp.getInt("captureTop", -1);
            int width = sp.getInt("captureWidth", 0);
            int height = sp.getInt("captureHeight", 0);
            if (screenW <= 0 || screenH <= 0 || left < 0 || top < 0 || width <= 0 || height <= 0) return null;

            float sx = src.getWidth() / (float)screenW;
            float sy = src.getHeight() / (float)screenH;
            int x = Math.max(0, Math.round(left * sx));
            int y = Math.max(0, Math.round(top * sy));
            int w = Math.min(src.getWidth() - x, Math.round(width * sx));
            int h = Math.min(src.getHeight() - y, Math.round(height * sy));
            if (w < 64 || h < 64) return null;
            return Bitmap.createBitmap(src, x, y, w, h);
        } catch(Exception ignored) {
            return null;
        }
    }

    private void upload(String serverUrl, String roomId, String token, String name, String base64, int width, int height, String source) throws Exception {
        String upPath = "/api/rooms/" + roomId + "/screenshot";
        if (token != null && token.length() > 0) upPath += "?token=" + java.net.URLEncoder.encode(token, "UTF-8");
        URL url = new URL(serverUrl + upPath);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setConnectTimeout(10000);
        c.setReadTimeout(10000);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        if (token != null && token.length() > 0) {
            c.setRequestProperty("Authorization", "Bearer " + token);
            c.setRequestProperty("X-CineIsle-Token", token);
        }
        JSONObject body = new JSONObject();
        if (token != null && token.length() > 0) body.put("token", token);
        body.put("actor", name);
        body.put("mime", "image/jpeg");
        body.put("imageBase64", base64);
        body.put("width", width);
        body.put("height", height);
        body.put("source", source);
        body.put("note", "映屿画面同步：仅在映屿前台运行，并只上传放映幕裁切区域。");
        try(OutputStream os = c.getOutputStream()) {
            os.write(body.toString().getBytes("UTF-8"));
        }
        int code = c.getResponseCode();
        if (code >= 400) {
            InputStream es = c.getErrorStream();
            String err = es == null ? "" : readAll(es);
            throw new RuntimeException("HTTP " + code + (err.length() > 0 ? (" " + err) : ""));
        }
        setStatus("截图已上传：" + width + "×" + height + "，HTTP " + code);
    }

    private void setStatus(String s) {
        getSharedPreferences("cineisle", 0).edit()
                .putString("lastScreenshotStatus", s)
                .putLong("lastScreenshotStatusAt", System.currentTimeMillis())
                .apply();
    }
}
