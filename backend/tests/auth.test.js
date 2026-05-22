/**
 * =============================================================================
 * AUTH BACKEND TEST SUITE — Regression Protection
 * =============================================================================
 *
 * Covers:
 *   1. POST /api/mobile/auth/google/android-token
 *   2. POST /api/mobile/auth/refresh
 *   3. POST /api/mobile/auth/logout
 *   4. GET  /api/auth/me (via authenticate middleware)
 *
 * Run:  cd backend && npm test
 *
 * IMPORTANT: These tests mock Prisma and Google OAuth client.
 * They do NOT require a real database or network.
 *
 * Uses jest.unstable_mockModule() for ES module compatibility.
 * Jest must be run with --experimental-vm-modules flag.
 */

import { jest } from '@jest/globals';

// =============================================================================
// MOCKS (must be before any dynamic imports of the modules under test)
// =============================================================================

const mockPrisma = {
  user: {
    findUnique: jest.fn(),
    findFirst: jest.fn(),
    create: jest.fn(),
    update: jest.fn(),
  },
  refreshToken: {
    findUnique: jest.fn(),
    create: jest.fn(),
    update: jest.fn(),
    updateMany: jest.fn(),
  },
  mobileAuthCode: {
    findUnique: jest.fn(),
    create: jest.fn(),
    update: jest.fn(),
  },
  $transaction: jest.fn((queries) => Promise.resolve(queries.map(() => ({})))),
};

// Mock Prisma — path relative to this test file (backend/tests/)
jest.unstable_mockModule('../config/database.config.js', () => ({
  __esModule: true,
  default: mockPrisma,
}));

// Mock google-auth-library
const mockVerifyIdToken = jest.fn();
jest.unstable_mockModule('google-auth-library', () => ({
  OAuth2Client: jest.fn().mockImplementation(() => ({
    verifyIdToken: mockVerifyIdToken,
  })),
}));

// Set test environment variables
process.env.JWT_SECRET = 'test-jwt-secret-at-least-32-chars-long-for-security';
process.env.GOOGLE_WEB_CLIENT_ID = 'test-web-client-id.apps.googleusercontent.com';
process.env.FRONTEND_URL = 'http://localhost:3000';

// =============================================================================
// DYNAMIC IMPORTS (must come after unstable_mockModule calls)
// =============================================================================

let jwt;
let express;
let request;
let mobileAuthRouter;
let authRouter;

beforeAll(async () => {
  jwt = (await import('jsonwebtoken')).default;
  express = (await import('express')).default;
  const supertest = await import('supertest');
  request = supertest.default;
  mobileAuthRouter = (await import('../features/auth/routes/mobileAuth.routes.js')).default;
  authRouter = (await import('../features/auth/routes/auth.routes.js')).default;
});

// =============================================================================
// TEST HELPERS
// =============================================================================

/**
 * Build a test Express app mounting the auth routers.
 */
function buildApp() {
  const app = express();
  app.use(express.json());
  app.use('/api/mobile/auth', mobileAuthRouter);
  app.use('/api/auth', authRouter);
  return app;
}

/**
 * Generate a valid JWT for a given user payload.
 */
function makeToken(overrides = {}) {
  return jwt.sign(
    {
      userId: overrides.userId ?? 1,
      email: overrides.email ?? 'test@example.com',
      name: overrides.name ?? 'Test User',
      role: overrides.role ?? 'user',
      ...overrides,
    },
    process.env.JWT_SECRET,
    { expiresIn: '15m' },
  );
}

/**
 * Create a mock Google token payload.
 */
function makeGooglePayload(overrides = {}) {
  return {
    sub: overrides.sub ?? 'google-uid-123',
    email: overrides.email ?? 'alice@example.com',
    email_verified: overrides.email_verified ?? true,
    name: overrides.name ?? 'Alice',
    picture: overrides.picture ?? 'https://example.com/photo.jpg',
    ...overrides,
  };
}

/**
 * Create a mock user row as Prisma would return it.
 */
