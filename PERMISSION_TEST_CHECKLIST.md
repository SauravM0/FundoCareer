# Permission Test Checklist — Android 12/13/14/15

## Permission Inventory

| Permission | Declared | Runtime Requested | When | Status |
|---|---|---|---|---|
| `INTERNET` | Yes | No (install-time) | Always | ✅ Required |
| `ACCESS_NETWORK_STATE` | Yes | No (install-time) | Always | ✅ Required |
| `RECORD_AUDIO` | Yes | Yes | Mock interview only | ✅ Required |
| `WRITE_EXTERNAL_STORAGE` | Yes (maxSdkVersion=28) | No | Pre-Android 10 downloads only | ✅ Required |
| `MODIFY_AUDIO_SETTINGS` | **Removed** | No | — | ❌ Unused |
| `DOWNLOAD_WITHOUT_NOTIFICATION` | **Removed** | No | — | ❌ Unused |
| `POST_NOTIFICATIONS` | **Removed** | No | (Add back when feature is ready) | ⏸️ Deferred |
| `CAMERA` | Never declared | No | — | ✅ Denied in WebView |

---

## 1. Manifest & Installation Test (all API levels)

- [ ] **1.1** Install the app on device/emulator running Android 12 (API 31), 13 (API 33), 14 (API 34), 15 (API 35).
- [ ] **1.2** Verify `MODIFY_AUDIO_SETTINGS` is NOT in the merged manifest.
- [ ] **1.3** Verify `DOWNLOAD_WITHOUT_NOTIFICATION` is NOT in the merged manifest.
- [ ] **1.4** Verify `POST_NOTIFICATIONS` is NOT in the merged manifest.
- [ ] **1.5** Verify only 4 permissions appear in Settings > App info > Permissions:
      `INTERNET`, `ACCESS_NETWORK_STATE`, `RECORD_AUDIO`, `WRITE_EXTERNAL_STORAGE`.
- [ ] **1.6** Verify `WRITE_EXTERNAL_STORAGE` only applies up to API 28 (Android 9).
- [ ] **1.7** Verify *no* permission prompt is shown at first launch / login.

---

## 2. Microphone (Mock Interview) — All API Levels

### 2.1 First request flow
- [ ] **2.1.1** Navigate to `/mock-interview` page.
- [ ] **2.1.2** Tap "Start Interview" / mic button.
- [ ] **2.1.3** **Expect:** Explanation dialog appears with title *"Prepare for Your Mock Interview"* and message explaining mic use.
- [ ] **2.1.4** Tap **"Continue"**.
- [ ] **2.1.5** **Expect:** System permission dialog for `RECORD_AUDIO`.
- [ ] **2.1.6** Tap **"Allow"**.
- [ ] **2.1.7** **Expect:** Mic starts, red "● Recording" indicator shows, interview proceeds.

### 2.2 Deny flow
- [ ] **2.2.1** Repeat 2.1.1–2.1.4.
- [ ] **2.2.2** Tap **"Deny"** on system dialog.
- [ ] **2.2.3** **Expect:** Mic request denied, Toast "Microphone access is needed for mock interviews", interview does NOT record.

### 2.3 Deny permanently flow
- [ ] **2.3.1** Deny twice or select "Don't ask again".
- [ ] **2.3.2** Navigate to `/mock-interview` and tap mic button.
- [ ] **2.3.3** **Expect:** Denied dialog *"Microphone required — Go to Settings / Dismiss"*.
- [ ] **2.3.4** Tap **"Go to Settings"** → should open App Settings.
- [ ] **2.3.5** Enable mic permission, return to app.
- [ ] **2.3.6** **Expect:** Mic works after returning.

### 2.4 Cancel explanation dialog
- [ ] **2.4.1** Explanation dialog appears → tap **"Not now"**.
- [ ] **2.4.2** **Expect:** Mic request cancelled, no system dialog, interview does not record.

### 2.5 No mic hardware
- [ ] **2.5.1** Run on device without mic (or emulator without mic).
- [ ] **2.5.2** Navigate to `/mock-interview`.
- [ ] **2.5.3** **Expect:** Toast "No microphone available on this device", no dialog.

### 2.6 Non-mock URL
- [ ] **2.6.1** Navigate to any non-mock page (e.g. Home, Profile).
- [ ] **2.6.2** Page tries to request mic in WebView (e.g. via JS).
- [ ] **2.6.3** **Expect:** Request denied silently, Toast "Microphone is only available during mock interviews".

---

## 3. WebView Permission Granting — All API Levels

