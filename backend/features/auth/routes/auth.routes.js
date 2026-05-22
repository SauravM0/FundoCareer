import express from 'express';
import passport from '../../../config/passport.config.js';
import { authenticate } from '../../../middlewares/auth.middleware.js';
import { 
  googleCallback, 
  logout, 
  getCurrentUser, 
  verifyToken,
  exchangeAndroidIdToken
} from '../controllers/auth.controller.js';

const router = express.Router();

/**
 * Authentication Routes
 * Handles Google OAuth flow and user session management
 */

// Google OAuth - Initiate authentication (web flow)
router.get('/google', 
  passport.authenticate('google', { 
    scope: ['profile', 'email'],
    session: false 
  })
);

// Android native Google Sign-In — accepts ID token and returns JWT
// POST /api/auth/google  { idToken: "..." }
router.post('/google', exchangeAndroidIdToken);

// Google OAuth - Callback
router.get('/google/callback', googleCallback);

// Logout
router.post('/logout', logout);

// Get current user (protected)
router.get('/me', authenticate, getCurrentUser);

// Verify token (protected)
router.get('/verify', authenticate, verifyToken);

export default router;
