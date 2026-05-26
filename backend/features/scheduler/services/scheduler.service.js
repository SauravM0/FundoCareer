import prisma from '../../../config/database.config.js';
import { normalizeFingerprint, normalizeFingerprints } from '../../../shared/services/fingerprint.service.js';
import { maskDeviceId } from '../../../shared/utils/logMasker.js';

const LOCK_TTL_MS = 10 * 60 * 1000;

export function now() { return new Date(); }

export function future(ms) { return new Date(Date.now() + ms); }

export function getUserEmail(req) {
  return req.user?.email || null;
}

export function safeJsonParse(str) {
  try {
    return JSON.parse(str);
  } catch {
    return null;
  }
}

export function activeDevicePayload(device) {
  if (!device) return null;
  const preferences = device.preferencesJson ? safeJsonParse(device.preferencesJson) : null;
  const schedulerState = device.schedulerStateJson ? safeJsonParse(device.schedulerStateJson) : null;
  const nextApproxRunAt = schedulerState?.nextScheduledRunAt || schedulerState?.nextApproxRunAt || preferences?.nextScheduledRunAt || null;
  const lastRunAt = schedulerState?.lastRunAt || schedulerState?.lastSuccessfulRunAt || null;
  const intervalMinutes = device.intervalMinutes || preferences?.intervalMinutes || schedulerState?.intervalMinutes || null;
  return {
    deviceId: device.deviceId,
    deviceName: device.deviceName,
    devicePlatform: device.deviceType,
    activeDeviceLastSeenAt: device.activeDeviceLastSeenAt?.toISOString() || null,
    activatedAt: device.activatedAt?.toISOString() || null,
    stoppedAt: device.stoppedAt?.toISOString() || null,
    lastSetupEmailAt: device.lastSetupEmailAt?.toISOString() || null,
    lastJobEmailAt: device.lastJobEmailAt?.toISOString() || null,
    intervalMinutes,
    schedulerPreferenceId: schedulerState?.preferenceId || preferences?.preferenceId || null,
    preferences,
    schedulerState,
    lastRunAt,
    nextApproxRunAt,
  };
}

export function mapRunToTimelineEvent(run) {
  const s = run.status;
  let type, title, description, status, metadata;

  switch (s) {
    case 'SUCCESS_EMAIL_SENT':
      type = 'job_alert_sent';
      title = 'Jobs found and emailed';
      description = `${run.jobsNew} new job${run.jobsNew !== 1 ? 's' : ''} sent via email`;
      status = 'success';
      metadata = { jobsFound: run.jobsFound, jobsSent: run.jobsNew, pdfAttached: true, errorMessage: null };
      break;
    case 'SUCCESS_NO_NEW_JOBS':
      type = 'no_jobs_found';
      title = 'No new jobs found';
      description = `Checked ${run.jobsFound} job${run.jobsFound !== 1 ? 's' : ''}, all already seen or no matches`;
      status = 'success';
      metadata = { jobsFound: run.jobsFound, jobsSent: 0, pdfAttached: false, errorMessage: null };
      break;
    case 'SKIPPED_NOT_ACTIVE_DEVICE':
      type = 'worker_skipped_inactive';
      title = 'Scheduler skipped — device not active';
      description = run.errorMessage || 'This device is no longer the active scheduler device';
      status = 'skipped';
      metadata = { jobsFound: 0, jobsSent: 0, pdfAttached: false, errorMessage: run.errorMessage };
      break;
    case 'SKIPPED_LOCK_HELD_BY_OTHER_DEVICE':
      type = 'worker_skipped_lock_held';
      title = 'Scheduler skipped — lock held elsewhere';
      description = run.errorMessage || 'Another device held the execution lock';
      status = 'skipped';
      metadata = { jobsFound: 0, jobsSent: 0, pdfAttached: false, errorMessage: run.errorMessage };
      break;
    case 'NO_ACTIVE_PREFERENCE':
      type = 'no_preferences';
      title = 'No active job preferences';
      description = run.errorMessage || 'No active job search preferences found';
      status = 'skipped';
      metadata = { jobsFound: 0, jobsSent: 0, pdfAttached: false, errorMessage: run.errorMessage };
      break;
    case 'FAILED_EMAIL_BACKEND':
      type = 'email_failed';
      title = 'Email delivery failed';
      description = run.errorMessage || 'Failed to send job alert email';
      status = 'failed';
      metadata = { jobsFound: run.jobsFound, jobsSent: 0, pdfAttached: false, errorMessage: run.errorMessage };
      break;
    case 'FAILED_NETWORK':
      type = 'backend_sync_failed';
      title = 'Backend sync failed';
      description = run.errorMessage || 'Network error during scheduler run';
      status = 'failed';
      metadata = { jobsFound: 0, jobsSent: 0, pdfAttached: false, errorMessage: run.errorMessage };
      break;
    case 'FAILED_JOB_SOURCE':
      type = 'worker_failed_source';
      title = 'Job search failed';
      description = run.errorMessage || 'Could not fetch jobs from LinkedIn';
      status = 'failed';
      metadata = { jobsFound: 0, jobsSent: 0, pdfAttached: false, errorMessage: run.errorMessage };
      break;
    case 'FAILED_AUTH':
      type = 'worker_failed_auth';
      title = 'Authentication failed';
      description = run.errorMessage || 'Session expired, could not re-authenticate';
      status = 'failed';
      metadata = { jobsFound: 0, jobsSent: 0, pdfAttached: false, errorMessage: run.errorMessage };
      break;
    default:
      if (s && s.startsWith('FAILED')) {
        type = 'worker_failed';
        title = 'Scheduler run failed';
        description = run.errorMessage || s;
        status = 'failed';
        metadata = { jobsFound: run.jobsFound, jobsSent: 0, pdfAttached: false, errorMessage: run.errorMessage };
      } else {
        return null;
      }
  }

  return {
    type,
    timestamp: run.startedAt.toISOString(),
    title,
    description,
    status,
    runId: run.runId || null,
    metadata,
  };
}

