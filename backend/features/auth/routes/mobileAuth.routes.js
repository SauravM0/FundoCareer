import express from 'express';
import { authenticate } from '../../../middlewares/auth.middleware.js';
import {
  startGoogleMobileAuth,
  googleMobileCallback,
  exchangeMobileCode,
  exchangeAndroidIdToken,
  refreshAccessToken,
  mobileLogout,
  checkGoogleConfig,
  ping,
} from '../controllers/mobileAuth.controller.js';

const router = express.Router();

// Connectivity test
router.get('/ping', ping);

// Config check (safe diagnostic - no secrets exposed)
router.get('/google/config-check', checkGoogleConfig);

// Start Google OAuth for mobile
router.get('/google/start', startGoogleMobileAuth);

// Google OAuth callback
router.get('/google/callback', googleMobileCallback);

// Exchange one-time code for tokens
router.post('/exchange', exchangeMobileCode);

// Native Android Google Sign-In (verify ID token)
router.post('/google/android-token', exchangeAndroidIdToken);

// Refresh access token
router.post('/refresh', refreshAccessToken);

// Logout (revoke refresh token)
router.post('/logout', mobileLogout);

export default router;
