import { describe, test, expect, beforeAll, afterAll } from '@jest/globals';
import request from 'supertest';
import { createServer } from 'http';
import app from '../app.js';
import prisma from '../config/database.config.js';

const server = createServer(app);

const DEVICE_A = { deviceId: 'device-a-uuid', deviceType: 'android', appVersion: '2.0.0' };
const DEVICE_B = { deviceId: 'device-b-uuid', deviceType: 'electron', appVersion: '2.0.0' };
const DEVICE_C = { deviceId: 'device-c-uuid', deviceType: 'android', appVersion: '2.0.0', deviceName: 'Test Pixel 8' };
const TEST_USER = { email: 'scheduler-test@fundocareer.com', name: 'Scheduler Test' };

let testUserId = null;
let authToken = null;
const PREF_SET = 'test-preference-set-id-123';

beforeAll(async () => {
  const user = await prisma.user.upsert({
    where: { email: TEST_USER.email },
    create: {
      email: TEST_USER.email,
      name: TEST_USER.name,
      authProvider: 'test',
      authProviderId: 'test-scheduler',
    },
    update: {},
  });
  testUserId = user.id;

  const jwt = await import('jsonwebtoken');
  authToken = jwt.default.sign(
    { userId: user.id, email: user.email, name: user.name, role: user.role || 'user' },
    process.env.JWT_SECRET || 'dev-jwt-secret-fundocareer-local-2026',
    { expiresIn: '1h' },
  );

  await prisma.emailedJobFingerprint.deleteMany({ where: { userEmail: TEST_USER.email } });
  await prisma.schedulerLock.deleteMany({ where: { userEmail: TEST_USER.email } });
  await prisma.schedulerDeviceState.deleteMany({ where: { userEmail: TEST_USER.email } });
  await prisma.schedulerRunHistory.deleteMany({ where: { userEmail: TEST_USER.email } });
});

afterAll(async () => {
  await prisma.emailedJobFingerprint.deleteMany({ where: { userEmail: TEST_USER.email } });
  await prisma.schedulerLock.deleteMany({ where: { userEmail: TEST_USER.email } });
  await prisma.schedulerDeviceState.deleteMany({ where: { userEmail: TEST_USER.email } });
  await prisma.schedulerRunHistory.deleteMany({ where: { userEmail: TEST_USER.email } });
  server.close();
});

function authReq() {
  return request(server);
}

function setAuth(req) {
  return req
    .set('Authorization', `Bearer ${authToken}`)
    .set('Content-Type', 'application/json');
}

// ================================================================
// LOCK TESTS
// ================================================================

describe('Lock Acquisition', () => {

  test('Device A acquires lock successfully', async () => {
    const res = await setAuth(authReq()
      .post('/api/scheduler/acquire-lock'))
      .send({ preferenceSetId: PREF_SET, ...DEVICE_A });

    expect(res.status).toBe(200);
    expect(res.body.success).toBe(true);
    expect(res.body.data.granted).toBe(true);
    expect(res.body.data.lockId).toBeGreaterThan(0);
    expect(res.body.data.expiresAt).toBeDefined();
  });

  test('Device B denied lock while Device A holds it', async () => {
    const res = await setAuth(authReq()
      .post('/api/scheduler/acquire-lock'))
      .send({ preferenceSetId: PREF_SET, ...DEVICE_B });

    expect(res.status).toBe(200);
    expect(res.body.success).toBe(true);
    expect(res.body.data.granted).toBe(false);
    expect(res.body.data.heldBy).toBe(DEVICE_A.deviceId);
  });

  test('Device A releases lock successfully', async () => {
    const res = await setAuth(authReq()
      .post('/api/scheduler/release-lock'))
      .send({ preferenceSetId: PREF_SET, deviceId: DEVICE_A.deviceId });

    expect(res.status).toBe(200);
    expect(res.body.success).toBe(true);
    expect(res.body.data.released).toBe(true);
  });

  test('Device B cannot release Device A lock', async () => {
    await setAuth(authReq()
      .post('/api/scheduler/acquire-lock'))
      .send({ preferenceSetId: PREF_SET, ...DEVICE_A });

    const res = await setAuth(authReq()
      .post('/api/scheduler/release-lock'))
      .send({ preferenceSetId: PREF_SET, deviceId: DEVICE_B.deviceId });

    expect(res.status).toBe(200);
    expect(res.body.data.released).toBe(false);
    expect(res.body.data.reason).toBe('not_lock_holder');

    await setAuth(authReq()
      .post('/api/scheduler/release-lock'))
      .send({ preferenceSetId: PREF_SET, deviceId: DEVICE_A.deviceId });
  });

  test('Device B acquires after Device A releases', async () => {
    await setAuth(authReq()
      .post('/api/scheduler/acquire-lock'))
      .send({ preferenceSetId: PREF_SET, ...DEVICE_A });

    await setAuth(authReq()
      .post('/api/scheduler/release-lock'))
      .send({ preferenceSetId: PREF_SET, deviceId: DEVICE_A.deviceId });

    const res = await setAuth(authReq()
      .post('/api/scheduler/acquire-lock'))
      .send({ preferenceSetId: PREF_SET, ...DEVICE_B });

    expect(res.status).toBe(200);
    expect(res.body.data.granted).toBe(true);
  });
});

