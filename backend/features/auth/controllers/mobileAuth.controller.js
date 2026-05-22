import crypto from 'crypto';
import jwt from 'jsonwebtoken';
import { OAuth2Client } from 'google-auth-library';
import passport from '../../../config/passport.config.js';
import prisma from '../../../config/database.config.js';

const GOOGLE_WEB_CLIENT_ID = process.env.GOOGLE_WEB_CLIENT_ID || process.env.GOOGLE_CLIENT_ID;
const googleIdTokenClient = new OAuth2Client(GOOGLE_WEB_CLIENT_ID);

const MOBILE_CODE_EXPIRY_MS = 5 * 60 * 1000;
const ACCESS_TOKEN_EXPIRY_S = 900;
const REFRESH_TOKEN_EXPIRY_MS = 30 * 24 * 60 * 60 * 1000;

function hashToken(token) {
  return crypto.createHash('sha256').update(token).digest('hex');
}

function generateMobileAuthCode() {
  return crypto.randomBytes(32).toString('hex');
}

function generateRefreshToken() {
  return crypto.randomBytes(48).toString('hex');
}

function buildCallbackUrl(req) {
  if (req.query.redirect_uri) {
    return req.query.redirect_uri;
  }
  if (process.env.GOOGLE_MOBILE_CALLBACK_URL) {
    return process.env.GOOGLE_MOBILE_CALLBACK_URL;
  }
  const host = req.headers['x-forwarded-host'] || req.headers['host'] || 'localhost:5000';
  const protocol = req.headers['x-forwarded-proto'] || 'http';
  return `${protocol}://${host}/api/mobile/auth/google/callback`;
}

/**
 * GET /api/mobile/auth/google/start
 * Starts Google OAuth for Android.
 */
export const startGoogleMobileAuth = (req, res, next) => {
  const platform = req.query.platform || 'android';
  const state = crypto.randomBytes(16).toString('hex');
  req.session = req.session || {};
  req.session.mobileOAuthState = state;
  req.session.mobileOAuthPlatform = platform;

  const callbackUrl = buildCallbackUrl(req);
  console.log(`[Mobile Auth] Starting OAuth with callback URL: ${callbackUrl}`);

  passport.authenticate('google', {
    scope: ['profile', 'email'],
    session: false,
    state: state,
    callbackURL: callbackUrl,
  })(req, res, next);
};

/**
 * GET /api/mobile/auth/google/callback
 * Google redirects here after user auth.
 */
export const googleMobileCallback = (req, res) => {
  const callbackUrl = buildCallbackUrl(req);

  passport.authenticate('google', { session: false, callbackURL: callbackUrl }, async (err, user) => {
    const deepLinkBase = process.env.ANDROID_DEEP_LINK_CALLBACK || 'fundocareer://auth/callback';

    if (err) {
      console.error('[Mobile Google Callback] Error:', err.message);

      if (err.message && err.message.includes('redirect_uri_mismatch')) {
        console.error(`[Mobile Google Callback] Google OAuth redirect_uri_mismatch. Add this URI in Google Console: ${callbackUrl}`);
        return res.redirect(`${deepLinkBase}?status=error&error=google_console_redirect_missing`);
      }

      console.error(`[Mobile Google Callback] Expected redirect URI: ${callbackUrl}`);
      return res.redirect(`${deepLinkBase}?status=error&error=google_auth_failed`);
    }

    if (!user) {
      console.error('[Mobile Google Callback] No user returned from Google');
      console.error(`[Mobile Google Callback] Expected redirect URI: ${callbackUrl}`);
      return res.redirect(`${deepLinkBase}?status=error&error=google_auth_failed`);
    }

    if (user.status === 'suspended') {
      return res.redirect(`${deepLinkBase}?status=error&error=user_disabled`);
    }

    try {
      const rawCode = generateMobileAuthCode();
      const codeHash = hashToken(rawCode);
      const platform = req.session?.mobileOAuthPlatform || 'android';

      await prisma.mobileAuthCode.create({
        data: {
          codeHash,
          userId: user.id,
          platform,
          expiresAt: new Date(Date.now() + MOBILE_CODE_EXPIRY_MS),
        },
      });

      console.log(`[Mobile Google Callback] One-time code generated for user ${user.id}`);
      return res.redirect(`${deepLinkBase}?code=${rawCode}&status=success`);
    } catch (dbErr) {
      console.error('[Mobile Google Callback] DB error:', dbErr);
      return res.redirect(`${deepLinkBase}?status=error&error=server_error`);
    }
  })(req, res);
};