// ================================================================
// LOCK OPERATIONS
// ================================================================

export async function acquireLockService(userEmail, { preferenceSetId, deviceId, deviceType, appVersion }) {
  const activeDevice = await prisma.schedulerDeviceState.findFirst({
    where: { userEmail, isActiveDevice: true },
  });
  if (!activeDevice) {
    return { granted: false, reason: 'no_active_device' };
  }
  if (activeDevice.deviceId !== deviceId) {
    return {
      granted: false,
      reason: 'device_not_active',
      heldBy: activeDevice.deviceId,
      heldByDeviceType: activeDevice.deviceType,
    };
  }

  const lockKey = { userEmail, preferenceSetId };
  const existingLock = await prisma.schedulerLock.findUnique({ where: { userEmail_preferenceSetId: lockKey } });

  if (existingLock) {
    const isExpired = existingLock.expiresAt < now();
    if (!isExpired) {
      return {
        granted: false,
        reason: 'lock_held_by_other_device',
        heldBy: existingLock.deviceId,
        heldByDeviceType: existingLock.deviceType,
        acquiredAt: existingLock.acquiredAt.toISOString(),
        expiresAt: existingLock.expiresAt.toISOString(),
      };
    }
  }

  const expiresAt = future(LOCK_TTL_MS);
  const lock = await prisma.schedulerLock.upsert({
    where: { userEmail_preferenceSetId: lockKey },
    create: {
      userEmail, preferenceSetId, deviceId, deviceType,
      appVersion: appVersion || null, expiresAt, lastSeenAt: now(),
    },
    update: {
      deviceId, deviceType, appVersion: appVersion || null,
      acquiredAt: now(), expiresAt, lastSeenAt: now(),
    },
  });

  return {
    granted: true,
    lockId: lock.id,
    acquiredAt: lock.acquiredAt.toISOString(),
    expiresAt: lock.expiresAt.toISOString(),
  };
}

export async function releaseLockService(userEmail, { preferenceSetId, deviceId }) {
  const lock = await prisma.schedulerLock.findUnique({
    where: { userEmail_preferenceSetId: { userEmail, preferenceSetId } },
  });

  if (!lock) return { released: false, reason: 'no_lock' };
  if (lock.deviceId !== deviceId) return { released: false, reason: 'not_lock_holder', heldBy: lock.deviceId };

  await prisma.schedulerLock.delete({ where: { id: lock.id } });
  return { released: true };
}