describe('Lock Expiration', () => {

  test('Lock is denied with TTL and later re-acquirable', async () => {
    const prefExpire = 'pref-expire-test';
    await setAuth(authReq()
      .post('/api/scheduler/acquire-lock'))
      .send({ preferenceSetId: prefExpire, ...DEVICE_A });

    const res = await setAuth(authReq()
      .post('/api/scheduler/acquire-lock'))
      .send({ preferenceSetId: prefExpire, ...DEVICE_B });

    expect(res.body.data.granted).toBe(false);

    await setAuth(authReq()
      .post('/api/scheduler/release-lock'))
      .send({ preferenceSetId: prefExpire, deviceId: DEVICE_A.deviceId });
  });
});

// ================================================================
// DUPLICATE EMAIL PREVENTION
// ================================================================

describe('Duplicate Email Prevention', () => {

  const testFingerprints = ['fp-linkedin-1', 'fp-linkedin-2', 'fp-indeed-1'];
  const PREF_ID = 'duplicate-test-pref';

  test('Check returns no existing emails initially', async () => {
    const res = await setAuth(authReq()
      .post('/api/scheduler/check-emailed-jobs'))
      .send({ fingerprints: testFingerprints, preferenceId: PREF_ID });

    expect(res.status).toBe(200);
    expect(res.body.data.alreadyEmailed).toEqual([]);
    expect(res.body.data.new).toEqual(testFingerprints);
  });

  test('Mark fingerprints as emailed', async () => {
    const res = await setAuth(authReq()
      .post('/api/scheduler/mark-emailed-jobs'))
      .send({ fingerprints: testFingerprints.slice(0, 2), preferenceId: PREF_ID });

    expect(res.status).toBe(200);
    expect(res.body.data.marked).toBe(2);
  });

  test('Duplicate mark returns skipped', async () => {
    const res = await setAuth(authReq()
      .post('/api/scheduler/mark-emailed-jobs'))
      .send({ fingerprints: testFingerprints, preferenceId: PREF_ID });

    expect(res.status).toBe(200);
    expect(res.body.data.marked).toBe(1); // only fp-indeed-1 is new
    expect(res.body.data.skippedDuplicates).toBe(2);
  });

  test('Check reflects already emailed fingerprints', async () => {
    const res = await setAuth(authReq()
      .post('/api/scheduler/check-emailed-jobs'))
      .send({ fingerprints: testFingerprints, preferenceId: PREF_ID });

    expect(res.status).toBe(200);
    expect(res.body.data.alreadyEmailed).toEqual(
      expect.arrayContaining(testFingerprints),
    );
    expect(res.body.data.new).toEqual([]);
  });
});

// ================================================================
// SYNC STATE TESTS
// ================================================================

