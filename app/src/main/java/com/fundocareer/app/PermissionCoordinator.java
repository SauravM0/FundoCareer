package com.fundocareer.app;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.DownloadManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.PowerManager;
import android.provider.MediaStore;
import android.provider.Settings;
import android.webkit.MimeTypeMap;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

public class PermissionCoordinator {

    public interface PermissionCallback {
        void onGranted();
        void onDenied(boolean neverAskAgain);
    }

    public static boolean hasMicrophoneHardware(Context context) {
        try {
            return context.getPackageManager().hasSystemFeature(
                    PackageManager.FEATURE_MICROPHONE);
        } catch (Exception e) {
            return true;
        }
    }

    public static boolean hasMicrophonePermission(Context context) {
        try {
            return ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean hasCameraPermission(Context context) {
        try {
            return ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean hasNotificationPermission(Context context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                return ContextCompat.checkSelfPermission(
                        context, android.Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED;
            }
            return true;
        } catch (Exception e) {
            return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU;
        }
    }

    public static boolean hasExactAlarmPermission(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
                return am != null && am.canScheduleExactAlarms();
            } catch (Exception e) {
                return false;
            }
        }
        return true;
    }

    public static boolean isIgnoringBatteryOptimizations(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
                return pm != null && pm.isIgnoringBatteryOptimizations(context.getPackageName());
            } catch (Exception e) {
                return false;
            }
        }
        return true;
    }

    public static boolean areNotificationsEnabled(Context context) {
        try {
            return NotificationManagerCompat.from(context).areNotificationsEnabled();
        } catch (Exception e) {
            return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU;
        }
    }

    public static boolean shouldShowRationale(Activity activity, String permission) {
        try {
            return ActivityCompat.shouldShowRequestPermissionRationale(activity, permission);
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isNeverAskAgain(Activity activity, String permission) {
        try {
            return !shouldShowRationale(activity, permission)
                    && ContextCompat.checkSelfPermission(activity, permission)
                    != PackageManager.PERMISSION_GRANTED;
        } catch (Exception e) {
            return false;
        }
    }

    public static void openAppSettings(Activity activity) {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + activity.getPackageName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
            Toast.makeText(activity,
                    "Please enable the permission in Settings",
                    Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(activity,
                    "Unable to open settings", Toast.LENGTH_SHORT).show();
        }
    }

    public static void openBatteryOptimizationSettings(Activity activity) {
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + activity.getPackageName()));
            activity.startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(activity,
                    "Battery optimization settings unavailable",
                    Toast.LENGTH_SHORT).show();
        }
    }

    public static void openExactAlarmSettings(Activity activity) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Intent intent = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM);
                intent.setData(Uri.parse("package:" + activity.getPackageName()));
                activity.startActivity(intent);
            } else {
                openAppSettings(activity);
            }
        } catch (Exception e) {
            openAppSettings(activity);
        }
    }

    public static void openNotificationSettings(Activity activity) {
        try {
            Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            intent.putExtra(Settings.EXTRA_APP_PACKAGE, activity.getPackageName());
            activity.startActivity(intent);
        } catch (Exception e) {
            openAppSettings(activity);
        }
    }

    public static void openDataUsageSettings(Activity activity) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Intent intent = new Intent(Settings.ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS);
                intent.setData(Uri.parse("package:" + activity.getPackageName()));
                activity.startActivity(intent);
            } else {
                openAppSettings(activity);
            }
        } catch (Exception e) {
            openAppSettings(activity);
        }
    }

    public static void openAutostartGuidance(Activity activity) {
        try {
            Toast.makeText(activity,
                    "Open Settings > Apps > FundoCareer and enable autostart",
                    Toast.LENGTH_LONG).show();
            openAppSettings(activity);
        } catch (Exception e) {
            Toast.makeText(activity,
                    "Please enable autostart for FundoCareer in system Settings",
                    Toast.LENGTH_LONG).show();
        }
    }

    public static void showExplanationDialog(Activity activity,
                                              String title,
                                              String message,
                                              Runnable onContinue,
                                              Runnable onCancel) {
        try {
            new AlertDialog.Builder(activity, android.R.style.Theme_Material_Light_Dialog)
                    .setTitle(title)
                    .setMessage(message)
                    .setPositiveButton("Continue", (d, w) -> {
                        if (onContinue != null) onContinue.run();
                    })
                    .setNegativeButton("Not now", (d, w) -> {
                        if (onCancel != null) onCancel.run();
                    })
                    .setCancelable(false)
                    .show();
        } catch (Exception e) {
            if (onContinue != null) onContinue.run();
        }
    }

    public static void showPermissionDeniedDialog(Activity activity,
                                                   String permissionName,
                                                   Runnable onGoToSettings,
                                                   Runnable onDismiss) {
        try {
            new AlertDialog.Builder(activity, android.R.style.Theme_Material_Light_Dialog)
                    .setTitle(permissionName + " required")
                    .setMessage("This feature needs " + permissionName
                            + " access. Please enable it in Settings.")
                    .setPositiveButton("Go to Settings", (d, w) -> {
                        if (onGoToSettings != null) onGoToSettings.run();
                    })
                    .setNegativeButton("Dismiss", (d, w) -> {
                        if (onDismiss != null) onDismiss.run();
                    })
                    .setCancelable(false)
                    .show();
        } catch (Exception e) {
            if (onGoToSettings != null) onGoToSettings.run();
        }
    }

    public static void saveFileToDownloads(Context context,
                                            byte[] data,
                                            String fileName,
                                            String mimeType) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
                values.put(MediaStore.Downloads.MIME_TYPE, mimeType);
                values.put(MediaStore.Downloads.IS_PENDING, 1);

                Uri uri = context.getContentResolver().insert(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri != null) {
                    try (java.io.OutputStream os = context.getContentResolver().openOutputStream(uri)) {
                        if (os != null) {
                            os.write(data);
                        }
                    }
                    values.clear();
                    values.put(MediaStore.Downloads.IS_PENDING, 0);
                    context.getContentResolver().update(uri, values, null, null);
                }
            } else {
                String ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType);
                if (ext == null) ext = "dat";
                java.io.File dir = Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS);
                java.io.File file = new java.io.File(dir, fileName);
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(file)) {
                    fos.write(data);
                }
            }
        } catch (Exception e) {
            android.util.Log.e("FundoCareer-Perm",
                    "Failed to save file: " + fileName, e);
        }
    }

    public static long downloadFile(Context context,
                                     String url,
                                     String fileName,
                                     String description) {
        try {
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            request.setTitle(fileName);
            request.setDescription(description);
            request.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setMimeType(MimeTypeMap.getFileExtensionFromUrl(url) != null
                    ? MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                            MimeTypeMap.getFileExtensionFromUrl(url))
                    : "application/octet-stream");

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
                String mime = MimeTypeMap.getFileExtensionFromUrl(url);
                if (mime != null) {
                    String mt = MimeTypeMap.getSingleton().getMimeTypeFromExtension(mime);
                    if (mt != null) values.put(MediaStore.Downloads.MIME_TYPE, mt);
                }
                values.put(MediaStore.Downloads.IS_PENDING, 1);
                Uri destUri = context.getContentResolver().insert(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (destUri != null) {
                    request.setDestinationUri(destUri);
                } else {
                    request.setDestinationInExternalPublicDir(
                            Environment.DIRECTORY_DOWNLOADS, fileName);
                }
            } else {
                request.setDestinationInExternalPublicDir(
                        Environment.DIRECTORY_DOWNLOADS, fileName);
            }

            request.allowScanningByMediaScanner();

            DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm != null) {
                return dm.enqueue(request);
            }
        } catch (Exception e) {
            android.util.Log.e("FundoCareer-Perm",
                    "Failed to download: " + url, e);
        }
        return -1;
    }

    public static void shareFile(Context context, Uri fileUri, String mimeType) {
        try {
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType(mimeType);
            shareIntent.putExtra(Intent.EXTRA_STREAM, fileUri);
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            context.startActivity(Intent.createChooser(shareIntent, "Share"));
        } catch (Exception e) {
            android.util.Log.e("FundoCareer-Perm",
                    "Failed to share file", e);
            Toast.makeText(context, "Unable to share", Toast.LENGTH_SHORT).show();
        }
    }
}