// ================================================================
// FINGERPRINT OPERATIONS
// ================================================================

export async function checkEmailedJobsService(userEmail, fingerprints) {
  const normalizedIncoming = normalizeFingerprints(fingerprints);
  const lookupSet = new Set([...fingerprints, ...normalizedIncoming].filter(f => f.length > 0));

  const existing = await prisma.emailedJobFingerprint.findMany({
    where: { userEmail, fingerprint: { in: Array.from(lookupSet) } },
    select: { fingerprint: true },
  });

  const storedSet = new Set(existing.map(e => e.fingerprint));
  const alreadyEmailed = new Set();
  const newFingerprints = [];

  for (const f of fingerprints) {
    if (storedSet.has(f)) {
      alreadyEmailed.add(f);
    } else {
      const nf = normalizeFingerprint(f);
      if (nf.length > 0 && storedSet.has(nf)) {
        alreadyEmailed.add(f);
      } else {
        newFingerprints.push(f);
      }
    }
  }

  return { alreadyEmailed: Array.from(alreadyEmailed), new: newFingerprints };
}

export async function markEmailedJobsService(userEmail, { fingerprints, preferenceId, emailRecordId }) {
  const normalizedToStore = fingerprints.map(f => normalizeFingerprint(f)).filter(h => h.length > 0);

  const existingHashes = new Set(
    (await prisma.emailedJobFingerprint.findMany({
      where: { userEmail, fingerprint: { in: [...fingerprints, ...normalizedToStore] } },
      select: { fingerprint: true },
    })).map(e => e.fingerprint)
  );

  const toInsert = normalizedToStore
    .filter(f => !existingHashes.has(f))
    .map(fingerprint => ({
      userEmail, preferenceId, fingerprint,
      emailedAt: now(), emailRecordId: emailRecordId || null,
    }));

  let marked = 0;
  if (toInsert.length > 0) {
    await prisma.emailedJobFingerprint.createMany({ data: toInsert, skipDuplicates: true });
    marked = toInsert.length;
  }

  return { marked, total: fingerprints.length, skippedDuplicates: fingerprints.length - marked };
}

// ================================================================
// DEVICE STATE OPERATIONS
// ================================================================

export async function syncStateService(userEmail, { deviceId, deviceType, appVersion, preferences, schedulerState, recentRuns }) {
  const nowTime = now();
  await prisma.schedulerDeviceState.upsert({
    where: { userEmail_deviceId: { userEmail, deviceId } },
    create: {
      userEmail, deviceId, deviceType,
      appVersion: appVersion || null, lastSyncAt: nowTime, lastSeenAt: nowTime,
      preferencesJson: preferences ? JSON.stringify(preferences) : null,
      schedulerStateJson: schedulerState ? JSON.stringify(schedulerState) : null,
    },
    update: {
      deviceType, appVersion: appVersion || null,
      lastSyncAt: nowTime, lastSeenAt: nowTime,
      preferencesJson: preferences ? JSON.stringify(preferences) : undefined,
      schedulerStateJson: schedulerState ? JSON.stringify(schedulerState) : undefined,
    },
  });

  if (Array.isArray(recentRuns) && recentRuns.length > 0) {
    const runData = recentRuns.map(run => ({
      userEmail, deviceId,
      preferenceId: run.preferenceId || 'unknown',
      runId: run.runId || null,
      startedAt: new Date(run.startedAt || Date.now()),
      completedAt: run.completedAt ? new Date(run.completedAt) : null,
      status: run.status || 'unknown',
      jobsFound: run.jobsFound || 0,
      jobsNew: run.jobsNew || 0,
      errorMessage: run.errorMessage || null,
      triggeredBy: run.triggeredBy || 'manual',
    }));
    await prisma.schedulerRunHistory.createMany({ data: runData, skipDuplicates: true });
  }

  return { syncedAt: nowTime.toISOString() };
}