function makeUser(overrides = {}) {
  return {
    id: overrides.id ?? 1,
    email: overrides.email ?? 'alice@example.com',
    name: overrides.name ?? 'Alice',
    image: overrides.image ?? null,
    authProvider: overrides.authProvider ?? 'google',
    authProviderId: overrides.authProviderId ?? 'google-uid-123',
    role: overrides.role ?? 'user',
    status: overrides.status ?? 'activated',
    isVerified: overrides.isVerified ?? true,
    createdAt: new Date(),
    updatedAt: new Date(),
    ...overrides,
  };
}

/**
 * Create a mock RefreshToken row.
 */
function makeRefreshToken(overrides = {}) {
  const now = Date.now();
  return {
    id: overrides.id ?? 1,
    tokenHash: overrides.tokenHash ?? 'abc123hash',
    userId: overrides.userId ?? 1,
    platform: overrides.platform ?? 'android',
    deviceId: overrides.deviceId ?? null,
    expiresAt: overrides.expiresAt ?? new Date(now + 30 * 24 * 60 * 60 * 1000),
    revokedAt: overrides.revokedAt ?? null,
    createdAt: new Date(),
    ...overrides,
  };
}

// =============================================================================
// RESET ALL MOCKS BEFORE EACH TEST
// =============================================================================

beforeEach(() => {
  jest.clearAllMocks();
});

// =============================================================================
// TESTS: POST /api/mobile/auth/google/android-token
// =============================================================================

describe('POST /api/mobile/auth/google/android-token', () => {
  // ---------------------------------------------------------------------------
  // 1. Missing idToken fails
  // ---------------------------------------------------------------------------
  it('returns 400 when idToken is missing', async () => {
    const app = buildApp();
    const res = await request(app)
      .post('/api/mobile/auth/google/android-token')
      .send({ platform: 'android' });

    expect(res.status).toBe(400);
    expect(res.body.success).toBe(false);
    expect(res.body.code).toBe('GOOGLE_TOKEN_REQUIRED');
  });

  // ---------------------------------------------------------------------------
  // 2. Invalid (unverifiable) token fails
  // ---------------------------------------------------------------------------
  it('returns 401 when Google ID token verification fails', async () => {
    mockVerifyIdToken.mockRejectedValueOnce(new Error('Invalid token'));

    const app = buildApp();
    const res = await request(app)
      .post('/api/mobile/auth/google/android-token')
      .send({ idToken: 'bad-token', platform: 'android' });

    expect(res.status).toBe(401);
    expect(res.body.success).toBe(false);
    expect(res.body.code).toBe('GOOGLE_TOKEN_INVALID');
  });

  // ---------------------------------------------------------------------------
  // 3. Valid token creates a new user
  // ---------------------------------------------------------------------------
  it('returns 200 and creates a new user when Google ID token is valid', async () => {
    const googlePayload = makeGooglePayload();
    mockVerifyIdToken.mockResolvedValueOnce({
      getPayload: () => googlePayload,
    });
    // No existing user found by google ID
    mockPrisma.user.findFirst.mockResolvedValueOnce(null);
    // No existing user found by email
    mockPrisma.user.findUnique.mockResolvedValueOnce(null);
    // Create succeeds
    mockPrisma.user.create.mockResolvedValueOnce(makeUser());

    const app = buildApp();
    const res = await request(app)
      .post('/api/mobile/auth/google/android-token')
      .send({ idToken: 'valid-token', platform: 'android' });

    expect(res.status).toBe(200);
    expect(res.body.success).toBe(true);
    expect(res.body.accessToken).toBeDefined();
    expect(res.body.refreshToken).toBeDefined();
    expect(res.body.user.email).toBe(googlePayload.email);
    expect(res.body.tokenType).toBe('Bearer');
  });

  // ---------------------------------------------------------------------------
  // 4. Same email does NOT duplicate user (finds existing user by googleId)
  // ---------------------------------------------------------------------------
  it('does not duplicate user when same Google account logs in again', async () => {
    const googlePayload = makeGooglePayload();
    const existingUser = makeUser({ id: 1 });

    mockVerifyIdToken.mockResolvedValueOnce({
      getPayload: () => googlePayload,
    });
    // Existing user found by authProviderId
    mockPrisma.user.findFirst.mockResolvedValueOnce(existingUser);
    // update returns the user (with refreshed name/image)
    mockPrisma.user.update.mockResolvedValueOnce(existingUser);

    const app = buildApp();
    const res = await request(app)
      .post('/api/mobile/auth/google/android-token')
      .send({ idToken: 'valid-token', platform: 'android' });

    expect(res.status).toBe(200);
    expect(res.body.success).toBe(true);
    // update should have been called, not create
    expect(mockPrisma.user.create).not.toHaveBeenCalled();
    expect(mockPrisma.user.update).toHaveBeenCalled();
    expect(res.body.user.email).toBe(googlePayload.email);
  });

  // ---------------------------------------------------------------------------
  // 5. Unverified email fails
  // ---------------------------------------------------------------------------
  it('returns 403 when Google account email is not verified', async () => {
    const googlePayload = makeGooglePayload({ email_verified: false });

    mockVerifyIdToken.mockResolvedValueOnce({
      getPayload: () => googlePayload,
    });

    const app = buildApp();
    const res = await request(app)
      .post('/api/mobile/auth/google/android-token')
      .send({ idToken: 'valid-token-unverified', platform: 'android' });

    expect(res.status).toBe(403);
    expect(res.body.success).toBe(false);
    expect(res.body.code).toBe('GOOGLE_EMAIL_NOT_VERIFIED');
  });

  // ---------------------------------------------------------------------------
  // 6. Suspended user fails
  // ---------------------------------------------------------------------------
  it('returns 403 when user status is suspended', async () => {
    const googlePayload = makeGooglePayload();
    const suspendedUser = makeUser({ status: 'suspended' });

    mockVerifyIdToken.mockResolvedValueOnce({
      getPayload: () => googlePayload,
    });
    // findFirst returns suspended user
    mockPrisma.user.findFirst.mockResolvedValueOnce(suspendedUser);
    // update returns the suspended user (controller updates name/image first)
    mockPrisma.user.update.mockResolvedValueOnce(suspendedUser);

    const app = buildApp();
    const res = await request(app)
      .post('/api/mobile/auth/google/android-token')
      .send({ idToken: 'valid-token-suspended', platform: 'android' });

    expect(res.status).toBe(403);
    expect(res.body.success).toBe(false);
    expect(res.body.code).toBe('USER_DISABLED');
  });
});