describe('State Sync', () => {

  test('Sync state creates device record', async () => {
    const res = await setAuth(authReq()
      .post('/api/scheduler/sync-state'))
      .send({
        ...DEVICE_A,
        preferences: { role: 'Android Developer', location: 'Remote' },
        schedulerState: { enabled: true, intervalMinutes: 15 },
        recentRuns: [
          { preferenceId: 'pref-1', runId: 'run-1', status: 'success', jobsFound: 10, jobsNew: 3, startedAt: new Date().toISOString(), triggeredBy: 'scheduler' },
        ],
      });

    expect(res.status).toBe(200);
    expect(res.body.success).toBe(true);
    expect(res.body.data.syncedAt).toBeDefined();
  });

  test('Get state returns device record and active locks', async () => {
    const res = await setAuth(authReq()
      .get(`/api/scheduler/state?deviceId=${DEVICE_A.deviceId}`));

    expect(res.status).toBe(200);
    expect(res.body.data.deviceState).not.toBeNull();
    expect(res.body.data.deviceState.deviceId).toBe(DEVICE_A.deviceId);
    expect(res.body.data.recentRuns.length).toBeGreaterThanOrEqual(1);
  });
});

// ================================================================
// HISTORY SUMMARY
// ================================================================

describe('History Summary', () => {

  test('Returns aggregate stats', async () => {
    const res = await setAuth(authReq()
      .post('/api/scheduler/history-summary'))
      .send({});

    expect(res.status).toBe(200);
    expect(res.body.data.totalRuns).toBeGreaterThanOrEqual(1);
    expect(res.body.data.totalJobsFound).toBeGreaterThanOrEqual(10);
    expect(typeof res.body.data.totalEmailsSent).toBe('number');
  });
});

// ================================================================
// AUTH GUARD TESTS
// ================================================================

describe('Authentication Guards', () => {

  test('Unauthenticated request is rejected', async () => {
    const res = await request(server)
      .post('/api/scheduler/acquire-lock')
      .send({ preferenceSetId: 'x', deviceId: 'y', deviceType: 'android' });

    expect(res.status).toBe(401);
    expect(res.body.success).toBe(false);
  });

  test('Missing fields return 400', async () => {
    const res = await setAuth(authReq()
      .post('/api/scheduler/acquire-lock'))
      .send({});

    expect(res.status).toBe(400);
    expect(res.body.success).toBe(false);
  });
});

// ================================================================
// NOTIFICATION EMAIL TESTS
// ================================================================

