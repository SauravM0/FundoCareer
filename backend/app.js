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
import schedulerRoutes from "./features/scheduler/routes/scheduler.routes.js";
import notificationRoutes from "./features/notifications/routes/notifications.routes.js";

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

const app = express();
const ALLOWED_ORIGINS = [
  'https://www.fundocareer.com',
  'https://fundocareer.com',
];
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

app.use(express.json({ limit: "50mb" }));
app.use(express.urlencoded({ extended: true, limit: "50mb" }));
app.use(cookieParser());
app.use(passport.initialize());

// API error middleware
app.use('/api', (err, req, res, next) => {
  if (err instanceof SyntaxError && err.status === 400 && 'body' in err) {
    console.error('[API] JSON parse error:', err.message);
    return res.status(400).json({ success: false, message: 'Invalid JSON in request body.' });
  }
  next(err);
});

const asyncHandler = (fn) => (req, res, next) => {
  Promise.resolve(fn(req, res, next)).catch(next);
};

// Health checks
app.get("/api/health", (req, res) => {
  res.json({
    ok: true,
    status: "healthy",
    module: "fundocareer-backend",
    timestamp: new Date().toISOString()
  });
});

app.get("/health", (req, res) => {
  res.json({
    status: "healthy",
    timestamp: new Date().toISOString(),
    service: "Resume Builder API",
    version: "2.0.0",
  });
});

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
        "GET /api/resume/source-resumes": "Get source resume library (protected)",
      },
    },
  });
});

// Feature routes
console.log('[API] Mounting auth routes at /api/auth');
app.use("/api/auth", authRoutes);
console.log('[API] Mounting mobile auth routes at /api/mobile/auth');
app.use("/api/mobile/auth", mobileAuthRoutes);
console.log('[API] Mounting resume routes at /api/resume');
app.use("/api/resume", resumeRoutes);
app.use("/api/resumes", resumeRoutes);
console.log('[API] Mounting AI routes at /api/ai');
app.use("/api/ai", aiRoutes);
console.log('[API] Mounting download routes at /api/download');
app.use("/api/download", downloadRoutes);
console.log('[API] Mounting scheduler routes at /api/scheduler');
app.use("/api/scheduler", schedulerRoutes);
console.log('[API] Mounting notification routes at /api/notifications');
app.use("/api/notifications", notificationRoutes);

// Add /api/user/profile alias
import { authenticate } from './middlewares/auth.middleware.js';
import { getCurrentUser } from './features/auth/controllers/auth.controller.js';
app.get("/api/user/profile", authenticate, getCurrentUser);
app.get("/api/user/me", authenticate, getCurrentUser);

// Stub routes
const stubGet = (path) => app.get(path, (req, res) => res.json({ success: true, data: [] }));
stubGet('/api/commercial/config');
stubGet('/api/features');
stubGet('/api/interview/history');
stubGet('/api/schedule/entitlement');
stubGet('/api/user/preferences');
stubGet('/api/job-app/history');
stubGet('/api/ats/history');
stubGet('/api/user/subscriptions');

// API JSON guard
app.use('/api', (req, res) => {
  res.status(404).json({
    success: false,
    message: 'API endpoint not found',
    path: req.originalUrl
  });
});

// 404 handler
app.use((req, res) => {
  if (req.path.startsWith('/api/')) {
    return res.status(404).json({
      success: false,
      message: "API route not found",
      path: req.path,
    });
  }
  res.status(404).json({
    success: false,
    message: "Route not found",
    path: req.path,
  });
});

// Global error handler
app.use((err, req, res, next) => {
  console.error("[Server Error]", err);
  const isApi = req.path && req.path.startsWith('/api/');
  const statusCode = err.status || err.statusCode || 500;
  res.status(statusCode).json({
    success: false,
    message: err.message || "Internal server error",
    ...(isApi ? { path: req.path } : {}),
    ...(process.env.NODE_ENV === "development" && { stack: err.stack }),
  });
});

export default app;