// =============================================================================
// TESTS: POST /api/mobile/auth/refresh
// =============================================================================

describe('POST /api/mobile/auth/refresh', () => {
  // ---------------------------------------------------------------------------
  // 7. Valid refresh succeeds
  // ---------------------------------------------------------------------------
  it('returns 200 with new access token when refresh token is valid', async () => {
    const user = makeUser();
    const storedToken = makeRefreshToken({ userId: user.id });

    mockPrisma.refreshToken.findUnique.mockResolvedValueOnce(storedToken);
    mockPrisma.user.findUnique.mockResolvedValueOnce(user);

    const app = buildApp();
    const res = await request(app)
      .post('/api/mobile/auth/refresh')
      .send({ refreshToken: 'some-valid-refresh-token' });

    expect(res.status).toBe(200);
    expect(res.body.success).toBe(true);
    expect(res.body.accessToken).toBeDefined();
    expect(res.body.expiresIn).toBeDefined();
  });

  // ---------------------------------------------------------------------------
  // 8. Revoked refresh fails
  // ---------------------------------------------------------------------------
  it('returns 401 when refresh token has been revoked', async () => {
    const token = makeRefreshToken({ revokedAt: new Date() });

    mockPrisma.refreshToken.findUnique.mockResolvedValueOnce(token);

    const app = buildApp();
    const res = await request(app)
      .post('/api/mobile/auth/refresh')
      .send({ refreshToken: 'revoked-token' });

    expect(res.status).toBe(401);
    expect(res.body.success).toBe(false);
    expect(res.body.code).toBe('REFRESH_TOKEN_REVOKED');
  });

  // ---------------------------------------------------------------------------
  // 9. Expired refresh fails
  // ---------------------------------------------------------------------------
  it('returns 401 when refresh token has expired', async () => {
    const pastDate = new Date(Date.now() - 24 * 60 * 60 * 1000);
    const token = makeRefreshToken({ expiresAt: pastDate });

    mockPrisma.refreshToken.findUnique.mockResolvedValueOnce(token);

    const app = buildApp();
    const res = await request(app)
      .post('/api/mobile/auth/refresh')
      .send({ refreshToken: 'expired-token' });

    expect(res.status).toBe(401);
    expect(res.body.success).toBe(false);
    expect(res.body.code).toBe('REFRESH_TOKEN_EXPIRED');
  });

  // ---------------------------------------------------------------------------
  // 10. Non-existent refresh token fails
  // ---------------------------------------------------------------------------
  it('returns 401 when refresh token does not exist', async () => {
    mockPrisma.refreshToken.findUnique.mockResolvedValueOnce(null);

    const app = buildApp();
    const res = await request(app)
      .post('/api/mobile/auth/refresh')
      .send({ refreshToken: 'nonexistent-token' });

    expect(res.status).toBe(401);
    expect(res.body.success).toBe(false);
    expect(res.body.code).toBe('REFRESH_TOKEN_INVALID');
  });
});

