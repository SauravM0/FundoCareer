import passport from 'passport';
import { Strategy as GoogleStrategy } from 'passport-google-oauth20';
import prisma from './database.config.js';

/**
 * Google OAuth Strategy Configuration
 * Handles user authentication via Google
 */

const handleGoogleAuth = async (accessToken, refreshToken, profile, done) => {
  try {
    const email = profile.emails && profile.emails[0] ? profile.emails[0].value : null;
    const photo = profile.photos && profile.photos[0] ? profile.photos[0].value : null;
    const name = profile.displayName || "User";
    const googleId = profile.id;

    console.log(`[Google Auth] Authenticating user: ${email}`);

    if (!email) {
      return done(new Error("No email found from Google"), null);
    }

    // 1. Check if user exists by Google ID
    let user = await prisma.user.findFirst({
      where: {
        authProviderId: googleId,
        authProvider: 'google'
      }
    });

    if (user) {
      console.log(`[Google Auth] Existing user found: ${user.id}`);
      return done(null, user);
    }

    // 2. Check if user exists by email (account linking)
    user = await prisma.user.findUnique({
      where: { email: email }
    });

    if (user) {
      console.log(`[Google Auth] Linking Google account to existing user: ${user.id}`);
      // Link Google account
      user = await prisma.user.update({
        where: { id: user.id },
        data: {
          authProvider: 'google',
          authProviderId: googleId,
          name: name,
          image: photo
        }
      });
      return done(null, user);
    }

    // 3. Create new user
    console.log('[Google Auth] Creating new user...');
    user = await prisma.user.create({
      data: {
        email: email,
        name: name,
        image: photo,
        authProvider: 'google',
        authProviderId: googleId,
        role: 'user',
        status: 'activated',
        isVerified: true
      }
    });
    
    console.log(`[Google Auth] New user created: ${user.id}`);
    return done(null, user);

  } catch (error) {
    console.error('[Google Auth] Error:', error);
    return done(error, null);
  }
};

// Configure Google Strategy
// Note: GOOGLE_CLIENT_ID here is the web client ID used for Passport.js OAuth flow.
// For Android native ID token verification (POST /api/mobile/auth/google/android-token),
// the backend uses GOOGLE_WEB_CLIENT_ID instead.
if (process.env.GOOGLE_CLIENT_ID) {
  passport.use(new GoogleStrategy({
    clientID: process.env.GOOGLE_CLIENT_ID,
    clientSecret: process.env.GOOGLE_CLIENT_SECRET,
    callbackURL: process.env.GOOGLE_CALLBACK_URL || '/api/auth/google/callback',
    proxy: true
  }, handleGoogleAuth));

  console.log('✓ Google OAuth Strategy configured');
  console.log('  Callback URL:', process.env.GOOGLE_CALLBACK_URL || '/api/auth/google/callback');
} else {
  console.warn('⚠ Google OAuth not configured (missing GOOGLE_CLIENT_ID)');
}

export default passport;
