import express from "express";
import dotenv from "dotenv";
import cors from "cors";
import cookieParser from "cookie-parser";
import passport from "./config/passport.config.js";

// Import routes
import authRoutes from "./features/auth/routes/auth.routes.js";
import mobileAuthRoutes from "./features/auth/routes/mobileAuth.routes.js";
import resumeRoutes from "./features/resume/routes/resume.routes.js";
import aiRoutes from "./features/ai/routes/ai.routes.js";
import downloadRoutes from "./routes/downloadRoute.js";

// Load environment variables
dotenv.config();

// ======================
// STARTUP ENVIRONMENT VALIDATION
// ======================
const REQUIRED_ENV_VARS = ['DATABASE_URL', 'JWT_SECRET', 'GOOGLE_WEB_CLIENT_ID', 'FRONTEND_URL'];
const missingVars = REQUIRED_ENV_VARS.filter(v => !process.env[v]);
if (missingVars.length > 0) {
  console.error('\n❌ FATAL: Missing required environment variables:');
  missingVars.forEach(v => console.error(`   - ${v}`));
  console.error('   Set them in a .env file or environment.\n');
  process.exit(1);
}

/**
 * Resume Builder Backend Server
 * Modular architecture with feature-based organization
 */

const app = express();
const PORT = process.env.PORT || 5000;

// ======================
// MIDDLEWARE
// ======================

const ALLOWED_ORIGINS = [
  'https://www.fundocareer.com',
  'https://fundocareer.com',
];
// In development, allow localhost and Android emulator
if (process.env.NODE_ENV !== 'production') {
  ALLOWED_ORIGINS.push(
    'http://localhost:3000',
    'http://localhost:5173',
    'http://localhost:5000',
    'http://10.0.2.2:5173',
    'http://10.0.2.2:5000',
  );
}

app.use(
  cors({
    origin: (origin, callback) => {
      // Allow requests with no origin (mobile apps, curl, etc.)
      if (!origin) return callback(null, true);
      if (ALLOWED_ORIGINS.includes(origin)) return callback(null, true);
      console.warn(`[CORS] Blocked origin: ${origin}`);
      return callback(null, false);
    },
    credentials: true,
    methods: ["GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"],
    allowedHeaders: ["Content-Type", "Authorization", "X-Requested-With", "x-session-id"],
  }),
);

// Body parsing
app.use(express.json({ limit: "50mb" }));
app.use(express.urlencoded({ extended: true, limit: "50mb" }));

// Cookie parsing (for JWT tokens)
app.use(cookieParser());

// Initialize Passport
app.use(passport.initialize());

// ======================
// API ERROR MIDDLEWARE — catches body-parsing & early errors
// Must be placed after body parsers but before routes to catch JSON parse errors
// ======================
app.use('/api', (err, req, res, next) => {
  if (err instanceof SyntaxError && err.status === 400 && 'body' in err) {
    console.error('[API] JSON parse error:', err.message);
    return res.status(400).json({ success: false, message: 'Invalid JSON in request body.' });
  }
  next(err);
});

// Wrap async route handlers to catch rejected promises
const asyncHandler = (fn) => (req, res, next) => {
  Promise.resolve(fn(req, res, next)).catch(next);
};

// ======================
// ROUTES
// ======================

// ======================
// API ENDPOINT: /api/health
// ======================
// This endpoint proves www.fundocareer.com/api/* reaches the backend
app.get("/api/health", (req, res) => {
  res.json({
    ok: true,
    status: "healthy",
    module: "fundocareer-backend",
    timestamp: new Date().toISOString()
  });
});

// Legacy health check (no /api prefix — direct backend access)
app.get("/health", (req, res) => {
  res.json({
    status: "healthy",
    timestamp: new Date().toISOString(),
    service: "Resume Builder API",
    version: "2.0.0",
  });
});

// API documentation
app.get("/", (req, res) => {
  res.json({
    message: "Resume Builder API",
    version: "2.0.0",
    documentation: {
      auth: "/api/auth",
      resume: "/api/resume",
    },
    endpoints: {
      auth: {
        "POST /api/auth/google": "Android native Google Sign-In — send {idToken}",
        "GET /api/auth/google": "Initiate Google OAuth login (web)",
        "GET /api/auth/google/callback": "Google OAuth callback",
        "POST /api/auth/logout": "Logout user",
        "GET /api/auth/me": "Get current user (protected)",
        "GET /api/auth/verify": "Verify token (protected)",
      },
      resume: {
        "POST /api/resume": "Create resume (protected)",
        "GET /api/resume": "Get all user resumes (protected)",
        "GET /api/resume/:id": "Get resume by ID (protected)",
        "PATCH /api/resume/:id": "Update resume (protected)",
        "DELETE /api/resume/:id": "Delete resume (protected)",
        "POST /api/resume/:id/duplicate": "Duplicate resume (protected)",
        "POST /api/resume/upload": "Upload & parse resume file (protected)",
        "POST /api/resume/extract-text": "Extract text from file (protected)",
        "GET /api/resume/source-resumes":
          "Get source resume library (protected)",
      },
    },
  });
});