export async function getStateService(userEmail, deviceId) {
  const deviceState = await prisma.schedulerDeviceState.findUnique({
    where: { userEmail_deviceId: { userEmail, deviceId } },
  });

  const activeLocks = await prisma.schedulerLock.findMany({
    where: { userEmail, expiresAt: { gt: now() } },
  });

  const recentRuns = await prisma.schedulerRunHistory.findMany({
    where: { userEmail, deviceId },
    orderBy: { startedAt: 'desc' },
    take: 20,
  });

  return {
    deviceState: deviceState ? {
      deviceId: deviceState.deviceId,
      deviceType: deviceState.deviceType,
      appVersion: deviceState.appVersion,
      lastSyncAt: deviceState.lastSyncAt.toISOString(),
      lastSeenAt: deviceState.lastSeenAt.toISOString(),
      preferences: deviceState.preferencesJson ? safeJsonParse(deviceState.preferencesJson) : null,
      schedulerState: deviceState.schedulerStateJson ? safeJsonParse(deviceState.schedulerStateJson) : null,
    } : null,
    activeLocks: activeLocks.map(l => ({
      preferenceSetId: l.preferenceSetId,
      heldBy: l.deviceId,
      deviceType: l.deviceType,
      acquiredAt: l.acquiredAt.toISOString(),
      expiresAt: l.expiresAt.toISOString(),
    })),
    recentRuns: recentRuns.map(r => ({
      runId: r.runId,
      preferenceId: r.preferenceId,
      status: r.status,
      jobsFound: r.jobsFound,
      jobsNew: r.jobsNew,
      startedAt: r.startedAt.toISOString(),
      completedAt: r.completedAt?.toISOString() || null,
    })),
  };
}

export async function getEmailHistoryService(userEmail, preferenceId) {
  const whereClause = { userEmail };
  if (preferenceId) {
    whereClause.schedulerPreferenceId = preferenceId;
  }

  const rows = await prisma.emailSentHistory.findMany({
    where: whereClause,
    orderBy: { emailSentAt: 'desc' },
    take: 50,
  });

  return rows.map(r => ({
    emailSentAt: r.emailSentAt.toISOString(),
    jobsSentCount: r.jobsSentCount,
    pdfAttached: r.pdfAttached,
    title: r.searchTitle || 'Job alert delivered successfully',
  }));
}

export async function historySummaryService(userEmail, preferenceId) {
  const whereClause = { userEmail };
  if (preferenceId) {
    whereClause.preferenceId = preferenceId;
  }

  const SUCCESS_NO_NEW_JOBS = 'SUCCESS_NO_NEW_JOBS';

  const totalRuns = await prisma.schedulerRunHistory.count({
    where: { ...whereClause, status: { not: SUCCESS_NO_NEW_JOBS } },
  });
  const lastRun = await prisma.schedulerRunHistory.findFirst({
    where: whereClause,
    orderBy: { startedAt: 'desc' },
  });

  const aggregation = await prisma.schedulerRunHistory.aggregate({
    where: whereClause,
    _sum: { jobsFound: true, jobsNew: true },
  });

  const totalEmailsSent = await prisma.emailedJobFingerprint.count({
    where: { userEmail, ...(preferenceId ? { preferenceId } : {}) },
  });
  const lastEmail = await prisma.emailedJobFingerprint.findFirst({
    where: { userEmail, ...(preferenceId ? { preferenceId } : {}) },
    orderBy: { emailedAt: 'desc' },
  });

  return {
    totalRuns,
    lastRunAt: lastRun?.startedAt?.toISOString() || null,
    lastRunStatus: lastRun?.status || null,
    totalJobsFound: aggregation._sum?.jobsFound || 0,
    totalJobsNew: aggregation._sum?.jobsNew || 0,
    totalEmailsSent,
    lastEmailSentAt: lastEmail?.emailedAt?.toISOString() || null,
  };
}

// ================================================================
// ACTIVE DEVICE OPERATIONS
// ================================================================

export async function getActiveDeviceService(userEmail, deviceId) {
  const activeDevice = await prisma.schedulerDeviceState.findFirst({
    where: { userEmail, isActiveDevice: true },
  });

  if (!activeDevice) {
    return { activeDevice: null, isCurrentDeviceActive: false };
  }

  return {
    activeDevice: activeDevicePayload(activeDevice),
    isCurrentDeviceActive: deviceId ? activeDevice.deviceId === deviceId : false,
  };
}