describe('Notification Email Endpoint', () => {

  const sampleJobs = [
    { jobTitle: 'Android Developer', company: 'Google', location: 'Mountain View', fingerprint: 'fp-email-test-1' },
    { jobTitle: 'iOS Developer', company: 'Apple', location: 'Cupertino', fingerprint: 'fp-email-test-2' },
  ];

  test('Unauthenticated email request is rejected', async () => {
    const res = await request(server)
      .post('/api/notifications/send-email')
      .send({ to: TEST_USER.email, preferenceId: 'test-pref', newJobs: sampleJobs });

    expect(res.status).toBe(401);
    expect(res.body.success).toBe(false);
  });

  test('Missing required fields returns 400', async () => {
    const res = await setAuth(authReq()
      .post('/api/notifications/send-email'))
      .send({ to: TEST_USER.email });

    expect(res.status).toBe(400);
    expect(res.body.success).toBe(false);
  });

  test('Empty newJobs returns 400', async () => {
    const res = await setAuth(authReq()
      .post('/api/notifications/send-email'))
      .send({ to: TEST_USER.email, preferenceId: 'test-pref', newJobs: [] });

    expect(res.status).toBe(400);
    expect(res.body.success).toBe(false);
  });

  test('Too many jobs returns 400', async () => {
    const tooMany = Array(51).fill(null).map((_, i) => ({
      jobTitle: `Job ${i}`, company: 'Test', fingerprint: `fp-test-${i}`,
    }));
    const res = await setAuth(authReq()
      .post('/api/notifications/send-email'))
      .send({ to: TEST_USER.email, preferenceId: 'test-pref', newJobs: tooMany });

    expect(res.status).toBe(400);
    expect(res.body.success).toBe(false);
  });

  test('Recipient mismatch returns 403', async () => {
    const res = await setAuth(authReq()
      .post('/api/notifications/send-email'))
      .send({ to: 'other@email.com', preferenceId: 'test-pref', newJobs: sampleJobs });

    expect(res.status).toBe(403);
    expect(res.body.success).toBe(false);
  });

  test('Sends email with metadata and PDF attachment', async () => {
    const res = await setAuth(authReq()
      .post('/api/notifications/send-email'))
      .send({
        to: TEST_USER.email,
        preferenceId: 'test-pref-metadata',
        preferenceName: 'Test Role',
        newJobs: [{ jobTitle: 'Test', company: 'Co', fingerprint: 'fp-metadata-1' }],
        allJobs: 5,
        deviceId: 'test-device',
        runId: 'test-run-123',
      });

    expect(res.body.success).toBe(true);
    expect(res.body.emailSent).toBe(true);
    expect(res.body.jobsSent).toBe(1);
  });

  test('Ignores legacy attachment field, sends with PDF instead', async () => {
    const res = await setAuth(authReq()
      .post('/api/notifications/send-email'))
      .send({
        to: TEST_USER.email,
        preferenceId: 'test-pref-attachment',
        newJobs: [{ jobTitle: 'Test', company: 'Co', fingerprint: 'fp-attach-1' }],
        attachment: {
          filename: 'report.txt',
          content: 'Job report content here',
        },
      });

    // Legacy client attachment is ignored; server generates PDF server-side
    expect(res.body.success).toBe(true);
    expect(res.body.emailSent).toBe(true);
    expect(res.body.jobsSent).toBe(1);
  });

  test('Large request body does not prevent email (attachments validated server-side now)', async () => {
    const bigContent = 'x'.repeat(11 * 1024 * 1024);
    const res = await setAuth(authReq()
      .post('/api/notifications/send-email'))
      .send({
        to: TEST_USER.email,
        preferenceId: 'test-pref-big',
        newJobs: [{ jobTitle: 'Test', company: 'Co', fingerprint: 'fp-big-1' }],
        attachment: { filename: 'big.txt', content: bigContent },
      });

    // Attachment field is now ignored; server generates PDF server-side
    expect(res.body.success).toBe(true);
    expect(res.body.emailSent).toBe(true);
  });
});

// ================================================================
// ACTIVE DEVICE TESTS
// ================================================================