/**
 * POST /api/mobile/auth/google/android-token
 * Android app sends a Google ID token from native Google Sign-In.
 * Backend verifies the token and returns access/refresh tokens.
 */
export const exchangeAndroidIdToken = async (req, res) => {
  let email = 'unknown';
  try {
    const { idToken, platform, deviceId } = req.body;

    if (!idToken) {
      return res.status(400).json({ success: false, code: 'GOOGLE_TOKEN_REQUIRED', message: 'Google ID token is required.' });
    }

    let payload;
    try {
      const ticket = await googleIdTokenClient.verifyIdToken({
        idToken,
        audience: GOOGLE_WEB_CLIENT_ID,
      });
      payload = ticket.getPayload();
    } catch (verifyErr) {
      console.error('[Android Token] ID token verification failed.');
      return res.status(401).json({ success: false, code: 'GOOGLE_TOKEN_INVALID', message: 'Invalid Google ID token.' });
    }

    const googleId = payload.sub;
    const emailVerified = payload.email_verified;
    email = payload.email;
    const name = payload.name || 'User';
    const photo = payload.picture || null;

    if (!googleId || !email) {
      return res.status(400).json({ success: false, code: 'GOOGLE_TOKEN_INVALID', message: 'Invalid Google ID token payload.' });
    }

    if (emailVerified !== true) {
      return res.status(403).json({ success: false, code: 'GOOGLE_EMAIL_NOT_VERIFIED', message: 'Google account email is not verified.' });
    }

    console.log(`[Android Token] Authenticating user: ${email}`);

    let user = await prisma.user.findFirst({
      where: { authProvider: 'google', authProviderId: googleId },
    });

    if (user) {
      console.log(`[Android Token] Existing Google user found: ${user.id}`);
      user = await prisma.user.update({
        where: { id: user.id },
        data: { name, image: photo },
      });
    } else {
      user = await prisma.user.findUnique({ where: { email } });
      if (user) {
        console.log(`[Android Token] Linking Google account to existing user: ${user.id}`);
        user = await prisma.user.update({
          where: { id: user.id },
          data: { authProvider: 'google', authProviderId: googleId, name, image: photo, isVerified: true },
        });
      } else {
        console.log('[Android Token] Creating new user...');
        user = await prisma.user.create({
          data: {
            email,
            name,
            image: photo,
            authProvider: 'google',
            authProviderId: googleId,
            role: 'user',
            status: 'activated',
            isVerified: true,
          },
        });
        console.log(`[Android Token] New user created: ${user.id}`);
      }
    }

    if (user.status === 'suspended') {
      return res.status(403).json({ success: false, code: 'USER_DISABLED', message: 'Account is disabled. Please contact support.' });
    }

    const accessToken = jwt.sign(
      { userId: user.id, email: user.email, name: user.name, role: user.role },
      process.env.JWT_SECRET,
      { expiresIn: ACCESS_TOKEN_EXPIRY_S },
    );

    const rawRefreshToken = generateRefreshToken();
    const refreshTokenHash = hashToken(rawRefreshToken);

    // Revoke previous Android tokens for this user to prevent session contamination
    await prisma.$transaction([
      prisma.refreshToken.updateMany({
        where: {
          userId: user.id,
          platform: platform || 'android',
          revokedAt: null,
          ...(deviceId ? { deviceId } : {}),
        },
        data: { revokedAt: new Date() },
      }),
      prisma.refreshToken.create({
        data: {
          tokenHash: refreshTokenHash,
          userId: user.id,
          platform: platform || 'android',
          deviceId: deviceId || null,
          expiresAt: new Date(Date.now() + REFRESH_TOKEN_EXPIRY_MS),
        },
      }),
    ]);

    console.log(`[Android Token] Tokens issued for user ${user.id}`);
    console.log(`[Android Token] Login success: ${user.email}`);

    return res.json({
      success: true,
      accessToken,
      refreshToken: rawRefreshToken,
      expiresIn: ACCESS_TOKEN_EXPIRY_S,
      tokenType: 'Bearer',
      user: {
        id: user.id,
        email: user.email,
        name: user.name,
        image: user.image,
        role: user.role,
      },
    });
  } catch (error) {
    console.error(`[Android Token] Error for user: ${email}`);
    return res.status(500).json({ success: false, code: 'SERVER_ERROR', message: 'Internal server error.' });
  }
};

