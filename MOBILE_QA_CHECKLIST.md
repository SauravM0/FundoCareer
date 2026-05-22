# Mobile Responsiveness QA Checklist — Android WebView

## Test Devices / Viewports
- [ ] 360px (small phone, e.g. Galaxy S10e / Pixel 5)
- [ ] 390px (e.g. iPhone 14 / Pixel 7)
- [ ] 412px (e.g. Galaxy S24/Note)
- [ ] 600px (small tablet, landscape phone)
- [ ] 768px+ (tablet — layout should remain mostly desktop)

---

## 1. App-Mode Detection (All Routes)

| # | Test | Expected |
|---|------|----------|
| 1.1 | Load any page in WebView | `data-fundocareer-app="android"` set on `<html>` |
| 1.2 | Check `localStorage` | `FUNDOCareer_APP_MODE = "android"` set |
| 1.3 | `window.electron` | `true` (reuses web app's Electron mode) |
| 1.4 | `localStorage` flag persists across navigation | Still set after tab switch |

---

## 2. General Layout (All Routes)

| # | Test | Expected |
|---|------|----------|
| 2.1 | Website header/navbar | **Hidden** (not visible anywhere) |
| 2.2 | Website top padding (pt-20, pt-24) | **Hidden** (no extra gap at top) |
| 2.3 | Website footer | **Hidden** (not visible) |
| 2.4 | Bottom padding | 80px added to `body`, `main`, `#app` for native nav clearance |
| 2.5 | No horizontal scroll | Content fits within viewport width |
| 2.6 | Overscroll behavior | `overscroll-behavior-y: contain` — no rubber-banding |
| 2.7 | Touch targets | All buttons, links, inputs ≥ 48×48px |
| 2.8 | Font size | No text smaller than 13px; body 16px; inputs 16px (no iOS zoom) |

---

## 3. Route: Home (`/`)

| # | Test | Expected |
|---|------|----------|
| 3.1 | Hero section | Compact padding, fits mobile width |
| 3.2 | Marketing section | Stacked vertically, 1.5rem padding blocks |
| 3.3 | Buttons (CTA) | Full-width, 14px/16px padding, 8px radius |
| 3.4 | Images | Max-width 100%, no overflow |
| 3.5 | Cards on home | 12px border-radius, 8px shadow, margin-bottom 12px |

---

## 4. Route: Resume Builder (`/resumes`)

| # | Test | Expected |
|---|------|----------|
| 4.1 | Resume list | Full-width cards, 1rem padding |
| 4.2 | Resume cards | 12px radius, shadow, 12px bottom margin |
| 4.3 | Create new resume button | Full-width, touch-friendly |
| 4.4 | Resume editor | No padding, fills screen width |
| 4.5 | Templates grid | **Single column** (grid-template-columns: 1fr) |
| 4.6 | Upload button | Full-width, file picker opens SAF |

---

## 5. Route: Jobs (`/jobpage`)

| # | Test | Expected |
|---|------|----------|
| 5.1 | Job list | 1rem padding on container |
| 5.2 | Job cards | 1rem padding, 12px radius, 12px bottom margin |
| 5.3 | Job title | 1.125rem font-size |
| 5.4 | Company name | 0.875rem font-size |
| 5.5 | Apply button | Full-width at bottom of card |
| 5.6 | Search/filter controls | 48px+ tall, full-width |

---

## 6. Route: Job Application (`/job-application` or `/apply/*`)

| # | Test | Expected |
|---|------|----------|
| 6.1 | Form container | 1rem padding |
| 6.2 | Form fields | Full-width, 48px+ height, 12px padding |
| 6.3 | Labels | 0.875rem, bold, block display |
| 6.4 | Submit button | Full-width, 14px padding, 8px radius |
| 6.5 | File upload area | 2rem padding, 100px+ min-height, 12px radius |
| 6.6 | Mobile keyboard | Focus auto-scrolls input into view |

---

## 7. Route: ATS Checker (`/ats-checker`)

| # | Test | Expected |
|---|------|----------|
| 7.1 | Container padding | 1rem |
| 7.2 | Upload dropzone | 2rem padding, 120px min-height, 12px radius |
| 7.3 | Upload icon | 48×48px |
| 7.4 | Score display | 80×80px max (circle/ring) |
| 7.5 | Results section | Full-width cards |
| 7.6 | Download report button | Full-width |

---

## 8. Route: Mock Interview (`/mock-interview`)

| # | Test | Expected |
|---|------|----------|
| 8.1 | Container padding | 1rem |
| 8.2 | AI sphere (avatar) | 35vh min-height |
| 8.3 | User sphere (avatar) | 25vh min-height, 140px minimum |
| 8.4 | Mic button | 64×64px circle, centered |
| 8.5 | Mic recording indicator | "● Recording" banner appears at top |
| 8.6 | Permission flow | See Permissions checklist — only triggered here |
| 8.7 | Interview chat bubbles | Full-width, no overflow |

---

## 9. Route: Profile (`/profile`)

| # | Test | Expected |
|---|------|----------|
| 9.1 | Container padding | 1rem |
| 9.2 | Profile avatar | 64×64px |
| 9.3 | Profile card | 1.5rem padding, 12px radius, 1rem bottom margin |
| 9.4 | Settings list | Full-width items, 48px+ tall |
| 9.5 | Logout button | Full-width |

---

## 10. Route: Pricing (`/pricing`)

| # | Test | Expected |
|---|------|----------|
| 10.1 | Container padding | 1rem |
| 10.2 | Pricing grid | **Single column** |
| 10.3 | Plan cards | 12px radius, 1rem bottom margin |
| 10.4 | Price text | 1.75rem font-size |
| 10.5 | Select/subscribe button | Full-width, at bottom of card |
| 10.6 | External payment | Tapping payment/checkout opens **Custom Tab** (not in WebView) |
| 10.7 | ⚠️ Most popular / featured plan badge | Visible, no overflow |

---

## 11. Modals, Dialogs, Tables

| # | Test | Expected |
|---|------|----------|
| 11.1 | Modal max-width | 95vw |
| 11.2 | Modal height | 90vh max |
| 11.3 | Modal border-radius | 16px |
| 11.4 | Table horizontal scroll | Tables scroll left/right on mobile (no overflow breakage) |
| 11.5 | Drawer | Full-width (100vw) |

---

## 12. Forms & Controls

| # | Test | Expected |
|---|------|----------|
| 12.1 | Input fields | 48px+ height, 12+14px padding, 8px radius |
| 12.2 | Select dropdowns | Same sizing as inputs |
| 12.3 | Text areas | Same sizing, multi-line |
| 12.4 | Labels | Block display, 0.875rem, 4px bottom margin |
| 12.5 | Form group margin | 1rem bottom margin |
| 12.6 | Checkboxes/radio | 48×48px touch target minimum |

---

## 13. Browser Mode (Desktop Website)

| # | Test | Expected |
|---|------|----------|
| 13.1 | Visit site in regular Chrome on desktop | **No CSS changes** — `data-fundocareer-app` attribute not set |
| 13.2 | All CSS uses `[data-fundocareer-app=android]` prefix | Yes — desktop unaffected |
| 13.3 | `window.electron` check | Only set in WebView, not in browser |

---

## Summary of Changes

| File | Change |
|------|--------|
| `MainActivity.java` — `injectMobileAppStyles()` | Completely rewritten with comprehensive route-specific CSS |
| `injectSafeAreaCss()` | Existing (unchanged, runs first) |
| `injectMicTracking()` | Existing (unchanged) |
| `injectNavigationBridge()` | Existing (unchanged) |

### CSS Injection Architecture

```
onPageFinished()
  ├── injectSafeAreaCss()         ← adds safe-area padding via env(safe-area-inset-*)
  ├── injectMobileAppStyles()     ← comprehensive mobile CSS (THIS FILE)
  │     ├── Set data attribute on <html>
  │     ├── Set localStorage flag
  │     ├── Set window.electron = true
  │     └── Inject <style id="__fc_fixes">
  │           ├── App-mode foundation (64 rules)
  │           │     ├── Hide website chrome (header/nav/footer + padding)
  │           │     ├── 48px touch targets
  │           │     ├── Responsive typography
  │           │     ├── Modern card styling
  │           │     ├── Single-column grids
  │           │     ├── Full-width buttons
  │           │     ├── Full-width form controls
  │           │     ├── Modal/dialog sizing
  │           │     ├── Scrollable tables
  │           │     ├── Responsive images
  │           │     └── Bottom padding for native nav
  │           └── Route-specific overrides
  │                 ├── ATS Checker (dropzone, score, icon)
  │                 ├── Mock Interview (spheres, mic button)
  │                 ├── Pricing (single column, card styling)
  │                 ├── Resume Builder (editor, list, templates)
  │                 ├── Profile (avatar, card)
  │                 ├── Job Application (form, file upload)
  │                 └── Jobs (cards, list)
  ├── injectMicTracking()         ← Intercept getUserMedia for mic status
  └── injectNavigationBridge()    ← Tab navigation via SPA router
```