describe('Active Device Management', () => {

  test('GET active-device returns null when no device is active', async () => {
    const res = await setAuth(authReq()
      .get(`/api/scheduler/active-device?deviceId=${DEVICE_A.deviceId}`));

    expect(res.status).toBe(200);
    expect(res.body.success).toBe(true);
    expect(res.body.activeDevice).toBeNull();
    expect(res.body.isCurrentDeviceActive).toBe(false);
  });

  test('Activate Device A successfully', async () => {
    const res = await setAuth(authReq()
      .post('/api/scheduler/active-device/activate'))
      .send({
        deviceId: DEVICE_A.deviceId,
        deviceName: DEVICE_A.deviceName || 'Device A',
        devicePlatform: DEVICE_A.deviceType,
        schedulerPreferenceId: 'pref-123',
      });

    expect(res.status).toBe(200);
    expect(res.body.success).toBe(true);
    expect(res.body.activeDevice).not.toBeNull();
    expect(res.body.activeDevice.deviceId).toBe(DEVICE_A.deviceId);
    expect(res.body.activeDevice.devicePlatform).toBe(DEVICE_A.deviceType);
    expect(res.body.activeDevice.deviceName).toBe('Device A');
  });

  test('GET active-device shows Device A as active', async () => {
    const res = await setAuth(authReq()
      .get(`/api/scheduler/active-device?deviceId=${DEVICE_A.deviceId}`));

    expect(res.status).toBe(200);
    expect(res.body.activeDevice.deviceId).toBe(DEVICE_A.deviceId);
    expect(res.body.isCurrentDeviceActive).toBe(true);
  });

  test('GET active-device shows another device as not active', async () => {
    const res = await setAuth(authReq()
      .get(`/api/scheduler/active-device?deviceId=${DEVICE_B.deviceId}`));

    expect(res.status).toBe(200);
    expect(res.body.activeDevice.deviceId).toBe(DEVICE_A.deviceId);
    expect(res.body.isCurrentDeviceActive).toBe(false);
  });

  test('Activate Device B replaces Device A', async () => {
    const res = await setAuth(authReq()
      .post('/api/scheduler/active-device/activate'))
      .send({
        deviceId: DEVICE_B.deviceId,
        deviceName: 'Device B',
        devicePlatform: DEVICE_B.deviceType,
        schedulerPreferenceId: 'pref-456',
      });

    expect(res.status).toBe(200);
    expect(res.body.activeDevice.deviceId).toBe(DEVICE_B.deviceId);

    const getRes = await setAuth(authReq()
      .get(`/api/scheduler/active-device?deviceId=${DEVICE_A.deviceId}`));
    expect(getRes.body.activeDevice.deviceId).toBe(DEVICE_B.deviceId);
    expect(getRes.body.isCurrentDeviceActive).toBe(false);
  });

  test('Heartbeat for active device succeeds', async () => {
    const res = await setAuth(authReq()
      .post('/api/scheduler/active-device/heartbeat'))
      .send({
        deviceId: DEVICE_B.deviceId,
        schedulerPreferenceId: 'pref-456',
      });

    expect(res.status).toBe(200);
    expect(res.body.isCurrentDeviceActive).toBe(true);
    expect(res.body.activeDevice.deviceId).toBe(DEVICE_B.deviceId);
    expect(res.body.activeDevice.activeDeviceLastSeenAt).toBeDefined();
  });

  test('Heartbeat for inactive device returns isCurrentDeviceActive=false', async () => {
    const res = await setAuth(authReq()
      .post('/api/scheduler/active-device/heartbeat'))
      .send({
        deviceId: DEVICE_A.deviceId,
        schedulerPreferenceId: 'pref-123',
      });

    expect(res.status).toBe(200);
    expect(res.body.isCurrentDeviceActive).toBe(false);
  });

  test('Verify for active device returns canSend=true', async () => {
    const res = await setAuth(authReq()
      .post('/api/scheduler/active-device/verify'))
      .send({
        deviceId: DEVICE_B.deviceId,
        schedulerPreferenceId: 'pref-456',
      });

    expect(res.status).toBe(200);
    expect(res.body.canSend).toBe(true);
  });

  test('Verify for inactive device returns canSend=false', async () => {
    const res = await setAuth(authReq()
      .post('/api/scheduler/active-device/verify'))
      .send({
        deviceId: DEVICE_A.deviceId,
        schedulerPreferenceId: 'pref-123',
      });

    expect(res.status).toBe(200);
    expect(res.body.canSend).toBe(false);
    expect(res.body.reason).toBe('device_not_active');
  });

  test('Verify returns no_active_device when none is active', async () => {
    await setAuth(authReq()
      .post('/api/scheduler/active-device/deactivate'))
      .send({ deviceId: DEVICE_B.deviceId });

    const res = await setAuth(authReq()
      .post('/api/scheduler/active-device/verify'))
      .send({
        deviceId: DEVICE_A.deviceId,
        schedulerPreferenceId: 'pref-123',
      });

    expect(res.status).toBe(200);
    expect(res.body.canSend).toBe(false);
    expect(res.body.reason).toBe('no_active_device');
  });

  test('Deactivate only works for the active device', async () => {
    await setAuth(authReq()
      .post('/api/scheduler/active-device/activate'))
      .send({
        deviceId: DEVICE_C.deviceId,
        deviceName: DEVICE_C.deviceName,
        devicePlatform: DEVICE_C.deviceType,
        schedulerPreferenceId: 'pref-789',
      });

    const wrongDevice = await setAuth(authReq()
      .post('/api/scheduler/active-device/deactivate'))
      .send({ deviceId: DEVICE_A.deviceId });
    expect(wrongDevice.body.deactivated).toBe(false);
    expect(wrongDevice.body.reason).toBe('not_active_device');

    const correctDevice = await setAuth(authReq()
      .post('/api/scheduler/active-device/deactivate'))
      .send({ deviceId: DEVICE_C.deviceId });
    expect(correctDevice.body.deactivated).toBe(true);

    const getRes = await setAuth(authReq()
      .get(`/api/scheduler/active-device?deviceId=${DEVICE_C.deviceId}`));
    expect(getRes.body.activeDevice).toBeNull();
  });

  test('Unauthenticated request is rejected', async () => {
    const res = await request(server)
      .post('/api/scheduler/active-device/activate')
      .send({ deviceId: 'x', devicePlatform: 'android' });

    expect(res.status).toBe(401);
    expect(res.body.success).toBe(false);
  });

  test('Missing required fields return 400', async () => {
    const res = await setAuth(authReq()
      .post('/api/scheduler/active-device/activate'))
      .send({ deviceId: 'x' });

    expect(res.status).toBe(400);
    expect(res.body.success).toBe(false);
  });

  test('Activate device stores intervalMinutes', async () => {
    await setAuth(authReq()
      .post('/api/scheduler/active-device/deactivate'))
      .send({ deviceId: DEVICE_A.deviceId });

    const res = await setAuth(authReq()
      .post('/api/scheduler/active-device/activate'))
      .send({
        deviceId: DEVICE_A.deviceId,
        deviceName: 'Device A',
        devicePlatform: DEVICE_A.deviceType,
        schedulerPreferenceId: 'pref-interval',
        intervalMinutes: 60,
      });

    expect(res.status).toBe(200);
    expect(res.body.activeDevice.intervalMinutes).toBe(60);

    const getRes = await setAuth(authReq()
      .get(`/api/scheduler/active-device?deviceId=${DEVICE_A.deviceId}`));
    expect(getRes.body.activeDevice.intervalMinutes).toBe(60);
    expect(getRes.body.activeDevice.activatedAt).toBeDefined();
  });

  test('Deactivate device sets stoppedAt', async () => {
    const deactRes = await setAuth(authReq()
      .post('/api/scheduler/active-device/deactivate'))
      .send({ deviceId: DEVICE_A.deviceId });

    expect(deactRes.status).toBe(200);
    expect(deactRes.body.deactivated).toBe(true);
    expect(deactRes.body.stoppedAt).toBeDefined();

    // Re-activate Device A for subsequent tests
    await setAuth(authReq()
      .post('/api/scheduler/active-device/activate'))
      .send({
        deviceId: DEVICE_A.deviceId,
        deviceName: 'Device A',
        devicePlatform: DEVICE_A.deviceType,
        schedulerPreferenceId: 'pref-123',
        intervalMinutes: 30,
      });
  });

  test('Send job email updates lastJobEmailAt on active device', async () => {
    // Re-activate Device A (clean state)
    await setAuth(authReq()
      .post('/api/scheduler/active-device/activate'))
      .send({
        deviceId: DEVICE_A.deviceId,
        deviceName: 'Device A',
        devicePlatform: DEVICE_A.deviceType,
        schedulerPreferenceId: 'pref-email-test',
        intervalMinutes: 15,
      });

    const emailRes = await setAuth(authReq()
      .post('/api/notifications/send-email'))
      .send({
        to: TEST_USER.email,
        preferenceId: 'pref-email-test',
        newJobs: [{ jobTitle: 'Test', company: 'Co', fingerprint: 'fp-lastjob-1' }],
        deviceId: DEVICE_A.deviceId,
      });

    expect(emailRes.body.success).toBe(true);

    const getRes = await setAuth(authReq()
      .get(`/api/scheduler/active-device?deviceId=${DEVICE_A.deviceId}`));
    expect(getRes.body.activeDevice.lastJobEmailAt).toBeDefined();
  });

  test('Send setup email updates lastSetupEmailAt on active device', async () => {
    const setupRes = await setAuth(authReq()
      .post('/api/notifications/send-setup-email'))
      .send({
        to: TEST_USER.email,
        preferenceId: 'pref-setup-test',
        preferenceDetails: 'Role: Test\nLocation: Remote',
      });

    expect(setupRes.body.success).toBe(true);
    expect(setupRes.body.setupEmailSent).toBe(true);

    const getRes = await setAuth(authReq()
      .get(`/api/scheduler/active-device?deviceId=${DEVICE_A.deviceId}`));
    expect(getRes.body.activeDevice.lastSetupEmailAt).toBeDefined();
  });
});

