package com.fundocareer.app;

import android.app.Activity;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.MimeTypeMap;
import android.webkit.WebView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

public class FileBridge {

    private static final String TAG = "FundoCareer-File";
    private static final long DEFAULT_MAX_UPLOAD_SIZE = 20 * 1024 * 1024;

    private final Activity activity;
    private final WebView webView;

    public FileBridge(Activity activity, WebView webView) {
        this.activity = activity;
        this.webView = webView;
    }

    @JavascriptInterface
    public String getFileInfo(String uriString) {
        try {
            Uri uri = Uri.parse(uriString);
            JSONObject info = new JSONObject();
            info.put("name", getFileName(uri));
            info.put("mimeType", resolveMimeType(uri));
            info.put("size", getFileSize(uri));
            info.put("extension", getExtension(getFileName(uri)));
            return info.toString();
        } catch (Exception e) {
            Log.e(TAG, "getFileInfo error", e);
            return "{}";
        }
    }

    @JavascriptInterface
    public String validateFile(String uriString, int maxSizeMB, String allowedTypesJson) {
        try {
            JSONObject result = new JSONObject();
            Uri uri = Uri.parse(uriString);
            String mimeType = resolveMimeType(uri);
            long size = getFileSize(uri);
            String ext = getExtension(getFileName(uri));
            long maxBytes = maxSizeMB > 0 ? (long) maxSizeMB * 1024 * 1024 : DEFAULT_MAX_UPLOAD_SIZE;

            if (size > maxBytes) {
                result.put("valid", false);
                result.put("error", "File too large. Maximum size is " + maxSizeMB + "MB.");
                return result.toString();
            }

            if (allowedTypesJson != null && !allowedTypesJson.isEmpty()) {
                JSONArray allowed = new JSONArray(allowedTypesJson);
                boolean typeOk = false;
                for (int i = 0; i < allowed.length(); i++) {
                    String t = allowed.getString(i).trim().toLowerCase(Locale.ROOT);
                    if (t.startsWith(".") && t.substring(1).equals(ext)) { typeOk = true; break; }
                    if (!t.startsWith(".") && mimeType != null && mimeType.startsWith(t)) { typeOk = true; break; }
                }
                if (!typeOk) {
                    result.put("valid", false);
                    result.put("error", "File type not supported. Please select PDF, DOC, or DOCX.");
                    return result.toString();
                }
            }

            result.put("valid", true);
            result.put("error", "");
            return result.toString();
        } catch (Exception e) {
            Log.e(TAG, "validateFile error", e);
            return "{\"valid\":false,\"error\":\"Unable to validate file\"}";
        }
    }

    @JavascriptInterface
    public void downloadFile(String url, String fileName) {
        try {
            if (fileName == null || fileName.isEmpty()) {
                fileName = Uri.parse(url).getLastPathSegment();
            }
            if (fileName == null || fileName.isEmpty()) {
                fileName = "download_" + System.currentTimeMillis();
            }
            String ext = MimeTypeMap.getFileExtensionFromUrl(url);
            if (!fileName.contains(".") && ext != null && !ext.isEmpty()) {
                fileName = fileName + "." + ext;
            }
            long id = PermissionCoordinator.downloadFile(activity, url, fileName, "Downloading\u2026");
            if (id >= 0) {
                Toast.makeText(activity, "Download started", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(activity, "Download failed to start", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Log.e(TAG, "downloadFile error", e);
            Toast.makeText(activity, "Download error", Toast.LENGTH_SHORT).show();
        }
    }

    @JavascriptInterface
    public void downloadBlob(String base64Data, String fileName, String mimeType) {
        try {
            if (fileName == null || fileName.isEmpty()) {
                fileName = "download_" + System.currentTimeMillis();
            }
            if (mimeType == null || mimeType.isEmpty()) {
                mimeType = "application/octet-stream";
            }
            final String fName = fileName;
            final String fMime = mimeType;
            byte[] data = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT);
            PermissionCoordinator.saveFileToDownloads(activity, data, fName, fMime);
            activity.runOnUiThread(() -> {
                Toast.makeText(activity, "Downloaded: " + fName, Toast.LENGTH_SHORT).show();
            });
        } catch (Exception e) {
            Log.e(TAG, "downloadBlob error", e);
            activity.runOnUiThread(() -> {
                Toast.makeText(activity, "Download failed", Toast.LENGTH_SHORT).show();
            });
        }
    }

    @JavascriptInterface
    public void shareFile(String uriString, String mimeType) {
        try {
            if (mimeType == null || mimeType.isEmpty()) mimeType = "*/*";
            PermissionCoordinator.shareFile(activity, Uri.parse(uriString), mimeType);
        } catch (Exception e) {
            Log.e(TAG, "shareFile error", e);
            Toast.makeText(activity, "Unable to share file", Toast.LENGTH_SHORT).show();
        }
    }

    @JavascriptInterface
    public void uploadFileWithProgress(String uriString, String uploadUrl, String headersJson, String callbackId) {
        try {
            new UploadTask(Uri.parse(uriString), uploadUrl, headersJson, callbackId).start();
        } catch (Exception e) {
            Log.e(TAG, "uploadFileWithProgress error", e);
            notifyUploadError(callbackId, "Failed to start upload: " + e.getMessage());
        }
    }

    private String getFileName(Uri uri) {
        if (!"content".equals(uri.getScheme())) return uri.getLastPathSegment();
        try (Cursor c = activity.getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) return c.getString(idx);
            }
        } catch (Exception e) {
            Log.w(TAG, "getFileName error", e);
        }
        return uri.getLastPathSegment();
    }

