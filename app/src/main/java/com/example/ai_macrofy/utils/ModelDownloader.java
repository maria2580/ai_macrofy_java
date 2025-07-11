package com.example.ai_macrofy.utils;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;

public class ModelDownloader {

    private static final String TAG = "ModelDownloader";

    public interface DownloadCallback {
        void onProgress(int currentFile, int totalFiles, String currentFileName);
        void onComplete(boolean success, String message);
    }

    public static void downloadAndMerge(Context context, List<String> partUrls, String finalFileName, DownloadCallback callback) {
        new Thread(() -> {
            File finalModelFile = new File(context.getFilesDir(), finalFileName);
            File tempDir = new File(context.getCacheDir(), "model_parts");
            if (!tempDir.exists()) {
                tempDir.mkdirs();
            }

            try {
                // 1. Download all parts
                for (int i = 0; i < partUrls.size(); i++) {
                    String url = partUrls.get(i);
                    String partFileName = new File(url).getName();
                    callback.onProgress(i + 1, partUrls.size(), partFileName);
                    downloadFile(url, new File(tempDir, partFileName));
                }

                // 2. Merge all parts
                Log.d(TAG, "All parts downloaded. Starting merge...");
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
                        partFile.delete(); // Clean up part file after merging
                    }
                }
                tempDir.delete(); // Clean up directory
                Log.d(TAG, "Model merged successfully to " + finalModelFile.getAbsolutePath());
                callback.onComplete(true, "Model downloaded and ready.");

            } catch (IOException e) {
                Log.e(TAG, "Download or merge failed", e);
                // Cleanup on failure
                if (finalModelFile.exists()) finalModelFile.delete();
                for (File file : tempDir.listFiles()) file.delete();
                tempDir.delete();
                callback.onComplete(false, "Error: " + e.getMessage());
            }
        }).start();
    }

    private static void downloadFile(String fileURL, File saveFile) throws IOException {
        HttpURLConnection httpConn = null;
        InputStream inputStream = null;
        OutputStream outputStream = null;
        try {
            URL url = new URL(fileURL);
            httpConn = (HttpURLConnection) url.openConnection();
            int responseCode = httpConn.getResponseCode();

            if (responseCode == HttpURLConnection.HTTP_OK) {
                inputStream = httpConn.getInputStream();
                outputStream = new FileOutputStream(saveFile);

                int bytesRead;
                byte[] buffer = new byte[8192];
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                }
            } else {
                throw new IOException("Server replied HTTP code: " + responseCode);
            }
        } finally {
            if (outputStream != null) outputStream.close();
            if (inputStream != null) inputStream.close();
            if (httpConn != null) httpConn.disconnect();
        }
    }
}