// ================================================================
// TWO-DEVICE ACCEPTANCE TESTS
// ================================================================

describe('Two-Device Acceptance Scenarios', () => {

  const TWO_DEVICE_PREF = 'two-device-pref';

  beforeEach(async () => {
    // Reset state: deactivate all devices, clean up locks and runs
    await prisma.schedulerDeviceState.updateMany({
      where: { userEmail: TEST_USER.email },
      data: { isActiveDevice: false },
    });
    await prisma.schedulerLock.deleteMany({ where: { userEmail: TEST_USER.email } });
    await prisma.schedulerRunHistory.deleteMany({ where: { userEmail: TEST_USER.email } });
    await prisma.emailedJobFingerprint.deleteMany({
      where: { userEmail: TEST_USER.email, preferenceId: TWO_DEVICE_PREF },
    });
  });

  test('1. Device A creates scheduler, Device B sees active status', async () => {
    // Device A activates
    const activateA = await setAuth(authReq()
      .post('/api/scheduler/active-device/activate'))
      .send({
        deviceId: DEVICE_A.deviceId,
        deviceName: 'Pixel 8',
        devicePlatform: DEVICE_A.deviceType,
        schedulerPreferenceId: TWO_DEVICE_PREF,
        intervalMinutes: 60,
      });
    expect(activateA.body.success).toBe(true);

    // Device B checks active device — should see Device A
    const checkB = await setAuth(authReq()
      .get(`/api/scheduler/active-device?deviceId=${DEVICE_B.deviceId}`));

    expect(checkB.body.success).toBe(true);
    expect(checkB.body.activeDevice).not.toBeNull();
    expect(checkB.body.activeDevice.deviceId).toBe(DEVICE_A.deviceId);
    expect(checkB.body.activeDevice.deviceName).toBe('Pixel 8');
    expect(checkB.body.activeDevice.intervalMinutes).toBe(60);
    expect(checkB.body.isCurrentDeviceActive).toBe(false);
  });

  test('2. Device B cannot activate while Device A is active', async () => {
    // Device A activates first
    await setAuth(authReq()
      .post('/api/scheduler/active-device/activate'))
      .send({
        deviceId: DEVICE_A.deviceId,
        deviceName: 'Pixel 8',
        devicePlatform: DEVICE_A.deviceType,
        schedulerPreferenceId: TWO_DEVICE_PREF,
      });

    // Device B activates — this should succeed (replaces A atomically)
    const activateB = await setAuth(authReq()
      .post('/api/scheduler/active-device/activate'))
      .send({
        deviceId: DEVICE_B.deviceId,
        deviceName: 'Desktop App',
        devicePlatform: DEVICE_B.deviceType,
        schedulerPreferenceId: TWO_DEVICE_PREF,
      });

    // Backend allows takeover by design (atomic deactivate-old + activate-new)
    expect(activateB.body.success).toBe(true);
    expect(activateB.body.activeDevice.deviceId).toBe(DEVICE_B.deviceId);

    // Device A is now inactive
    const checkA = await setAuth(authReq()
      .get(`/api/scheduler/active-device?deviceId=${DEVICE_A.deviceId}`));
    expect(checkA.body.isCurrentDeviceActive).toBe(false);
    expect(checkA.body.activeDevice.deviceId).toBe(DEVICE_B.deviceId);
  });

  test('3. Only the active device can send emails', async () => {
    // Device A active
    await setAuth(authReq()
      .post('/api/scheduler/active-device/activate'))
      .send({
        deviceId: DEVICE_A.deviceId,
        deviceName: 'Pixel 8',
        devicePlatform: DEVICE_A.deviceType,
        schedulerPreferenceId: TWO_DEVICE_PREF,
      });

    // Device A verify — canSend=true
    const verifyA = await setAuth(authReq()
      .post('/api/scheduler/active-device/verify'))
      .send({ deviceId: DEVICE_A.deviceId, schedulerPreferenceId: TWO_DEVICE_PREF });
    expect(verifyA.body.canSend).toBe(true);

    // Device B verify — canSend=false
    const verifyB = await setAuth(authReq()
      .post('/api/scheduler/active-device/verify'))
      .send({ deviceId: DEVICE_B.deviceId, schedulerPreferenceId: TWO_DEVICE_PREF });
    expect(verifyB.body.canSend).toBe(false);
    expect(verifyB.body.reason).toBe('device_not_active');

    // Device B acquire lock — should be denied
    const lockB = await setAuth(authReq()
      .post('/api/scheduler/acquire-lock'))
      .send({ preferenceSetId: TWO_DEVICE_PREF, ...DEVICE_B });
    expect(lockB.body.data.granted).toBe(false);
  });

  test('4. Deactivating device allows new scheduler creation', async () => {
    // Device A activates, then deactivates
    await setAuth(authReq()
      .post('/api/scheduler/active-device/activate'))
      .send({
        deviceId: DEVICE_A.deviceId,
        deviceName: 'Pixel 8',
        devicePlatform: DEVICE_A.deviceType,
        schedulerPreferenceId: TWO_DEVICE_PREF,
      });

    const deactivateRes = await setAuth(authReq()
      .post('/api/scheduler/active-device/deactivate'))
      .send({ deviceId: DEVICE_A.deviceId });

    expect(deactivateRes.body.deactivated).toBe(true);
    expect(deactivateRes.body.stoppedAt).toBeDefined();

    // Device B can now activate afresh
    const activateB = await setAuth(authReq()
      .post('/api/scheduler/active-device/activate'))
      .send({
        deviceId: DEVICE_B.deviceId,
        deviceName: 'Desktop App',
        devicePlatform: DEVICE_B.deviceType,
        schedulerPreferenceId: TWO_DEVICE_PREF,
        intervalMinutes: 120,
      });

    expect(activateB.body.success).toBe(true);
    expect(activateB.body.activeDevice.deviceId).toBe(DEVICE_B.deviceId);
    expect(activateB.body.activeDevice.intervalMinutes).toBe(120);
  });

  test('5. Backend lock prevents duplicates during race condition', async () => {
    const RACE_PREF = 'race-condition-pref';

    // Device A acquires lock
    const lockA = await setAuth(authReq()
      .post('/api/scheduler/acquire-lock'))
      .send({ preferenceSetId: RACE_PREF, ...DEVICE_A });
    expect(lockA.body.data.granted).toBe(true);

    // Device B tries — denied (lock held by A)
    const lockB = await setAuth(authReq()
      .post('/api/scheduler/acquire-lock'))
      .send({ preferenceSetId: RACE_PREF, ...DEVICE_B });
    expect(lockB.body.data.granted).toBe(false);
    expect(lockB.body.data.heldBy).toBe(DEVICE_A.deviceId);

    // Device A releases lock
    await setAuth(authReq()
      .post('/api/scheduler/release-lock'))
      .send({ preferenceSetId: RACE_PREF, deviceId: DEVICE_A.deviceId });

    // Device B can now acquire
    const lockB2 = await setAuth(authReq()
      .post('/api/scheduler/acquire-lock'))
      .send({ preferenceSetId: RACE_PREF, ...DEVICE_B });
    expect(lockB2.body.data.granted).toBe(true);

    // Cleanup
    await setAuth(authReq()
      .post('/api/scheduler/release-lock'))
      .send({ preferenceSetId: RACE_PREF, deviceId: DEVICE_B.deviceId });
  });
});