export async function activateDeviceService(userEmail, { deviceId, deviceName, devicePlatform, schedulerPreferenceId, intervalMinutes, takeover }) {
  const nowTime = now();
  const existingActiveDevice = await prisma.schedulerDeviceState.findFirst({
    where: { userEmail, isActiveDevice: true },
  });

  if (existingActiveDevice && existingActiveDevice.deviceId !== deviceId && takeover !== true) {
    return {
      conflict: true,
      activeDevice: activeDevicePayload(existingActiveDevice),
      isCurrentDeviceActive: false,
    };
  }

  const isTakeover = takeover === true;

  await prisma.$transaction(async (tx) => {
    if (isTakeover) {
      await tx.schedulerDeviceState.updateMany({
        where: { userEmail, isActiveDevice: true, NOT: { deviceId } },
        data: { isActiveDevice: false, stoppedAt: nowTime },
      });
    }

    await tx.schedulerDeviceState.upsert({
      where: { userEmail_deviceId: { userEmail, deviceId } },
      create: {
        userEmail, deviceId, deviceType: devicePlatform,
        deviceName: deviceName || null,
        isActiveDevice: true,
        activeDeviceLastSeenAt: nowTime,
        activatedAt: nowTime,
        intervalMinutes: intervalMinutes || null,
        schedulerStateJson: schedulerPreferenceId
          ? JSON.stringify({ preferenceId: schedulerPreferenceId })
          : null,
      },
      update: {
        deviceType: devicePlatform,
        deviceName: deviceName || undefined,
        isActiveDevice: true,
        activeDeviceLastSeenAt: nowTime,
        activatedAt: nowTime,
        stoppedAt: null,
        intervalMinutes: intervalMinutes || undefined,
      },
    });
  });

  const activated = await prisma.schedulerDeviceState.findUnique({
    where: { userEmail_deviceId: { userEmail, deviceId } },
  });

  return {
    conflict: false,
    isTakeover,
    activeDevice: activeDevicePayload(activated),
    isCurrentDeviceActive: true,
  };
}

export async function heartbeatDeviceService(userEmail, { deviceId, schedulerPreferenceId, lastJobEmailAt }) {
  const activeDevice = await prisma.schedulerDeviceState.findFirst({
    where: { userEmail, isActiveDevice: true },
  });

  if (!activeDevice || activeDevice.deviceId !== deviceId) {
    return {
      isCurrentDeviceActive: false,
      activeDevice: activeDevice
        ? { deviceId: activeDevice.deviceId, deviceName: activeDevice.deviceName, devicePlatform: activeDevice.deviceType }
        : null,
    };
  }

  const nowTime = now();
  const updateData = {
    activeDeviceLastSeenAt: nowTime,
    ...(schedulerPreferenceId
      ? { schedulerStateJson: JSON.stringify({ preferenceId: schedulerPreferenceId }) }
      : {}),
  };
  if (lastJobEmailAt) {
    updateData.lastJobEmailAt = new Date(lastJobEmailAt);
  }
  await prisma.schedulerDeviceState.update({
    where: { id: activeDevice.id },
    data: updateData,
  });

  return {
    isCurrentDeviceActive: true,
    activeDevice: {
      deviceId: activeDevice.deviceId,
      deviceName: activeDevice.deviceName,
      devicePlatform: activeDevice.deviceType,
      activeDeviceLastSeenAt: nowTime.toISOString(),
      lastJobEmailAt: updateData.lastJobEmailAt?.toISOString() || null,
      schedulerPreferenceId: schedulerPreferenceId || undefined,
    },
  };
}

export async function deactivateDeviceService(userEmail, { deviceId }) {
  const activeDevice = await prisma.schedulerDeviceState.findFirst({
    where: { userEmail, isActiveDevice: true },
  });

  if (!activeDevice) {
    return { deactivated: false, reason: 'no_active_device' };
  }

  if (activeDevice.deviceId !== deviceId) {
    return {
      deactivated: false,
      reason: 'not_active_device',
      activeDevice: { deviceId: activeDevice.deviceId, deviceName: activeDevice.deviceName },
    };
  }

  await prisma.schedulerDeviceState.update({
    where: { id: activeDevice.id },
    data: { isActiveDevice: false, stoppedAt: now() },
  });

  return { deactivated: true, stoppedAt: new Date().toISOString(), activeDevice: null };
}