/**
 * POST /api/mobile/auth/exchange
 * Android app exchanges one-time code for tokens.
 */
export const exchangeMobileCode = async (req, res) => {
  try {
    const { code, platform, deviceId } = req.body;

    if (!code) {
      return res.status(400).json({ success: false, code: 'CODE_REQUIRED', message: 'Authorization code is required.' });
    }

    const codeHash = hashToken(code);
    const authCode = await prisma.mobileAuthCode.findUnique({ where: { codeHash } });

    if (!authCode) {
      return res.status(400).json({ success: false, code: 'AUTH_CODE_INVALID', message: 'Invalid authorization code.' });
    }

    if (authCode.consumedAt) {
      return res.status(400).json({ success: false, code: 'AUTH_CODE_ALREADY_USED', message: 'Authorization code has already been used.' });
    }

    if (new Date() > authCode.expiresAt) {
      return res.status(400).json({ success: false, code: 'AUTH_CODE_EXPIRED', message: 'Login session expired. Please try again.' });
    }

    const user = await prisma.user.findUnique({ where: { id: authCode.userId } });

    if (!user) {
      return res.status(404).json({ success: false, code: 'USER_NOT_FOUND', message: 'User not found.' });
    }

    if (user.status === 'suspended') {
      return res.status(403).json({ success: false, code: 'USER_DISABLED', message: 'Account is suspended.' });
    }

    // Mark code as consumed
    await prisma.mobileAuthCode.update({
      where: { id: authCode.id },
      data: { consumedAt: new Date(), deviceId: deviceId || authCode.deviceId },
    });

    // Generate tokens
    const accessToken = jwt.sign(
      { userId: user.id, email: user.email, name: user.name, role: user.role },
      process.env.JWT_SECRET,
      { expiresIn: ACCESS_TOKEN_EXPIRY_S },
    );

    const rawRefreshToken = generateRefreshToken();
    const refreshTokenHash = hashToken(rawRefreshToken);

    // Revoke previous tokens for this user to prevent session contamination
    await prisma.$transaction([
      prisma.refreshToken.updateMany({
        where: {
          userId: user.id,
          platform: platform || authCode.platform || 'android',
          revokedAt: null,
          ...(deviceId ? { deviceId } : {}),
        },
        data: { revokedAt: new Date() },
      }),
      prisma.refreshToken.create({
        data: {
          tokenHash: refreshTokenHash,
          userId: user.id,
          platform: platform || authCode.platform,
          deviceId: deviceId || authCode.deviceId,
          expiresAt: new Date(Date.now() + REFRESH_TOKEN_EXPIRY_MS),
        },
      }),
    ]);

    console.log(`[Mobile Auth] Tokens issued for user ${user.id}`);

    return res.json({
      success: true,
      accessToken,
      refreshToken: rawRefreshToken,
      expiresIn: ACCESS_TOKEN_EXPIRY_S,
      tokenType: 'Bearer',
      user: {
        id: user.id,
        email: user.email,
        name: user.name,
        image: user.image,
        role: user.role,
      },
    });
  } catch (error) {
    console.error('[Mobile Auth Exchange] Error:', error);
    return res.status(500).json({ success: false, code: 'SERVER_ERROR', message: 'Internal server error.' });
  }
};

/**
 * POST /api/mobile/auth/refresh
 * Refresh access token using refresh token.
 */
export const refreshAccessToken = async (req, res) => {
  try {
    const { refreshToken } = req.body;

    if (!refreshToken) {
      return res.status(400).json({ success: false, code: 'REFRESH_TOKEN_REQUIRED', message: 'Refresh token is required.' });
    }

    const tokenHash = hashToken(refreshToken);
    const storedToken = await prisma.refreshToken.findUnique({ where: { tokenHash } });

    if (!storedToken) {
      return res.status(401).json({ success: false, code: 'REFRESH_TOKEN_INVALID', message: 'Invalid refresh token.' });
    }

    if (storedToken.revokedAt) {
      return res.status(401).json({ success: false, code: 'REFRESH_TOKEN_REVOKED', message: 'Refresh token has been revoked. Please login again.' });
    }

    if (new Date() > storedToken.expiresAt) {
      return res.status(401).json({ success: false, code: 'REFRESH_TOKEN_EXPIRED', message: 'Refresh token has expired. Please login again.' });
    }

    const user = await prisma.user.findUnique({ where: { id: storedToken.userId } });

    if (!user) {
      return res.status(404).json({ success: false, code: 'USER_NOT_FOUND', message: 'User not found.' });
    }

    if (user.status === 'suspended') {
      return res.status(403).json({ success: false, code: 'USER_DISABLED', message: 'Account is disabled. Please contact support.' });
    }

    const accessToken = jwt.sign(
      { userId: user.id, email: user.email, name: user.name, role: user.role },
      process.env.JWT_SECRET,
      { expiresIn: ACCESS_TOKEN_EXPIRY_S },
    );

    console.log(`[Mobile Auth Refresh] Access token refreshed for user ${user.id}`);

    return res.json({
      success: true,
      accessToken,
      expiresIn: ACCESS_TOKEN_EXPIRY_S,
      tokenType: 'Bearer',
    });
  } catch (error) {
    console.error('[Mobile Auth Refresh] Error.');
    return res.status(500).json({ success: false, code: 'SERVER_ERROR', message: 'Internal server error.' });
  }
};