    private String resolveMimeType(Uri uri) {
        try {
            String mt = activity.getContentResolver().getType(uri);
            if (mt != null && !mt.isEmpty()) return mt;
        } catch (Exception e) {
            Log.w(TAG, "resolveMimeType error", e);
        }
        String ext = MimeTypeMap.getFileExtensionFromUrl(uri.toString());
        if (ext != null) {
            String mt = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.toLowerCase());
            if (mt != null) return mt;
        }
        return "application/octet-stream";
    }

    private long getFileSize(Uri uri) {
        try (Cursor c = activity.getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.SIZE);
                if (idx >= 0) return c.getLong(idx);
            }
        } catch (Exception e) {
            Log.w(TAG, "getFileSize error", e);
        }
        return -1;
    }

    private String getExtension(String name) {
        if (name != null && name.contains(".")) {
            return name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        }
        return "";
    }

    private void notifyUploadProgress(String cb, long sent, long total) {
        String js = String.format(Locale.US,
                "window.FileUploadBridge&&window.FileUploadBridge.onProgress&&window.FileUploadBridge.onProgress('%s',%d,%d)",
                cb, sent, total);
        activity.runOnUiThread(() -> { try { webView.evaluateJavascript(js, null); } catch (Exception e) { Log.w(TAG, "progress js err", e); } });
    }

    private void notifyUploadComplete(String cb, String body) {
        String e = body != null ? body.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r") : "";
        String js = "window.FileUploadBridge&&window.FileUploadBridge.onComplete&&window.FileUploadBridge.onComplete('" + cb + "','" + e + "')";
        activity.runOnUiThread(() -> { try { webView.evaluateJavascript(js, null); } catch (Exception ex) { Log.w(TAG, "complete js err", ex); } });
    }

    private void notifyUploadError(String cb, String msg) {
        String e = msg != null ? msg.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r") : "Unknown error";
        String js = "window.FileUploadBridge&&window.FileUploadBridge.onError&&window.FileUploadBridge.onError('" + cb + "','" + e + "')";
        activity.runOnUiThread(() -> { try { webView.evaluateJavascript(js, null); } catch (Exception ex) { Log.w(TAG, "error js err", ex); } });
    }

    private class UploadTask extends Thread {
        private final Uri uri;
        private final String uploadUrl;
        private final String headersJson;
        private final String callbackId;

        UploadTask(Uri uri, String uploadUrl, String headersJson, String callbackId) {
            this.uri = uri;
            this.uploadUrl = uploadUrl;
            this.headersJson = headersJson;
            this.callbackId = callbackId;
        }

        @Override
        public void run() {
            HttpURLConnection conn = null;
            try {
                InputStream fileStream = activity.getContentResolver().openInputStream(uri);
                if (fileStream == null) {
                    notifyUploadError(callbackId, "Cannot read file");
                    return;
                }

                String boundary = "Boundary-" + System.currentTimeMillis();
                String fileName = getFileName(uri);
                String mimeType = resolveMimeType(uri);
                long fileSize = getFileSize(uri);

                URL url = new URL(uploadUrl);
                conn = (HttpURLConnection) url.openConnection();
                conn.setDoOutput(true);
                conn.setDoInput(true);
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
                conn.setConnectTimeout(30000);
                conn.setReadTimeout(60000);

                if (headersJson != null && !headersJson.isEmpty()) {
                    try {
                        JSONObject headers = new JSONObject(headersJson);
                        JSONArray names = headers.names();
                        if (names != null) {
                            for (int i = 0; i < names.length(); i++) {
                                String k = names.getString(i);
                                conn.setRequestProperty(k, headers.getString(k));
                            }
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "header parse err", e);
                    }
                }

                String headerStart = "--" + boundary + "\r\n" +
                        "Content-Disposition: form-data; name=\"file\"; filename=\"" +
                        (fileName != null ? fileName : "file") + "\"\r\n" +
                        "Content-Type: " + mimeType + "\r\n\r\n";
                String headerEnd = "\r\n--" + boundary + "--\r\n";
                byte[] startBytes = headerStart.getBytes("UTF-8");
                byte[] endBytes = headerEnd.getBytes("UTF-8");

                long totalSize = fileSize > 0 ? fileSize + startBytes.length + endBytes.length : -1;
                if (totalSize > 0) {
                    conn.setFixedLengthStreamingMode(totalSize);
                }

                OutputStream os = conn.getOutputStream();
                os.write(startBytes);

                byte[] buf = new byte[8192];
                int read;
                long sent = 0;
                while ((read = fileStream.read(buf)) != -1) {
                    os.write(buf, 0, read);
                    sent += read;
                    long fileContentLength = fileSize > 0 ? totalSize - startBytes.length - endBytes.length : -1;
                    if (fileContentLength > 0) {
                        notifyUploadProgress(callbackId, sent, fileContentLength);
                    }
                }

                os.write(endBytes);
                os.flush();
                os.close();
                fileStream.close();

                int code = conn.getResponseCode();
                StringBuilder resp = new StringBuilder();
                try {
                    InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
                    if (is != null) {
                        BufferedReader r = new BufferedReader(new InputStreamReader(is, "UTF-8"));
                        String l;
                        while ((l = r.readLine()) != null) resp.append(l);
                        r.close();
                    }
                } catch (Exception e) {
                    Log.w(TAG, "resp read err", e);
                }

                if (code >= 200 && code < 300) {
                    notifyUploadComplete(callbackId, resp.toString());
                } else {
                    notifyUploadError(callbackId, "HTTP " + code + ": " + resp);
                }

            } catch (Exception e) {
                Log.e(TAG, "upload error", e);
                notifyUploadError(callbackId, "Upload error: " + e.getMessage());
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
    }
}