// ======================
// FEATURE ROUTES — all mounted under /api/*
// ======================
console.log('[API] Mounting auth routes at /api/auth');
app.use("/api/auth", authRoutes);
console.log('[API] Mounting mobile auth routes at /api/mobile/auth');
app.use("/api/mobile/auth", mobileAuthRoutes);
console.log('[API] Mounting resume routes at /api/resume');
app.use("/api/resume", resumeRoutes);
app.use("/api/resumes", resumeRoutes); // Alias for plural usage in Frontend
console.log('[API] Mounting AI routes at /api/ai');
app.use("/api/ai", aiRoutes);
console.log('[API] Mounting download routes at /api/download');
app.use("/api/download", downloadRoutes);

// Add /api/user/profile alias that frontend WebView expects
import { authenticate } from './middlewares/auth.middleware.js';
import { getCurrentUser } from './features/auth/controllers/auth.controller.js';
app.get("/api/user/profile", authenticate, getCurrentUser);
app.get("/api/user/me", authenticate, getCurrentUser);

// ======================
// STUB ROUTES — SPA-requested endpoints that don't exist in local backend
// These return minimal 200 JSON to prevent 404 error states in the dashboard.
// Remove or implement properly when adding real functionality.
// ======================
const stubGet = (path) => app.get(path, (req, res) => res.json({ success: true, data: [] }));
stubGet('/api/commercial/config');
stubGet('/api/features');
stubGet('/api/interview/history');
stubGet('/api/schedule/entitlement');
stubGet('/api/user/preferences');
stubGet('/api/job-app/history');
stubGet('/api/ats/history');
stubGet('/api/user/subscriptions');

// ======================
// API JSON GUARD — must come before frontend SPA fallback
// ======================
// Any unmatched /api/* route returns JSON, NOT HTML
app.use('/api', (req, res) => {
  res.status(404).json({
    success: false,
    message: 'API endpoint not found',
    path: req.originalUrl
  });
});

// ======================
// ERROR HANDLING
// ======================

// 404 handler (for non-API routes — frontend SPA should handle these)
app.use((req, res) => {
  // If this is an API route, return JSON
  if (req.path.startsWith('/api/')) {
    return res.status(404).json({
      success: false,
      message: "API route not found",
      path: req.path,
    });
  }
  // For non-API routes, let frontend SPA handle routing
  res.status(404).json({
    success: false,
    message: "Route not found",
    path: req.path,
  });
});

// Global error handler — always returns JSON
app.use((err, req, res, next) => {
  console.error("[Server Error]", err);

  // Determine if this is an API route
  const isApi = req.path && req.path.startsWith('/api/');
  const statusCode = err.status || err.statusCode || 500;

  res.status(statusCode).json({
    success: false,
    message: err.message || "Internal server error",
    ...(isApi ? { path: req.path } : {}),
    ...(process.env.NODE_ENV === "development" && { stack: err.stack }),
  });
});

// ======================
// START SERVER
// ======================

app.listen(PORT, "0.0.0.0", () => {
  console.log("\n" + "=".repeat(60));
  console.log("🚀 Resume Builder Backend Server");
  console.log("=".repeat(60));
  console.log(`📡 Server running on: http://localhost:${PORT}`);
  console.log(`🌍 Environment: ${process.env.NODE_ENV || "development"}`);
  console.log(
    `🔐 Google OAuth: ${process.env.GOOGLE_CLIENT_ID ? "✓ Configured" : "✗ Not configured"}`,
  );
  console.log(
    `🤖 AI Services: ${process.env.XIAOMI_API_KEY || process.env.OPENROUTER_API_KEY || process.env.GOOGLE_API_KEY ? "✓ Available" : "✗ Not configured"}`,
  );
  console.log("=".repeat(60));
  console.log("\n📚 Features:");
  console.log("  ✓ Auth (Google OAuth + JWT)");
  console.log("  ✓ Resume (Create, Upload, Parse)");
  console.log("\n" + "=".repeat(60) + "\n");
});

export default app;