/**
 * POST /api/mobile/auth/logout
 * Revoke refresh token.
 */
export const mobileLogout = async (req, res) => {
  try {
    const { refreshToken } = req.body;

    if (refreshToken) {
      const tokenHash = hashToken(refreshToken);
      const stored = await prisma.refreshToken.findUnique({ where: { tokenHash } });

      if (stored && !stored.revokedAt) {
        await prisma.refreshToken.update({
          where: { id: stored.id },
          data: { revokedAt: new Date() },
        });
        console.log(`[Mobile Logout] Refresh token revoked for user ${stored.userId}`);
      }
    }

    return res.json({ success: true, code: 'LOGOUT_SUCCESS', message: 'Logged out successfully.' });
  } catch (error) {
    console.error('[Mobile Logout] Error.');
    return res.status(500).json({ success: false, code: 'SERVER_ERROR', message: 'Internal server error.' });
  }
};

/**
 * GET /api/mobile/auth/ping
 * Simple connectivity test for Android app.
 */
export const ping = (req, res) => {
  res.json({ success: true, message: 'pong', timestamp: new Date().toISOString() });
};

/**
 * GET /api/mobile/auth/google/config-check
 * Safe diagnostic endpoint.
 */
export const checkGoogleConfig = (req, res) => {
  const envCallback = process.env.GOOGLE_MOBILE_CALLBACK_URL;
  const dynamicCallback = buildCallbackUrl(req);
  const androidDeepLink = process.env.ANDROID_DEEP_LINK_CALLBACK || 'fundocareer://auth/callback';

  return res.json({
    success: true,
    googleClientIdConfigured: !!process.env.GOOGLE_WEB_CLIENT_ID,
    googleClientSecretConfigured: !!process.env.GOOGLE_CLIENT_SECRET,
    mobileCallbackConfigured: !!envCallback,
    backendPublicUrlConfigured: !!process.env.BACKEND_PUBLIC_URL,
    configuredCallbackUrl: envCallback || '(not set - will use dynamic)',
    dynamicCallbackUrl: dynamicCallback,
    androidDeepLink: androidDeepLink,
    androidTokenEndpoint: {
      path: 'POST /api/mobile/auth/google/android-token',
      description: 'Android app sends native Google ID token, backend verifies and returns JWT tokens',
      expectedBody: { idToken: 'string (required)', platform: 'string (optional)', deviceId: 'string (optional)' },
      clientIdUsed: process.env.GOOGLE_WEB_CLIENT_ID ? `${process.env.GOOGLE_WEB_CLIENT_ID.substring(0, 20)}...` : '(not configured)',
      clientIdSource: 'process.env.GOOGLE_WEB_CLIENT_ID (Web client ID — used for Android ID token verification)',
    },
    googleConsoleRequiredUris: [
      'http://localhost:5000/api/auth/google/callback',
      'http://localhost:5000/api/mobile/auth/google/callback',
      'http://10.0.2.2:5000/api/mobile/auth/google/callback',
    ],
    noteForEmulator: 'Use "adb reverse tcp:5000 tcp:5000" so localhost:5000 on emulator reaches host. Or add http://10.0.2.2:5000/api/mobile/auth/google/callback to Google Console.',
    envRequired: [
      'GOOGLE_WEB_CLIENT_ID (for Android ID token verification)',
      'GOOGLE_ANDROID_CLIENT_ID (optional, for documentation)',
      'GOOGLE_CLIENT_SECRET (for web OAuth flow)',
      'JWT_SECRET',
      'FRONTEND_URL',
    ],
  });
};
