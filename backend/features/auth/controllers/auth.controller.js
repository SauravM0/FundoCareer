import crypto from 'crypto';
import jwt from 'jsonwebtoken';
import { OAuth2Client } from 'google-auth-library';
import passport from '../../../config/passport.config.js';

const GOOGLE_WEB_CLIENT_ID = process.env.GOOGLE_WEB_CLIENT_ID || process.env.GOOGLE_CLIENT_ID;
const googleIdTokenClient = new OAuth2Client(GOOGLE_WEB_CLIENT_ID);

const ACCESS_TOKEN_EXPIRY_S = 900;
const REFRESH_TOKEN_EXPIRY_MS = 30 * 24 * 60 * 60 * 1000;

function hashToken(token) {
  return crypto.createHash('sha256').update(token).digest('hex');
}

function generateRefreshToken() {
  return crypto.randomBytes(48).toString('hex');
}

/**
 * Auth Controller
 * Handles authentication callbacks and token generation
 */

/**
 * Handle Google OAuth callback
 * Generates JWT token for authenticated user
 */
export const googleCallback = (req, res, next) => {
  passport.authenticate('google', { session: false }, (err, user, info) => {
    if (err) {
      console.error('[Google Callback] Authentication error:', err);
      return res.redirect(`${process.env.FRONTEND_URL}/login?error=auth_failed`);
    }

    if (!user) {
      console.error('[Google Callback] No user returned');
      return res.redirect(`${process.env.FRONTEND_URL}/login?error=no_user`);
    }

    // Generate JWT token
    const token = jwt.sign(
      { 
        userId: user.id,
        email: user.email,
        name: user.name,
        role: user.role
      },
      process.env.JWT_SECRET,
      { expiresIn: '7d' }
    );

    console.log(`[Google Callback] Token generated for user: ${user.id}`);

    // Set cookie
    res.cookie('token', token, {
      httpOnly: true,
      secure: process.env.NODE_ENV === 'production',
      maxAge: 7 * 24 * 60 * 60 * 1000, // 7 days
      sameSite: 'lax'
    });

    // Redirect to frontend with token (also in URL for flexibility)
    const redirectUrl = process.env.FRONTEND_URL || 'http://localhost:3000';
    res.redirect(`${redirectUrl}/auth/success?token=${token}`);

  })(req, res, next);
};

/**
 * Logout user
 * Clears authentication token
 */
export const logout = (req, res) => {
  res.clearCookie('token');
  res.json({ 
    success: true, 
    message: 'Logged out successfully' 
  });
};

/**
 * Get current authenticated user
 */
export const getCurrentUser = async (req, res) => {
  try {
    if (!req.userId) {
      return res.status(401).json({ 
        success: false, 
        message: 'Not authenticated' 
      });
    }

    // Fetch user from database
    const prisma = (await import('../../../config/database.config.js')).default;
    const user = await prisma.user.findUnique({
      where: { id: req.userId },
      select: {
        id: true,
        email: true,
        name: true,
        image: true,
        role: true,
        status: true,
        createdAt: true
      }
    });

    if (!user) {
      return res.status(404).json({ 
        success: false, 
        message: 'User not found' 
      });
    }

    res.json({ 
      success: true, 
      user 
    });

  } catch (error) {
    console.error('[Get Current User] Error:', error);
    res.status(500).json({ 
      success: false, 
      message: 'Failed to fetch user details' 
    });
  }
};

/**
 * Verify token endpoint
 * Checks if the provided token is valid
 */
export const verifyToken = (req, res) => {
  // If middleware passed, token is valid
  res.json({ 
    success: true, 
    userId: req.userId,
    message: 'Token is valid' 
  });
};

/**
 * POST /api/auth/google
 * Android native Google Sign-In — accepts ID token, returns JWT tokens.
 * Response uses flat key structure for compatibility with Android codebase.
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

    const prisma = (await import('../../../config/database.config.js')).default;

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

    // Revoke previous Android tokens
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

    // Flat response shape for Android (matching working codebase)
    return res.json({
      success: true,
      accessToken,
      refreshToken: rawRefreshToken,
      idToken: idToken,
      email: user.email,
      name: user.name,
      userId: user.id,
      image: user.image,
      role: user.role,
      expiresIn: ACCESS_TOKEN_EXPIRY_S,
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