export async function verifyDeviceService(userEmail, { deviceId }) {
  const activeDevice = await prisma.schedulerDeviceState.findFirst({
    where: { userEmail, isActiveDevice: true },
  });

  if (!activeDevice) {
    return { canSend: false, reason: 'no_active_device' };
  }

  const isActive = activeDevice.deviceId === deviceId;
  if (isActive) {
    return { canSend: true };
  }

  return {
    canSend: false,
    reason: 'device_not_active',
    activeDevice: {
      deviceId: activeDevice.deviceId,
      deviceName: activeDevice.deviceName,
      devicePlatform: activeDevice.deviceType,
    },
  };
}

// ================================================================
// TIMELINE
// ================================================================

export async function getUserTimelineService(userEmail) {
  const events = [];

  const devices = await prisma.schedulerDeviceState.findMany({
    where: { userEmail },
    orderBy: { activatedAt: 'desc' },
  });

  for (const device of devices) {
    const prefs = device.preferencesJson ? safeJsonParse(device.preferencesJson) : {};
    const role = prefs?.role || '';
    const location = prefs?.location || '';

    if (device.activatedAt) {
      events.push({
        type: 'scheduler_created',
        timestamp: device.activatedAt.toISOString(),
        title: role
          ? `Searching for ${role}${location ? ` in ${location}` : ''}`
          : 'Job alerts activated',
        description: device.deviceName
          ? `Managed by ${device.deviceName}${device.deviceType ? ` (${device.deviceType})` : ''}`
          : 'Scheduler created',
        status: 'success',
        metadata: {
          deviceName: device.deviceName || null,
          devicePlatform: device.deviceType || null,
          intervalMinutes: device.intervalMinutes || null,
          role: role || null,
          location: location || null,
        },
      });
    }

    if (device.lastSetupEmailAt) {
      events.push({
        type: 'setup_email_sent',
        timestamp: device.lastSetupEmailAt.toISOString(),
        title: 'Setup confirmation sent',
        description: 'Preferences confirmation email delivered',
        status: 'success',
        metadata: {},
      });
    }

    if (device.stoppedAt) {
      events.push({
        type: 'scheduler_stopped',
        timestamp: device.stoppedAt.toISOString(),
        title: role
          ? `Stopped searching for ${role}`
          : 'Job alerts stopped',
        description: device.deviceName
          ? `Stopped on ${device.deviceName}`
          : 'Scheduler deactivated',
        status: 'warning',
        metadata: {
          deviceName: device.deviceName || null,
          devicePlatform: device.deviceType || null,
          intervalMinutes: device.intervalMinutes || null,
          role: role || null,
          location: location || null,
        },
      });
    }
  }

  const emails = await prisma.emailSentHistory.findMany({
    where: { userEmail },
    orderBy: { emailSentAt: 'desc' },
    take: 100,
  });

  for (const email of emails) {
    events.push({
      type: 'job_alert_sent',
      timestamp: email.emailSentAt.toISOString(),
      title: email.searchTitle || 'Job alert delivered',
      description: `${email.jobsSentCount} job${email.jobsSentCount !== 1 ? 's' : ''} sent${email.pdfAttached ? ' with PDF attachment' : ''}`,
      status: 'success',
      runId: null,
      metadata: {
        jobsFound: email.jobsSentCount,
        jobsSent: email.jobsSentCount,
        pdfAttached: email.pdfAttached,
      },
    });
  }

  const runs = await prisma.schedulerRunHistory.findMany({
    where: { userEmail },
    orderBy: { startedAt: 'desc' },
    take: 100,
  });

  for (const run of runs) {
    const timelineEvent = mapRunToTimelineEvent(run);
    if (timelineEvent) events.push(timelineEvent);
  }

  events.sort((a, b) => new Date(b.timestamp) - new Date(a.timestamp));

  return events.slice(0, 200);
}
