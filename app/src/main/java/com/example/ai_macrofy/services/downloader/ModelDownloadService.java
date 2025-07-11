package com.example.ai_macrofy.services.downloader;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.example.ai_macrofy.R;
import com.example.ai_macrofy.llm.gemma.GemmaManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class ModelDownloadService extends Service {

    private static final String TAG = "ModelDownloadService";
    private static final String CHANNEL_ID = "model_download_channel";
    private static final int NOTIFICATION_ID = 2;

    public static final String ACTION_DOWNLOAD_PROGRESS = "com.example.ai_macrofy.DOWNLOAD_PROGRESS";
    public static final String EXTRA_PROGRESS = "progress";
    public static final String EXTRA_STATUS_TEXT = "status_text";
    public static final String EXTRA_IS_COMPLETE = "is_complete";
    public static final String EXTRA_IS_SUCCESS = "is_success";

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        ArrayList<String> partUrls = intent.getStringArrayListExtra("urls");
        String finalFileName = intent.getStringExtra("fileName");

        Notification notification = createNotification("Initializing download...", 0);
        startForeground(NOTIFICATION_ID, notification);

        new Thread(() -> {
            downloadAndMerge(partUrls, finalFileName);
            stopSelf();
        }).start();

        return START_NOT_STICKY;
    }

    private void downloadAndMerge(List<String> partUrls, String finalFileName) {
        File finalModelFile = new File(getFilesDir(), finalFileName);
        File tempDir = new File(getCacheDir(), "model_parts");
        if (!tempDir.exists()) tempDir.mkdirs();

        try {
            long totalSize = getTotalSize(partUrls);
            if (totalSize <= 0) {
                throw new IOException("Could not determine total download size.");
            }
            long totalBytesDownloaded = 0;

            // 1. Download all parts
            for (int i = 0; i < partUrls.size(); i++) {
                String url = partUrls.get(i);
                String partFileName = new File(url).getName();
                File partFile = new File(tempDir, partFileName);
                long downloadedBytes = downloadFile(url, partFile, i + 1, partUrls.size(), totalBytesDownloaded, totalSize);
                if (downloadedBytes < 0) throw new IOException("Download failed for part " + (i + 1));
                totalBytesDownloaded += downloadedBytes;
            }

            // 2. Merge all parts
            updateNotification("Merging files...", 100);
            broadcastProgress("Merging files...", 100, false, false);

            try (OutputStream out = new FileOutputStream(finalModelFile)) {
                for (int i = 0; i < partUrls.size(); i++) {
                    String partFileName = new File(partUrls.get(i)).getName();
                    File partFile = new File(tempDir, partFileName);
                    try (InputStream in = partFile.toURI().toURL().openStream()) {
                        byte[] buffer = new byte[8192];
                        int read;
                        while ((read = in.read(buffer)) != -1) {
                            out.write(buffer, 0, read);
                        }
                    }
                    partFile.delete();
                }
            }
            tempDir.delete();
            updateNotification("Download complete", -1); // Use indeterminate for final state
            broadcastProgress("Download complete!", 100, true, true);

        } catch (IOException e) {
            Log.e(TAG, "Download or merge failed", e);
            if (finalModelFile.exists()) finalModelFile.delete();
            if (tempDir.exists()) {
                for (File file : tempDir.listFiles()) file.delete();
                tempDir.delete();
            }
            updateNotification("Download failed", -1);
            broadcastProgress("Download failed: " + e.getMessage(), -1, true, false);
        }
    }

    private long downloadFile(String fileURL, File saveFile, int currentFile, int totalFiles, long alreadyDownloaded, long totalSize) throws IOException {
        HttpURLConnection httpConn = null;
        InputStream inputStream = null;
        OutputStream outputStream = null;
        try {
            URL url = new URL(fileURL);
            httpConn = (HttpURLConnection) url.openConnection();
            int responseCode = httpConn.getResponseCode();

            if (responseCode == HttpURLConnection.HTTP_OK) {
                long fileLength = httpConn.getContentLengthLong();
                if (fileLength == -1) throw new IOException("Content-Length not available for " + fileURL);
                inputStream = httpConn.getInputStream();
                outputStream = new FileOutputStream(saveFile);

                long total = 0;
                byte[] buffer = new byte[8192];
                int bytesRead;

                long lastUpdateTime = System.currentTimeMillis();
                long lastUpdateBytes = 0;

                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    total += bytesRead;
                    outputStream.write(buffer, 0, bytesRead);

                    long currentTime = System.currentTimeMillis();
                    if (currentTime - lastUpdateTime > 500) { // Update every 0.5 second
                        long bytesSinceLastUpdate = total - lastUpdateBytes;
                        double speed = bytesSinceLastUpdate / ((currentTime - lastUpdateTime) / 1000.0); // bytes/sec
                        String speedStr = String.format(Locale.US, "%.2f MB/s", speed / (1024 * 1024));

                        int globalProgress = (int) ((alreadyDownloaded + total) * 100 / totalSize);
                        String statusText = String.format(Locale.US, "Downloading part %d/%d\n%s",
                                currentFile, totalFiles, speedStr);

                        updateNotification(statusText, globalProgress);
                        broadcastProgress(statusText, globalProgress, false, false);

                        lastUpdateTime = currentTime;
                        lastUpdateBytes = total;
                    }
                }
                return total;
            } else {
                throw new IOException("Server replied HTTP code: " + responseCode);
            }
        } finally {
            if (outputStream != null) outputStream.close();
            if (inputStream != null) inputStream.close();
            if (httpConn != null) httpConn.disconnect();
        }
    }

    private long getTotalSize(List<String> partUrls) {
        long totalSize = 0;
        for (String urlString : partUrls) {
            HttpURLConnection connection = null;
            try {
                URL url = new URL(urlString);
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("HEAD");
                if (connection.getResponseCode() == HttpURLConnection.HTTP_OK) {
                    long length = connection.getContentLengthLong();
                    if (length == -1) return -1; // If any part fails, abort.
                    totalSize += length;
                } else {
                    return -1; // If any part fails, abort.
                }
            } catch (IOException e) {
                Log.e(TAG, "Could not get size for " + urlString, e);
                return -1; // Indicate error
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }
        return totalSize;
    }

    private void broadcastProgress(String statusText, int progress, boolean isComplete, boolean isSuccess) {
        Intent intent = new Intent(ACTION_DOWNLOAD_PROGRESS);
        intent.putExtra(EXTRA_STATUS_TEXT, statusText);
        intent.putExtra(EXTRA_PROGRESS, progress);
        intent.putExtra(EXTRA_IS_COMPLETE, isComplete);
        intent.putExtra(EXTRA_IS_SUCCESS, isSuccess);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    private Notification createNotification(String contentText, int progress) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Downloading Gemma Model")
                .setContentText(contentText)
                .setSmallIcon(R.drawable.ic_launcher_foreground) // Replace with a proper download icon
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setOnlyAlertOnce(true);

        if (progress >= 0) {
            builder.setProgress(100, progress, false);
        }
        return builder.build();
    }

    private void updateNotification(String contentText, int progress) {
        Notification notification = createNotification(contentText, progress);
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        manager.notify(NOTIFICATION_ID, notification);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "Model Download",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(serviceChannel);
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