// =============================================================================
// TESTS: POST /api/mobile/auth/logout
// =============================================================================

describe('POST /api/mobile/auth/logout', () => {
  // ---------------------------------------------------------------------------
  // 11. Valid logout revokes token
  // ---------------------------------------------------------------------------
  it('revokes the refresh token and returns success', async () => {
    const token = makeRefreshToken({ userId: 1 });

    mockPrisma.refreshToken.findUnique.mockResolvedValueOnce(token);

    const app = buildApp();
    const res = await request(app)
      .post('/api/mobile/auth/logout')
      .send({ refreshToken: 'valid-token-to-revoke' });

    expect(res.status).toBe(200);
    expect(res.body.success).toBe(true);
    expect(res.body.code).toBe('LOGOUT_SUCCESS');
    // Should have called update to set revokedAt
    expect(mockPrisma.refreshToken.update).toHaveBeenCalledWith(
      expect.objectContaining({
        where: { id: token.id },
        data: expect.objectContaining({ revokedAt: expect.any(Date) }),
      }),
    );
  });

  // ---------------------------------------------------------------------------
  // 12. Repeated logout is safe (no error when token already revoked)
  // ---------------------------------------------------------------------------
  it('is idempotent — returns success even if token is already revoked', async () => {
    const token = makeRefreshToken({ revokedAt: new Date() });

    mockPrisma.refreshToken.findUnique.mockResolvedValueOnce(token);

    const app = buildApp();
    const res = await request(app)
      .post('/api/mobile/auth/logout')
      .send({ refreshToken: 'already-revoked-token' });

    expect(res.status).toBe(200);
    expect(res.body.success).toBe(true);
    // Should NOT call update again because revokedAt is already set
    expect(mockPrisma.refreshToken.update).not.toHaveBeenCalled();
  });

  // ---------------------------------------------------------------------------
  // 13. Logout without refreshToken still succeeds (graceful)
  // ---------------------------------------------------------------------------
  it('returns success even when no refreshToken provided', async () => {
    const app = buildApp();
    const res = await request(app)
      .post('/api/mobile/auth/logout')
      .send({});

    expect(res.status).toBe(200);
    expect(res.body.success).toBe(true);
  });
});

// =============================================================================
// TESTS: GET /api/auth/me
// =============================================================================

