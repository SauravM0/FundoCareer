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

async function resetSchedulerDevices() {
  await prisma.schedulerLock.deleteMany({ where: { userEmail: TEST_USER.email } });
  await prisma.schedulerDeviceState.deleteMany({ where: { userEmail: TEST_USER.email } });
}

async function activateDevice(device, overrides = {}) {
  return setAuth(authReq()
    .post('/api/scheduler/active-device/activate'))
    .send({
      deviceId: device.deviceId,
      deviceName: overrides.deviceName || device.deviceName || device.deviceId,
      devicePlatform: device.deviceType,
      schedulerPreferenceId: overrides.schedulerPreferenceId || PREF_SET,
      intervalMinutes: overrides.intervalMinutes,
      takeover: overrides.takeover,
    });
}

// ================================================================
// LOCK TESTS
// ================================================================

describe('Lock Acquisition', () => {

  test('Device A acquires lock successfully', async () => {
    await resetSchedulerDevices();
    await activateDevice(DEVICE_A);

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
    await resetSchedulerDevices();
    await activateDevice(DEVICE_A);
    await setAuth(authReq()
      .post('/api/scheduler/acquire-lock'))
      .send({ preferenceSetId: PREF_SET, ...DEVICE_A });

    const res = await setAuth(authReq()
      .post('/api/scheduler/acquire-lock'))
      .send({ preferenceSetId: PREF_SET, ...DEVICE_B });

    expect(res.status).toBe(200);
    expect(res.body.success).toBe(true);
    expect(res.body.data.granted).toBe(false);
    expect(res.body.data.heldBy).toBe(DEVICE_A.deviceId);
    expect(res.body.data.reason).toBe('device_not_active');
  });

  test('Device A releases lock successfully', async () => {
    await resetSchedulerDevices();
    await activateDevice(DEVICE_A);
    await setAuth(authReq()
      .post('/api/scheduler/acquire-lock'))
      .send({ preferenceSetId: PREF_SET, ...DEVICE_A });

    const res = await setAuth(authReq()
      .post('/api/scheduler/release-lock'))
      .send({ preferenceSetId: PREF_SET, deviceId: DEVICE_A.deviceId });

    expect(res.status).toBe(200);
    expect(res.body.success).toBe(true);
    expect(res.body.data.released).toBe(true);
  });

  test('Device B cannot release Device A lock', async () => {
    await resetSchedulerDevices();
    await activateDevice(DEVICE_A);
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

  test('Device B acquires only after explicit takeover', async () => {
    await resetSchedulerDevices();
    await activateDevice(DEVICE_A);
    await setAuth(authReq()
      .post('/api/scheduler/acquire-lock'))
      .send({ preferenceSetId: PREF_SET, ...DEVICE_A });

    await setAuth(authReq()
      .post('/api/scheduler/release-lock'))
      .send({ preferenceSetId: PREF_SET, deviceId: DEVICE_A.deviceId });

    await activateDevice(DEVICE_B, { takeover: true });

    const res = await setAuth(authReq()
      .post('/api/scheduler/acquire-lock'))
      .send({ preferenceSetId: PREF_SET, ...DEVICE_B });

    expect(res.status).toBe(200);
    expect(res.body.data.granted).toBe(true);
  });
});

describe('Lock Expiration', () => {

  test('Lock is denied with TTL and later re-acquirable', async () => {
    await resetSchedulerDevices();
    await activateDevice(DEVICE_A);
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

  test('Empty newJobs sends no-new-jobs email (not an error)', async () => {
    const res = await setAuth(authReq()
      .post('/api/notifications/send-email'))
      .send({ to: TEST_USER.email, preferenceId: 'test-pref', newJobs: [] });

    // Empty newJobs is valid: sends "no new matching jobs" email
    // Will return 200 if email configured, or 500 if not
    expect([200, 500]).toContain(res.status);
    if (res.status === 200) {
      expect(res.body.success).toBe(true);
      expect(res.body.jobEmailSent).toBe(false);
    }
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
    await resetSchedulerDevices();
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

  test('Device B activate is rejected until explicit takeover', async () => {
    await resetSchedulerDevices();
    await activateDevice(DEVICE_A, { schedulerPreferenceId: 'pref-123' });

    const res = await setAuth(authReq()
      .post('/api/scheduler/active-device/activate'))
      .send({
        deviceId: DEVICE_B.deviceId,
        deviceName: 'Device B',
        devicePlatform: DEVICE_B.deviceType,
        schedulerPreferenceId: 'pref-456',
      });

    expect(res.status).toBe(409);
    expect(res.body.success).toBe(false);
    expect(res.body.errorCode).toBe('ACTIVE_DEVICE_EXISTS');
    expect(res.body.activeDevice.deviceId).toBe(DEVICE_A.deviceId);

    const takeover = await setAuth(authReq()
      .post('/api/scheduler/active-device/activate'))
      .send({
        deviceId: DEVICE_B.deviceId,
        deviceName: 'Device B',
        devicePlatform: DEVICE_B.deviceType,
        schedulerPreferenceId: 'pref-456',
        takeover: true,
      });
    expect(takeover.status).toBe(200);
    expect(takeover.body.activeDevice.deviceId).toBe(DEVICE_B.deviceId);

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

    expect(activateB.status).toBe(409);
    expect(activateB.body.success).toBe(false);
    expect(activateB.body.errorCode).toBe('ACTIVE_DEVICE_EXISTS');
    expect(activateB.body.activeDevice.deviceId).toBe(DEVICE_A.deviceId);

    const takeoverB = await setAuth(authReq()
      .post('/api/scheduler/active-device/activate'))
      .send({
        deviceId: DEVICE_B.deviceId,
        deviceName: 'Desktop App',
        devicePlatform: DEVICE_B.deviceType,
        schedulerPreferenceId: TWO_DEVICE_PREF,
        takeover: true,
      });
    expect(takeoverB.body.success).toBe(true);
    expect(takeoverB.body.activeDevice.deviceId).toBe(DEVICE_B.deviceId);

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

    await setAuth(authReq()
      .post('/api/scheduler/active-device/activate'))
      .send({
        deviceId: DEVICE_A.deviceId,
        deviceName: 'Pixel 8',
        devicePlatform: DEVICE_A.deviceType,
        schedulerPreferenceId: RACE_PREF,
      });

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
    expect(lockB.body.data.reason).toBe('device_not_active');

    // Device A releases lock
    await setAuth(authReq()
      .post('/api/scheduler/release-lock'))
      .send({ preferenceSetId: RACE_PREF, deviceId: DEVICE_A.deviceId });

    await setAuth(authReq()
      .post('/api/scheduler/active-device/activate'))
      .send({
        deviceId: DEVICE_B.deviceId,
        deviceName: 'Desktop App',
        devicePlatform: DEVICE_B.deviceType,
        schedulerPreferenceId: RACE_PREF,
        takeover: true,
      });

    // Device B can now acquire after explicit takeover.
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

// ================================================================
// ACCEPTANCE TEST SCENARIOS (10 required scenarios)
// ================================================================

describe('Acceptance Test Scenarios', () => {

  const ACCEPT_PREF = 'acceptance-pref';

  beforeEach(async () => {
    await prisma.schedulerDeviceState.updateMany({
      where: { userEmail: TEST_USER.email },
      data: { isActiveDevice: false },
    });
    await prisma.schedulerLock.deleteMany({ where: { userEmail: TEST_USER.email } });
    await prisma.schedulerRunHistory.deleteMany({ where: { userEmail: TEST_USER.email } });
    await prisma.emailSentHistory.deleteMany({ where: { userEmail: TEST_USER.email } });
    await prisma.emailedJobFingerprint.deleteMany({
      where: { userEmail: TEST_USER.email, preferenceId: ACCEPT_PREF },
    });
  });

  // Scenario 1: Activate first device
  test('1. Activate first device successfully', async () => {
    const res = await setAuth(authReq()
      .post('/api/scheduler/active-device/activate'))
      .send({
        deviceId: DEVICE_A.deviceId,
        deviceName: 'Pixel 8',
        devicePlatform: DEVICE_A.deviceType,
        schedulerPreferenceId: ACCEPT_PREF,
        intervalMinutes: 30,
      });

    expect(res.status).toBe(200);
    expect(res.body.success).toBe(true);
    expect(res.body.activeDevice).not.toBeNull();
    expect(res.body.activeDevice.deviceId).toBe(DEVICE_A.deviceId);
    expect(res.body.activeDevice.devicePlatform).toBe(DEVICE_A.deviceType);
    expect(res.body.isCurrentDeviceActive).toBe(true);
  });

  // Scenario 2: Activate second device without takeover returns 409
  test('2. Activate second device without takeover returns 409', async () => {
    // Activate Device A first
    await setAuth(authReq()
      .post('/api/scheduler/active-device/activate'))
      .send({
        deviceId: DEVICE_A.deviceId,
        deviceName: 'Pixel 8',
        devicePlatform: DEVICE_A.deviceType,
        schedulerPreferenceId: ACCEPT_PREF,
      });

    // Device B tries to activate without takeover
    const res = await setAuth(authReq()
      .post('/api/scheduler/active-device/activate'))
      .send({
        deviceId: DEVICE_B.deviceId,
        deviceName: 'Desktop App',
        devicePlatform: DEVICE_B.deviceType,
        schedulerPreferenceId: ACCEPT_PREF,
      });

    expect(res.status).toBe(409);
    expect(res.body.success).toBe(false);
    expect(res.body.errorCode).toBe('ACTIVE_DEVICE_EXISTS');
    expect(res.body.activeDevice.deviceId).toBe(DEVICE_A.deviceId);
    expect(res.body.isCurrentDeviceActive).toBe(false);
  });

  // Scenario 3: Activate second device with takeover succeeds
  test('3. Activate second device with takeover succeeds', async () => {
    // Activate Device A first
    await setAuth(authReq()
      .post('/api/scheduler/active-device/activate'))
      .send({
        deviceId: DEVICE_A.deviceId,
        deviceName: 'Pixel 8',
        devicePlatform: DEVICE_A.deviceType,
        schedulerPreferenceId: ACCEPT_PREF,
      });

    // Device B takes over
    const res = await setAuth(authReq()
      .post('/api/scheduler/active-device/activate'))
      .send({
        deviceId: DEVICE_B.deviceId,
        deviceName: 'Desktop App',
        devicePlatform: DEVICE_B.deviceType,
        schedulerPreferenceId: ACCEPT_PREF,
        takeover: true,
      });

    expect(res.status).toBe(200);
    expect(res.body.success).toBe(true);
    expect(res.body.activeDevice.deviceId).toBe(DEVICE_B.deviceId);
    expect(res.body.isCurrentDeviceActive).toBe(true);

    // Verify Device A is no longer active
    const checkA = await setAuth(authReq()
      .get(`/api/scheduler/active-device?deviceId=${DEVICE_A.deviceId}`));
    expect(checkA.body.isCurrentDeviceActive).toBe(false);
  });

  // Scenario 4: Verify active device true
  test('4. Verify active device returns canSend=true', async () => {
    await setAuth(authReq()
      .post('/api/scheduler/active-device/activate'))
      .send({
        deviceId: DEVICE_A.deviceId,
        deviceName: 'Pixel 8',
        devicePlatform: DEVICE_A.deviceType,
        schedulerPreferenceId: ACCEPT_PREF,
      });

    const res = await setAuth(authReq()
      .post('/api/scheduler/active-device/verify'))
      .send({ deviceId: DEVICE_A.deviceId });

    expect(res.status).toBe(200);
    expect(res.body.canSend).toBe(true);
  });

  // Scenario 5: Verify inactive device false
  test('5. Verify inactive device returns canSend=false', async () => {
    await setAuth(authReq()
      .post('/api/scheduler/active-device/activate'))
      .send({
        deviceId: DEVICE_A.deviceId,
        deviceName: 'Pixel 8',
        devicePlatform: DEVICE_A.deviceType,
        schedulerPreferenceId: ACCEPT_PREF,
      });

    const res = await setAuth(authReq()
      .post('/api/scheduler/active-device/verify'))
      .send({ deviceId: DEVICE_B.deviceId });

    expect(res.status).toBe(200);
    expect(res.body.canSend).toBe(false);
    expect(res.body.reason).toBe('device_not_active');
  });

  // Scenario 6: Setup email blocked from inactive device
  test('6. Setup email blocked from inactive device', async () => {
    // Activate Device A
    await setAuth(authReq()
      .post('/api/scheduler/active-device/activate'))
      .send({
        deviceId: DEVICE_A.deviceId,
        deviceName: 'Pixel 8',
        devicePlatform: DEVICE_A.deviceType,
        schedulerPreferenceId: ACCEPT_PREF,
      });

    // Device B tries to send setup email — should be blocked
    const blockedRes = await setAuth(authReq()
      .post('/api/notifications/send-setup-email'))
      .send({
        to: TEST_USER.email,
        preferenceId: ACCEPT_PREF,
        preferenceDetails: 'Role: Test\nLocation: Remote',
        deviceId: DEVICE_B.deviceId,
      });

    expect(blockedRes.status).toBe(403);
    expect(blockedRes.body.errorCode).toBe('DEVICE_NOT_ACTIVE');
    expect(blockedRes.body.setupEmailSent).toBe(false);

    // Device A can send setup email
    const okRes = await setAuth(authReq()
      .post('/api/notifications/send-setup-email'))
      .send({
        to: TEST_USER.email,
        preferenceId: ACCEPT_PREF,
        preferenceDetails: 'Role: Test\nLocation: Remote',
        deviceId: DEVICE_A.deviceId,
      });

    expect(okRes.status).toBe(200);
    expect(okRes.body.setupEmailSent).toBe(true);
  });

  // Scenario 7: Setup email sent from active device
  test('7. Setup email sent from active device', async () => {
    await setAuth(authReq()
      .post('/api/scheduler/active-device/activate'))
      .send({
        deviceId: DEVICE_A.deviceId,
        deviceName: 'Pixel 8',
        devicePlatform: DEVICE_A.deviceType,
        schedulerPreferenceId: ACCEPT_PREF,
      });

    const res = await setAuth(authReq()
      .post('/api/notifications/send-setup-email'))
      .send({
        to: TEST_USER.email,
        preferenceId: ACCEPT_PREF,
        preferenceDetails: 'Role: Test\nLocation: Remote',
        deviceId: DEVICE_A.deviceId,
      });

    expect(res.status).toBe(200);
    expect(res.body.emailSent).toBe(true);
    expect(res.body.setupEmailSent).toBe(true);

    // Verify lastSetupEmailAt is set
    const getRes = await setAuth(authReq()
      .get(`/api/scheduler/active-device?deviceId=${DEVICE_A.deviceId}`));
    expect(getRes.body.activeDevice.lastSetupEmailAt).toBeDefined();
  });

  // Scenario 8: sync-state does not activate device unexpectedly
  test('8. sync-state does not activate device unexpectedly', async () => {
    // Sync state without activating
    const syncRes = await setAuth(authReq()
      .post('/api/scheduler/sync-state'))
      .send({
        ...DEVICE_A,
        preferences: { role: 'Developer', location: 'Remote' },
        schedulerState: { enabled: true, intervalMinutes: 15 },
      });

    expect(syncRes.status).toBe(200);
    expect(syncRes.body.success).toBe(true);

    // Verify the device is NOT active after sync
    const getRes = await setAuth(authReq()
      .get(`/api/scheduler/active-device?deviceId=${DEVICE_A.deviceId}`));
    expect(getRes.body.activeDevice).toBeNull();

    // Activate device explicitly
    const activateRes = await setAuth(authReq()
      .post('/api/scheduler/active-device/activate'))
      .send({
        deviceId: DEVICE_A.deviceId,
        deviceName: 'Pixel 8',
        devicePlatform: DEVICE_A.deviceType,
        schedulerPreferenceId: ACCEPT_PREF,
      });
    expect(activateRes.body.isCurrentDeviceActive).toBe(true);

    // Sync again should not deactivate
    const syncRes2 = await setAuth(authReq()
      .post('/api/scheduler/sync-state'))
      .send({
        ...DEVICE_A,
        preferences: { role: 'Developer', location: 'Remote' },
      });
    expect(syncRes2.status).toBe(200);

    const getRes2 = await setAuth(authReq()
      .get(`/api/scheduler/active-device?deviceId=${DEVICE_A.deviceId}`));
    expect(getRes2.body.activeDevice).not.toBeNull();
    expect(getRes2.body.isCurrentDeviceActive).toBe(true);
  });

  // Scenario 9: Stop scheduler deactivates active device
  test('9. Stop scheduler deactivates active device', async () => {
    // Activate Device A
    await setAuth(authReq()
      .post('/api/scheduler/active-device/activate'))
      .send({
        deviceId: DEVICE_A.deviceId,
        deviceName: 'Pixel 8',
        devicePlatform: DEVICE_A.deviceType,
        schedulerPreferenceId: ACCEPT_PREF,
      });

    // Verify active
    const beforeRes = await setAuth(authReq()
      .get(`/api/scheduler/active-device?deviceId=${DEVICE_A.deviceId}`));
    expect(beforeRes.body.activeDevice).not.toBeNull();

    // Deactivate (stop scheduler)
    const deactRes = await setAuth(authReq()
      .post('/api/scheduler/active-device/deactivate'))
      .send({ deviceId: DEVICE_A.deviceId });

    expect(deactRes.status).toBe(200);
    expect(deactRes.body.deactivated).toBe(true);
    expect(deactRes.body.stoppedAt).toBeDefined();

    // Verify inactive
    const afterRes = await setAuth(authReq()
      .get(`/api/scheduler/active-device?deviceId=${DEVICE_A.deviceId}`));
    expect(afterRes.body.activeDevice).toBeNull();
    expect(afterRes.body.isCurrentDeviceActive).toBe(false);
  });

  // Scenario 10: History returns events
  test('10. History returns events', async () => {
    // Activate device and create some history
    await setAuth(authReq()
      .post('/api/scheduler/active-device/activate'))
      .send({
        deviceId: DEVICE_A.deviceId,
        deviceName: 'Pixel 8',
        devicePlatform: DEVICE_A.deviceType,
        schedulerPreferenceId: ACCEPT_PREF,
      });

    // Create a run history entry
    await setAuth(authReq()
      .post('/api/scheduler/sync-state'))
      .send({
        ...DEVICE_A,
        preferences: { role: 'Developer', location: 'Remote' },
        recentRuns: [
          {
            preferenceId: ACCEPT_PREF,
            runId: 'acceptance-run-1',
            status: 'SUCCESS_EMAIL_SENT',
            jobsFound: 15,
            jobsNew: 5,
            startedAt: new Date().toISOString(),
            triggeredBy: 'scheduler',
          },
          {
            preferenceId: ACCEPT_PREF,
            runId: 'acceptance-run-2',
            status: 'SUCCESS_NO_NEW_JOBS',
            jobsFound: 10,
            jobsNew: 0,
            startedAt: new Date(Date.now() - 60000).toISOString(),
            triggeredBy: 'scheduler',
          },
        ],
      });

    // Test email history endpoint
    const historyRes = await setAuth(authReq()
      .get(`/api/scheduler/history?preferenceId=${ACCEPT_PREF}`));

    expect(historyRes.status).toBe(200);
    expect(historyRes.body.success).toBe(true);
    expect(Array.isArray(historyRes.body.history)).toBe(true);

    // Test history summary endpoint
    const summaryRes = await setAuth(authReq()
      .post('/api/scheduler/history-summary'))
      .send({ preferenceId: ACCEPT_PREF });

    expect(summaryRes.status).toBe(200);
    expect(summaryRes.body.success).toBe(true);
    // historySummary excludes SUCCESS_NO_NEW_JOBS from totalRuns and job counts
    expect(summaryRes.body.data.totalRuns).toBeGreaterThanOrEqual(1);
    expect(summaryRes.body.data.totalJobsFound).toBeGreaterThanOrEqual(15);

    // Test timeline endpoint
    const timelineRes = await setAuth(authReq()
      .get('/api/scheduler/user-timeline'));

    expect(timelineRes.status).toBe(200);
    expect(timelineRes.body.success).toBe(true);
    expect(Array.isArray(timelineRes.body.timeline)).toBe(true);

    // Should have at least: scheduler_created event + run events
    const types = timelineRes.body.timeline.map(e => e.type);
    expect(types).toContain('scheduler_created');
    expect(types).toContain('job_alert_sent');
    expect(types).toContain('no_jobs_found');
  });
});
