# Media & Permission QA Test Matrix

## Environment
- Device: Android 11+ (API 30+) primary, Android 9-10 secondary
- App: FundoCareer Android (WebView-based)
- Network: WiFi / Mobile data

---

## 1. Microphone (Audio Capture)

| # | Test Case | Steps | Expected Result | Status |
|---|-----------|-------|-----------------|--------|
| 1.1 | Website mic test page | Open website mic test page, click "Test Microphone" | WebView requests `RESOURCE_AUDIO_CAPTURE`, app shows runtime RECORD_AUDIO dialog, mic works after grant | |
| 1.2 | Mic permission already granted | Accept mic permission, revisit mic test page | Request auto-granted (no runtime dialog), mic works immediately | |
| 1.3 | Deny mic permission | Click "Deny" on runtime dialog | `request.deny()` called, website gets clean denial, no crash | |
| 1.4 | Deny mic (never ask again) | Click "Deny" + "Never ask again" | "Microphone" denied dialog shown with "Go to Settings" option, website gets clean denial | |
| 1.5 | No mic hardware | Run on device/emulator without mic | Deny with toast "No microphone available on this device" | |

## 2. Camera (Video Capture)

| # | Test Case | Steps | Expected Result | Status |
|---|-----------|-------|-----------------|--------|
| 2.1 | Website camera test page | Open website camera test, click "Test Camera" | WebView requests `RESOURCE_VIDEO_CAPTURE`, app shows runtime CAMERA dialog, camera works after grant | |
| 2.2 | Camera permission already granted | Accept camera, revisit camera test | Request auto-granted, camera works immediately | |
| 2.3 | Deny camera permission | Click "Deny" on runtime dialog | `request.deny()` called, website gets clean denial, no crash | |
| 2.4 | Deny camera (never ask again) | Click "Deny" + "Never ask again" | "Camera" denied dialog shown with settings link | |

## 3. Combined Mic + Camera

| # | Test Case | Steps | Expected Result | Status |
|---|-----------|-------|-----------------|--------|
| 3.1 | Combined request both granted | Accept both RECORD_AUDIO + CAMERA | WebView granted both `RESOURCE_AUDIO_CAPTURE` + `RESOURCE_VIDEO_CAPTURE` | |
| 3.2 | Combined request both denied | Deny both | `request.deny()` called for both, clean denial | |
| 3.3 | Combined request grant mic only | Accept mic, deny camera | Grant only `RESOURCE_AUDIO_CAPTURE`, website gets partial resources | |
| 3.4 | Combined request grant camera only | Accept camera, deny mic | Grant only `RESOURCE_VIDEO_CAPTURE`, website gets partial resources | |
| 3.5 | Combined already granted | Both permissions pre-approved | Auto-grant both without runtime dialog | |

## 4. File Upload

| # | Test Case | Steps | Expected Result | Status |
|---|-----------|-------|-----------------|--------|
| 4.1 | Upload single file (image) | Click file input on website, select image from gallery | `onShowFileChooser` launches picker, file URI returned to website | |
| 4.2 | Upload single file (document) | Click file input, select PDF from Documents | Document file returned to website | |
| 4.3 | Upload multiple files | Click `<input type="file" multiple>`, select multiple files | `MODE_OPEN_MULTIPLE` path used, multiple URIs returned | |
| 4.4 | Upload audio file | Click audio file input, select audio | File picker opens with audio MIME filter, file returned | |
| 4.5 | Cancel file picker | Open file picker, press back/cancel | `filePathCallback.onReceiveValue(null)` called, website notified | |

## 5. Audio Playback

| # | Test Case | Steps | Expected Result | Status |
|---|-----------|-------|-----------------|--------|
| 5.1 | Auto-play audio | Open page with `<audio autoplay>` | Audio plays without user gesture (`setMediaPlaybackRequiresUserGesture(false)`) | |
| 5.2 | Programmatic audio play | Click button that calls `audio.play()` | Audio plays | |
| 5.3 | Audio in popup window | Open popup with audio content | Audio plays in popup (`setSupportMultipleWindows(true)`) | |

## 6. Video / Interview

| # | Test Case | Steps | Expected Result | Status |
|---|-----------|-------|-----------------|--------|
| 6.1 | Interview feature opens | Navigate to mock-interview page | Page loads without block, mic/camera prompts work | |
| 6.2 | Video element plays | Open page with `<video>` element | Video loads and plays | |
| 6.3 | getUserMedia works | Website calls `navigator.mediaDevices.getUserMedia()` | Permission prompt shown, stream returned on grant | |

## 7. Permission Denial Safety

| # | Test Case | Steps | Expected Result | Status |
|---|-----------|-------|-----------------|--------|
| 7.1 | Deny mic - no crash | Deny mic permission at runtime | App does not crash, website gets clean callback | |
| 7.2 | Deny camera - no crash | Deny camera permission | App does not crash, website gets clean callback | |
| 7.3 | Deny combined - no crash | Deny both in combined request | App does not crash, website gets clean denial | |
| 7.4 | Multiple rapid requests | Quickly trigger permission request, deny, trigger again | Each request handled independently, no crash | |

## 8. Grant Persistence (No Restart)

| # | Test Case | Steps | Expected Result | Status |
|---|-----------|-------|-----------------|--------|
| 8.1 | Grant mic, re-test without restart | Grant mic, navigate to another page, return to mic test | Mic permission persists, request auto-granted | |
| 8.2 | Grant camera, re-test without restart | Grant camera, navigate away and back | Camera permission persists | |
| 8.3 | Grant both, verify in different session | Grant both, close app, reopen | Android OS retains permission grant | |

## 9. Logging

| # | Test Case | Steps | Expected Result | Status |
|---|-----------|-------|-----------------|--------|
| 9.1 | Permission request log | Website requests mic/camera | Logcat shows: `WebView permission request from <origin>: [resources]` | |
| 9.2 | Grant log | Grant permission | Logcat shows: `granting WebView RESOURCE_*` | |
| 9.3 | Deny log | Deny permission | Logcat shows: `Runtime * permission denied, denying WebView request` | |

## 10. Regression

| # | Test Case | Steps | Expected Result | Status |
|---|-----------|-------|-----------------|--------|
| 10.1 | Payment flow unchanged | Navigate to pricing/subscription page | Payment opens in Custom Tab, unchanged | |
| 10.2 | Login flow unchanged | Tap Sign In | Google Sign-In flow works as before | |
| 10.3 | Capacitor intact | App startup | `BridgeActivity` parent class works, Capacitor plugins available | |
| 10.4 | Navigation unaffected | Tap bottom nav tabs | Tab navigation works correctly | |
| 10.5 | File download | Long-press image, select download | Download via `DownloadManager` works | |