describe('GET /api/auth/me', () => {
  // ---------------------------------------------------------------------------
  // 14. Valid bearer token returns current user
  // ---------------------------------------------------------------------------
  it('returns the current user for a valid JWT', async () => {
    const user = makeUser({ id: 1, email: 'alice@example.com' });
    mockPrisma.user.findUnique.mockResolvedValueOnce(user);

    const token = makeToken({ userId: 1, email: 'alice@example.com' });

    const app = buildApp();
    const res = await request(app)
      .get('/api/auth/me')
      .set('Authorization', `Bearer ${token}`);

    expect(res.status).toBe(200);
    expect(res.body.success).toBe(true);
    expect(res.body.user.id).toBe(1);
    expect(res.body.user.email).toBe('alice@example.com');
  });

  // ---------------------------------------------------------------------------
  // 15. Invalid token fails
  // ---------------------------------------------------------------------------
  it('returns 401 for an invalid/malformed JWT', async () => {
    const app = buildApp();
    const res = await request(app)
      .get('/api/auth/me')
      .set('Authorization', 'Bearer this-is-not-a-valid-jwt');

    expect(res.status).toBe(401);
    expect(res.body.success).toBe(false);
  });

  // ---------------------------------------------------------------------------
  // 16. Token A never returns user B (user isolation)
  // ---------------------------------------------------------------------------
  it('token for user A never returns user B', async () => {
    // User A
    const userA = makeUser({ id: 1, email: 'alice@example.com' });

    // Create token for user A
    const tokenA = makeToken({ userId: 1, email: 'alice@example.com' });

    // When /api/auth/me is called with tokenA, it should decode to userId=1
    mockPrisma.user.findUnique.mockResolvedValueOnce(userA);

    const app = buildApp();
    const res = await request(app)
      .get('/api/auth/me')
      .set('Authorization', `Bearer ${tokenA}`);

    expect(res.status).toBe(200);
    expect(res.body.user.id).toBe(1);
    expect(res.body.user.email).toBe('alice@example.com');
    // Assert it's NOT user B
    expect(res.body.user.id).not.toBe(2);
    expect(res.body.user.email).not.toBe('bob@example.com');
  });

  // ---------------------------------------------------------------------------
  // 17. Missing Authorization header returns 401
  // ---------------------------------------------------------------------------
  it('returns 401 when no Authorization header is provided', async () => {
    const app = buildApp();
    const res = await request(app).get('/api/auth/me');

    expect(res.status).toBe(401);
    expect(res.body.success).toBe(false);
  });
});

// =============================================================================
// REGRESSION: Session Contamination
// =============================================================================

describe('Regression — Session Contamination', () => {
  // ---------------------------------------------------------------------------
  // 18. android-token revokes previous tokens for the same user
  // ---------------------------------------------------------------------------
  it('revokes old refresh tokens when issuing new ones for the same user', async () => {
    const googlePayload = makeGooglePayload();
    const existingUser = makeUser({ id: 1 });

    mockVerifyIdToken.mockResolvedValueOnce({
      getPayload: () => googlePayload,
    });
    mockPrisma.user.findFirst.mockResolvedValueOnce(existingUser);
    mockPrisma.user.update.mockResolvedValueOnce(existingUser);

    const app = buildApp();
    const res = await request(app)
      .post('/api/mobile/auth/google/android-token')
      .send({ idToken: 'valid-token', platform: 'android' });

    expect(res.status).toBe(200);
    // updateMany should have been called to revoke old tokens
    expect(mockPrisma.refreshToken.updateMany).toHaveBeenCalledWith(
      expect.objectContaining({
        where: expect.objectContaining({
          userId: 1,
          platform: 'android',
          revokedAt: null,
        }),
        data: expect.objectContaining({ revokedAt: expect.any(Date) }),
      }),
    );
  });

  // ---------------------------------------------------------------------------
  // 19. Logout prevents subsequent refresh
  // ---------------------------------------------------------------------------
  it('after logout, the same refresh token cannot be used to refresh', async () => {
    const user = makeUser();
    const token = makeRefreshToken({ userId: user.id });

    // Logout: findUnique returns the active token
    mockPrisma.refreshToken.findUnique.mockResolvedValueOnce(token);

    const app = buildApp();
    const logoutRes = await request(app)
      .post('/api/mobile/auth/logout')
      .send({ refreshToken: 'token-to-revoke' });

    expect(logoutRes.status).toBe(200);

    // Now simulate the token being revoked in DB
    const revokedToken = makeRefreshToken({ userId: user.id, revokedAt: new Date() });
    mockPrisma.refreshToken.findUnique.mockResolvedValueOnce(revokedToken);

    // Attempt to refresh with the same token — should return 401
    const refreshRes = await request(app)
      .post('/api/mobile/auth/refresh')
      .send({ refreshToken: 'token-to-revoke' });

    expect(refreshRes.status).toBe(401);
    expect(refreshRes.body.code).toBe('REFRESH_TOKEN_REVOKED');
  });
});