- [ ] **3.1** Verify `onPermissionRequest` only grants `RESOURCE_AUDIO_CAPTURE` on `/mock-interview` URLs.
- [ ] **3.2** Verify `onPermissionRequest` denies all requests containing:
      `RESOURCE_VIDEO_CAPTURE`, `RESOURCE_PROTECTED_MEDIA_ID`, `RESOURCE_MIDI_SYSEX`.
- [ ] **3.3** Verify mixed requests (audio + video) are denied entirely.
- [ ] **3.4** Verify `onGeolocationPermissionsShowPrompt` always denies (callback.invoke with false).
- [ ] **3.5** Verify `onShowFileChooser` still works (picks PDF/doc/image via SAF, no permissions needed).

---

## 4. No Permission at Login — All API Levels

- [ ] **4.1** Fresh install → launch app.
- [ ] **4.2** Tap "Sign in with Google".
- [ ] **4.3** **Expect:** Only Google account picker appears. No `RECORD_AUDIO` or other permission dialogs.
- [ ] **4.4** Complete login successfully.
- [ ] **4.5** **Expect:** User lands on Home page, no permission prompts.

---

## 5. File Upload via SAF — All API Levels

- [ ] **5.1** Navigate to page with file upload (e.g. Resume upload).
- [ ] **5.2** Tap "Choose File" / "Upload".
- [ ] **5.3** **Expect:** System document picker opens (SAF), NOT a storage permission request.
- [ ] **5.4** Select a PDF — upload proceeds.
- [ ] **5.5** Repeat with multi-file selection.
- [ ] **5.6** Cancel the picker — no crash, no leaked callback.

---

## 6. File Download — API < 33 (legacy) & 33+ (scoped)

- [ ] **6.1** Tap a download link in WebView.
- [ ] **6.2** **Expect:** Download starts via `DownloadManager`, notification shows, file appears in Downloads folder.
- [ ] **6.3** On Android 10+: verify file saved via `MediaStore.Downloads` with `IS_PENDING=1 → 0`.
- [ ] **6.4** On Android 9 and below: verify `WRITE_EXTERNAL_STORAGE` used (not requested since `maxSdkVersion=28`).
- [ ] **6.5** Test blob download (e.g. ATS report) → verify `downloadBlob()` bridge works and file saved to Downloads.

---

## 7. Notification Permissions — All API Levels

- [ ] **7.1** Verify `POST_NOTIFICATIONS` is NOT declared in manifest.
- [ ] **7.2** Verify notification permission is never requested by the app.
- [ ] **7.3** Verify system is not shown any notification prompt.
- [ ] **7.4** (Future) Add `POST_NOTIFICATIONS` back to manifest AND add contextual request flow before re-enabling.

---

## 8. Cross-App & Background — All API Levels

- [ ] **8.1** No battery optimization request is made at any point.
- [ ] **8.2** No autostart permission request.
- [ ] **8.3** No background restriction request.
- [ ] **8.4** Verify `openBatteryOptimizationSettings()` in `PermissionCoordinator` is never called (method is available but only for future opt-in use).

---

## Android Version-Specific Details

| Behavior | Android 12 (API 31) | Android 13 (API 33) | Android 14 (API 34) | Android 15 (API 35) |
|---|---|---|---|---|
| `RECORD_AUDIO` runtime dialog | ✅ Standard | ✅ Standard | ✅ Standard | ✅ Standard |
| `POST_NOTIFICATIONS` | N/A (auto-granted) | ✅ Runtime required | ✅ Runtime required | ✅ Runtime required |
| **Our app** | No notification prompt | No notification prompt | No notification prompt | No notification prompt |
| `WRITE_EXTERNAL_STORAGE` | Not used (scoped) | Not used (scoped) | Not used (scoped) | Not used (scoped) |
| `READ_MEDIA_*` (images/audio/video) | Not requested | Not requested | Not requested | Not requested |
| SAF file picker | ✅ Works | ✅ Works | ✅ Works | ✅ Works |
| `MediaStore.Downloads` | ✅ Works | ✅ Works | ✅ Works | ✅ Works |
| Permission auto-reset | ✅ May reset if unused | ✅ May reset if unused | ✅ May reset if unused | ✅ May reset if unused |
| Foreground service type | N/A | N/A | N/A | N/A |

---

## Regression Test

- [ ] Google Sign-In works without any permission prompt.
- [ ] Tab navigation (Home → Resume → Jobs → ATS → Profile) works.
- [ ] Payment links open in Custom Tabs.
- [ ] Offline overlay shows when airplane mode enabled, hides when reconnected.
- [ ] Top progress bar shows on page navigation (thin bar, no full-screen overlay except first load).
- [ ] App-mode CSS injections work (no website header/footer, bottom padding for nav capsule).
