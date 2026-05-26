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

// ======================
// EMAIL CONFIGURATION VALIDATION
// ======================
const emailUser = process.env.SERVER_EMAIL_USERNAME;
const emailPass = process.env.SERVER_EMAIL_PASSWORD;

console.log('\n[Startup] Email configuration check:');
if (!emailUser) {
  console.warn('  ⚠ SERVER_EMAIL_USERNAME not set — email sending DISABLED');
  console.warn('  Set both SERVER_EMAIL_USERNAME and SERVER_EMAIL_PASSWORD in .env to enable email.');
} else if (!emailPass) {
  console.warn('  ⚠ SERVER_EMAIL_USERNAME is set but SERVER_EMAIL_PASSWORD is missing — email sending DISABLED');
} else if (emailUser.includes('@gmail.com') || emailUser.includes('@googlemail.com')) {
  const looksLikeAppPassword = /^[a-z]{16}$/.test(emailPass);
  if (!looksLikeAppPassword) {
    console.warn('  ⚠ Gmail detected but password does not look like a Gmail App Password.');
    console.warn('  Gmail App Passwords are exactly 16 lowercase letters with no spaces.');
    console.warn('  Your regular Gmail password will NOT work with SMTP.');
    console.warn('  Generate one at: https://myaccount.google.com/apppasswords');
    console.warn('  1. Enable 2FA at https://myaccount.google.com/security');
    console.warn('  2. Go to https://myaccount.google.com/apppasswords');
    console.warn('  3. Select "Mail" and "Other (Custom name)" → generate');
  } else {
    console.log('  ✓ Gmail App Password detected (format valid)');
  }
  console.log(`  User: ${emailUser.replace(/(.{2}).*@/, '$1***@')}`);
} else {
  console.log('  ✓ SERVER_EMAIL_USERNAME is set (non-Gmail)');
}
console.log('');

const app = express();
const ALLOWED_ORIGINS = [
  'https://www.fundocareer.com',
  'https://fundocareer.com',
];
if (process.env.NODE_ENV !== 'production') {
  // Development: allow local dev servers if specified
  if (process.env.DEV_ORIGINS) {
    const devOrigins = process.env.DEV_ORIGINS.split(',').map(o => o.trim());
    ALLOWED_ORIGINS.push(...devOrigins);
  }
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

function maskLogEmail(email) {
  if (!email || typeof email !== 'string' || !email.includes('@')) return null;
  const [local, domain] = email.split('@');
  return `${local.slice(0, 2)}***@${domain}`;
}

function schedulerDiagnosticsRequestLogger(req, res, next) {
  const trackedPrefixes = ['/api/mobile/auth', '/api/notifications', '/api/scheduler'];
  if (!trackedPrefixes.some(prefix => req.originalUrl.startsWith(prefix))) return next();

  const startedAt = Date.now();
  const requestId = Math.random().toString(16).slice(2, 10);
  const endpoint = req.originalUrl.split('?')[0];
  const userEmail = req.user?.email || req.body?.to || req.body?.email || null;
  console.log(JSON.stringify({
    event: 'api_request_started',
    requestId,
    endpoint,
    method: req.method,
    userEmail: maskLogEmail(userEmail),
    hasAuthHeader: Boolean(req.headers.authorization),
    timestamp: new Date().toISOString(),
  }));

  req.requestId = requestId;

  res.on('finish', () => {
    console.log(JSON.stringify({
      event: 'api_request_finished',
      requestId,
      endpoint,
      method: req.method,
      httpStatus: res.statusCode,
      durationMs: Date.now() - startedAt,
      timestamp: new Date().toISOString(),
    }));
  });

  next();
}

// Health checks
app.get("/api/health", (req, res) => {
  res.json({
    success: true,
    ok: true,
    status: "healthy",
    errorCode: null,
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
app.use(schedulerDiagnosticsRequestLogger);
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
const stubGet = (path) => app.get(path, (req, res) => {
  res.json({ success: true, requestId: req.requestId || undefined, data: [] });
});
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
  const requestId = req.requestId || null;
  const isApi = req.path && req.path.startsWith('/api/');

  // Prisma known request errors
  if (err.constructor && err.constructor.name === 'PrismaClientKnownRequestError') {
    console.error(JSON.stringify({
      event: 'prisma_error', requestId, code: err.code, meta: err.meta,
      message: err.message?.substring(0, 200),
    }));
    const statusCode = err.code === 'P2002' ? 409 : err.code === 'P2025' ? 404 : 400;
    return res.status(statusCode).json({
      success: false,
      message: statusCode === 409 ? 'Resource already exists' : 'Database operation failed',
      errorCode: statusCode === 409 ? 'DUPLICATE_RESOURCE' : 'DB_ERROR',
      ...(isApi ? { path: req.path } : {}),
      ...(requestId ? { requestId } : {}),
    });
  }

  // Prisma validation errors
  if (err.constructor && err.constructor.name === 'PrismaClientValidationError') {
    console.error(JSON.stringify({
      event: 'prisma_validation_error', requestId,
      message: err.message?.substring(0, 200),
    }));
    return res.status(400).json({
      success: false, message: 'Invalid data provided',
      errorCode: 'VALIDATION_ERROR',
      ...(isApi ? { path: req.path } : {}),
      ...(requestId ? { requestId } : {}),
    });
  }

  console.error(JSON.stringify({
    event: 'server_error', requestId,
    message: err.message,
    statusCode: err.status || err.statusCode || 500,
    ...(process.env.NODE_ENV === "development" ? { stack: err.stack } : {}),
  }));

  const statusCode = err.status || err.statusCode || 500;
  res.status(statusCode).json({
    success: false,
    message: statusCode >= 500 ? "Internal server error" : err.message,
    errorCode: statusCode >= 500 ? 'SERVER_ERROR' : undefined,
    ...(isApi ? { path: req.path } : {}),
    ...(requestId ? { requestId } : {}),
  });
});

export default app;
