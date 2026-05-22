import jwt from "jsonwebtoken";

/**
 * Authentication Middleware
 * Verifies JWT token from cookie or Authorization header
 */
export const authenticate = (req, res, next) => {
  try {
    // Check Cookie OR Authorization Header
    const token = req.cookies?.token || 
                  (req.headers.authorization && req.headers.authorization.split(' ')[1]);

    if (!token) {
      return res.status(401).json({ 
        success: false, 
        message: "Authentication required. Please login." 
      });
    }

    // Verify JWT
    const decoded = jwt.verify(token, process.env.JWT_SECRET);
    
    // Attach user ID to request
    req.userId = decoded.userId;
    req.user = decoded;

    next();
  } catch (err) {
    console.error('[Auth Middleware] Token verification failed:', err.message);
    return res.status(401).json({ 
      success: false, 
      message: "Invalid or expired token. Please login again." 
    });
  }
};

/**
 * Optional Authentication Middleware
 * Attaches user if token is valid, but doesn't block if missing
 */
export const optionalAuth = (req, res, next) => {
  try {
    const token = req.cookies?.token || 
                  (req.headers.authorization && req.headers.authorization.split(' ')[1]);

    if (token) {
      const decoded = jwt.verify(token, process.env.JWT_SECRET);
      req.userId = decoded.userId;
      req.user = decoded;
    }

    next();
  } catch (err) {
    // Continue without authentication
    next();
  }
};
