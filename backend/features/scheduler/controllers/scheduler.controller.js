import prisma from '../../../config/database.config.js';
import { normalizeFingerprint, normalizeFingerprints } from '../../../shared/services/fingerprint.service.js';
import { maskDeviceId } from '../../../shared/utils/logMasker.js';

const LOCK_TTL_MS = 10 * 60 * 1000;

function now() { return new Date(); }

function future(ms) { return new Date(Date.now() + ms); }

function getUserEmail(req) {
  return req.user?.email || null;
}

function activeDevicePayload(device) {
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

// ================================================================
// GET EMAIL HISTORY (positive-only)
// GET /api/scheduler/history
// ================================================================
export async function getEmailHistory(req, res) {
  try {
    const userEmail = getUserEmail(req);
    if (!userEmail) {
      return res.status(401).json({ success: false, message: 'User email not found in token.' });
    }

    const { preferenceId } = req.query;

    const whereClause = { userEmail };
    if (preferenceId) {
      whereClause.schedulerPreferenceId = preferenceId;
    }

    const rows = await prisma.emailSentHistory.findMany({
      where: whereClause,
      orderBy: { emailSentAt: 'desc' },
      take: 50,
    });

    const history = rows.map(r => ({
      emailSentAt: r.emailSentAt.toISOString(),
      jobsSentCount: r.jobsSentCount,
      pdfAttached: r.pdfAttached,
      title: r.searchTitle || 'Job alert delivered successfully',
    }));

    return res.json({ success: true, history });
  } catch (err) {
    console.error('[EmailHistory] error:', err.message);
    return res.status(500).json({ success: false, message: 'Failed to fetch email history.' });
  }
}

// ================================================================
// LOCK ACQUIRE
// POST /api/scheduler/acquire-lock
// ================================================================
export async function acquireLock(req, res) {
  try {
    const userEmail = getUserEmail(req);
    if (!userEmail) {
      return res.status(401).json({ success: false, message: 'User email not found in token.' });
    }

    const { preferenceSetId, deviceId, deviceType, appVersion } = req.body;
    if (!preferenceSetId || !deviceId || !deviceType) {
      return res.status(400).json({ success: false, message: 'Missing required fields: preferenceSetId, deviceId, deviceType.' });
    }

    const activeDevice = await prisma.schedulerDeviceState.findFirst({
      where: { userEmail, isActiveDevice: true },
    });
    if (!activeDevice) {
      console.log(`[Lock] Rejected: no active device for user=${userEmail}, requester=${maskDeviceId(deviceId)}, preferenceSet=${preferenceSetId}`);
      return res.json({
        success: true,
        data: { granted: false, reason: 'no_active_device' },
      });
    }
    if (activeDevice.deviceId !== deviceId) {
      console.log(`[Lock] Rejected: requester=${maskDeviceId(deviceId)} is not active device=${maskDeviceId(activeDevice.deviceId)}, preferenceSet=${preferenceSetId}`);
      return res.json({
        success: true,
        data: {
          granted: false,
          reason: 'device_not_active',
          heldBy: activeDevice.deviceId,
          heldByDeviceType: activeDevice.deviceType,
        },
      });
    }

    const lockKey = { userEmail, preferenceSetId };
    const existingLock = await prisma.schedulerLock.findUnique({ where: { userEmail_preferenceSetId: lockKey } });

    if (existingLock) {
      const isExpired = existingLock.expiresAt < now();
      if (!isExpired) {
        console.log(`[Lock] Denied: lock held by ${maskDeviceId(existingLock.deviceId)} (expires ${existingLock.expiresAt.toISOString()})`);
        return res.json({
          success: true,
          data: {
            granted: false,
            reason: 'lock_held_by_other_device',
            heldBy: existingLock.deviceId,
            heldByDeviceType: existingLock.deviceType,
            acquiredAt: existingLock.acquiredAt.toISOString(),
            expiresAt: existingLock.expiresAt.toISOString(),
          },
        });
      }
      console.log(`[Lock] Expired lock from ${maskDeviceId(existingLock.deviceId)}, re-acquiring for ${maskDeviceId(deviceId)}`);
    }

    const expiresAt = future(LOCK_TTL_MS);
    const lock = await prisma.schedulerLock.upsert({
      where: { userEmail_preferenceSetId: lockKey },
      create: {
        userEmail,
        preferenceSetId,
        deviceId,
        deviceType,
        appVersion: appVersion || null,
        expiresAt,
        lastSeenAt: now(),
      },
      update: {
        deviceId,
        deviceType,
        appVersion: appVersion || null,
        acquiredAt: now(),
        expiresAt,
        lastSeenAt: now(),
      },
    });

    console.log(`[Lock] Acquired: device=${maskDeviceId(deviceId)}, preferenceSet=${preferenceSetId}, expires=${expiresAt.toISOString()}`);
    return res.json({
      success: true,
      data: {
        granted: true,
        lockId: lock.id,
        acquiredAt: lock.acquiredAt.toISOString(),
        expiresAt: lock.expiresAt.toISOString(),
      },
    });
  } catch (err) {
    console.error('[Lock] acquireLock error:', err.message);
    return res.status(500).json({ success: false, message: 'Failed to acquire lock.' });
  }
}

// ================================================================
// LOCK RELEASE
// POST /api/scheduler/release-lock
// ================================================================
export async function releaseLock(req, res) {
  try {
    const userEmail = getUserEmail(req);
    if (!userEmail) {
      return res.status(401).json({ success: false, message: 'User email not found in token.' });
    }

    const { preferenceSetId, deviceId } = req.body;
    if (!preferenceSetId || !deviceId) {
      return res.status(400).json({ success: false, message: 'Missing required fields: preferenceSetId, deviceId.' });
    }

    const lock = await prisma.schedulerLock.findUnique({
      where: { userEmail_preferenceSetId: { userEmail, preferenceSetId } },
    });

    if (!lock) {
      return res.json({ success: true, data: { released: false, reason: 'no_lock' } });
    }

    if (lock.deviceId !== deviceId) {
      return res.json({ success: true, data: { released: false, reason: 'not_lock_holder', heldBy: lock.deviceId } });
    }

    await prisma.schedulerLock.delete({ where: { id: lock.id } });
    console.log(`[Lock] Released: device=${maskDeviceId(deviceId)}, preferenceSet=${preferenceSetId}`);
    return res.json({ success: true, data: { released: true } });
  } catch (err) {
    console.error('[Lock] releaseLock error:', err.message);
    return res.status(500).json({ success: false, message: 'Failed to release lock.' });
  }
}

// ================================================================
// CHECK EMAILED FINGERPRINTS
// POST /api/scheduler/check-emailed-jobs
// ================================================================
export async function checkEmailedJobs(req, res) {
  try {
    const userEmail = getUserEmail(req);
    if (!userEmail) {
      return res.status(401).json({ success: false, message: 'User email not found in token.' });
    }

    const { fingerprints, preferenceId } = req.body;
    if (!Array.isArray(fingerprints) || fingerprints.length === 0) {
      return res.status(400).json({ success: false, message: 'Missing or empty fingerprints array.' });
    }

    const normalizedIncoming = normalizeFingerprints(fingerprints);
    const lookupSet = new Set([...fingerprints, ...normalizedIncoming].filter(f => f.length > 0));

    const existing = await prisma.emailedJobFingerprint.findMany({
      where: {
        userEmail,
        fingerprint: { in: Array.from(lookupSet) },
      },
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

    console.log(`[CheckEmailed] user=${userEmail}, total=${fingerprints.length}, already=${alreadyEmailed.size}, new=${newFingerprints.length}`);
    return res.json({
      success: true,
      data: {
        alreadyEmailed: Array.from(alreadyEmailed),
        new: newFingerprints,
      },
    });
  } catch (err) {
    console.error('[CheckEmailed] error:', err.message);
    return res.status(500).json({ success: false, message: 'Failed to check emailed jobs.' });
  }
}

// ================================================================
// MARK FINGERPRINTS AS EMAILED
// POST /api/scheduler/mark-emailed-jobs
// ================================================================
export async function markEmailedJobs(req, res) {
  try {
    const userEmail = getUserEmail(req);
    if (!userEmail) {
      return res.status(401).json({ success: false, message: 'User email not found in token.' });
    }

    const { fingerprints, preferenceId, emailRecordId } = req.body;
    if (!Array.isArray(fingerprints) || fingerprints.length === 0) {
      return res.status(400).json({ success: false, message: 'Missing or empty fingerprints array.' });
    }
    if (!preferenceId) {
      return res.status(400).json({ success: false, message: 'Missing preferenceId.' });
    }

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
        userEmail,
        preferenceId,
        fingerprint,
        emailedAt: now(),
        emailRecordId: emailRecordId || null,
      }));

    let marked = 0;
    if (toInsert.length > 0) {
      await prisma.emailedJobFingerprint.createMany({ data: toInsert, skipDuplicates: true });
      marked = toInsert.length;
    }

    console.log(`[MarkEmailed] user=${userEmail}, requested=${fingerprints.length}, newlyMarked=${marked}, alreadyMarked=${existingHashes.size}`);
    return res.json({
      success: true,
      data: { marked, total: fingerprints.length, skippedDuplicates: fingerprints.length - marked },
    });
  } catch (err) {
    console.error('[MarkEmailed] error:', err.message);
    return res.status(500).json({ success: false, message: 'Failed to mark emailed jobs.' });
  }
}

// ================================================================
// SYNC DEVICE STATE
// POST /api/scheduler/sync-state
// ================================================================
export async function syncState(req, res) {
  try {
    const userEmail = getUserEmail(req);
    if (!userEmail) {
      return res.status(401).json({ success: false, message: 'User email not found in token.' });
    }

    const { deviceId, deviceType, appVersion, preferences, schedulerState, recentRuns } = req.body;
    if (!deviceId || !deviceType) {
      return res.status(400).json({ success: false, message: 'Missing required fields: deviceId, deviceType.' });
    }

    const nowTime = now();
    await prisma.schedulerDeviceState.upsert({
      where: { userEmail_deviceId: { userEmail, deviceId } },
      create: {
        userEmail,
        deviceId,
        deviceType,
        appVersion: appVersion || null,
        lastSyncAt: nowTime,
        lastSeenAt: nowTime,
        preferencesJson: preferences ? JSON.stringify(preferences) : null,
        schedulerStateJson: schedulerState ? JSON.stringify(schedulerState) : null,
      },
      update: {
        deviceType,
        appVersion: appVersion || null,
        lastSyncAt: nowTime,
        lastSeenAt: nowTime,
        preferencesJson: preferences ? JSON.stringify(preferences) : undefined,
        schedulerStateJson: schedulerState ? JSON.stringify(schedulerState) : undefined,
      },
    });

    if (Array.isArray(recentRuns) && recentRuns.length > 0) {
      const runData = recentRuns.map(run => ({
        userEmail,
        deviceId,
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

    console.log(`[SyncState] user=${userEmail}, device=${maskDeviceId(deviceId)}, type=${deviceType}`);
    return res.json({
      success: true,
      data: { syncedAt: nowTime.toISOString() },
    });
  } catch (err) {
    console.error('[SyncState] error:', err.message);
    return res.status(500).json({ success: false, message: 'Failed to sync state.' });
  }
}

// ================================================================
// GET DEVICE STATE
// GET /api/scheduler/state?deviceId=xxx
// ================================================================
export async function getState(req, res) {
  try {
    const userEmail = getUserEmail(req);
    if (!userEmail) {
      return res.status(401).json({ success: false, message: 'User email not found in token.' });
    }

    const deviceId = req.query.deviceId;
    if (!deviceId) {
      return res.status(400).json({ success: false, message: 'Missing deviceId query parameter.' });
    }

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

    return res.json({
      success: true,
      data: {
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
      },
    });
  } catch (err) {
    console.error('[GetState] error:', err.message);
    return res.status(500).json({ success: false, message: 'Failed to get state.' });
  }
}

// ================================================================
// HISTORY SUMMARY
// POST /api/scheduler/history-summary
// ================================================================
export async function historySummary(req, res) {
  try {
    const userEmail = getUserEmail(req);
    if (!userEmail) {
      return res.status(401).json({ success: false, message: 'User email not found in token.' });
    }

    const { preferenceId } = req.body;

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

    return res.json({
      success: true,
      data: {
        totalRuns,
        lastRunAt: lastRun?.startedAt?.toISOString() || null,
        lastRunStatus: lastRun?.status || null,
        totalJobsFound: aggregation._sum?.jobsFound || 0,
        totalJobsNew: aggregation._sum?.jobsNew || 0,
        totalEmailsSent,
        lastEmailSentAt: lastEmail?.emailedAt?.toISOString() || null,
      },
    });
  } catch (err) {
    console.error('[HistorySummary] error:', err.message);
    return res.status(500).json({ success: false, message: 'Failed to get history summary.' });
  }
}

// ================================================================
// GET ACTIVE DEVICE
// GET /api/scheduler/active-device?deviceId=xxx
// ================================================================
export async function getActiveDevice(req, res) {
  try {
    const userEmail = getUserEmail(req);
    if (!userEmail) {
      return res.status(401).json({ success: false, message: 'User email not found in token.' });
    }

    const deviceId = req.query.deviceId;
    const activeDevice = await prisma.schedulerDeviceState.findFirst({
      where: { userEmail, isActiveDevice: true },
    });

    if (!activeDevice) {
      return res.json({
        success: true,
        activeDevice: null,
        isCurrentDeviceActive: false,
      });
    }

    return res.json({
      success: true,
      activeDevice: {
        ...activeDevicePayload(activeDevice),
      },
      isCurrentDeviceActive: deviceId ? activeDevice.deviceId === deviceId : false,
    });
  } catch (err) {
    console.error('[ActiveDevice] getActiveDevice error:', err.message);
    return res.status(500).json({ success: false, message: 'Failed to get active device.' });
  }
}

// ================================================================
// ACTIVATE DEVICE
// POST /api/scheduler/active-device/activate
// ================================================================
export async function activateDevice(req, res) {
  try {
    const userEmail = getUserEmail(req);
    if (!userEmail) {
      return res.status(401).json({ success: false, message: 'User email not found in token.' });
    }

    const { deviceId, deviceName, devicePlatform, schedulerPreferenceId, intervalMinutes, takeover } = req.body;
    if (!deviceId || !devicePlatform) {
      return res.status(400).json({ success: false, message: 'Missing required fields: deviceId, devicePlatform.' });
    }

    const nowTime = now();
    const existingActiveDevice = await prisma.schedulerDeviceState.findFirst({
      where: { userEmail, isActiveDevice: true },
    });
    if (existingActiveDevice && existingActiveDevice.deviceId !== deviceId && takeover !== true) {
      console.log(`[ActiveDevice] Activation rejected: user=${userEmail}, requester=${maskDeviceId(deviceId)}, active=${maskDeviceId(existingActiveDevice.deviceId)}`);
      return res.status(409).json({
        success: false,
        errorCode: 'ACTIVE_DEVICE_EXISTS',
        message: 'Another device is already managing this scheduler.',
        activeDevice: activeDevicePayload(existingActiveDevice),
        isCurrentDeviceActive: false,
      });
    }

    await prisma.$transaction(async (tx) => {
      if (takeover === true) {
        await tx.schedulerDeviceState.updateMany({
          where: { userEmail, isActiveDevice: true, NOT: { deviceId } },
          data: { isActiveDevice: false, stoppedAt: nowTime },
        });
      }

      await tx.schedulerDeviceState.upsert({
        where: { userEmail_deviceId: { userEmail, deviceId } },
        create: {
          userEmail,
          deviceId,
          deviceType: devicePlatform,
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

    console.log(`[ActiveDevice] ${takeover === true ? 'Takeover' : 'Activated'}: user=${userEmail}, device=${maskDeviceId(deviceId)}, platform=${devicePlatform}`);
    return res.json({
      success: true,
      activeDevice: activeDevicePayload(activated),
      isCurrentDeviceActive: true,
    });
  } catch (err) {
    console.error('[ActiveDevice] activateDevice error:', err.message);
    return res.status(500).json({ success: false, message: 'Failed to activate device.' });
  }
}

// ================================================================
// HEARTBEAT DEVICE
// POST /api/scheduler/active-device/heartbeat
// ================================================================
export async function heartbeatDevice(req, res) {
  try {
    const userEmail = getUserEmail(req);
    if (!userEmail) {
      return res.status(401).json({ success: false, message: 'User email not found in token.' });
    }

    const { deviceId, schedulerPreferenceId, lastJobEmailAt } = req.body;
    if (!deviceId) {
      return res.status(400).json({ success: false, message: 'Missing required field: deviceId.' });
    }

    const activeDevice = await prisma.schedulerDeviceState.findFirst({
      where: { userEmail, isActiveDevice: true },
    });

    if (!activeDevice || activeDevice.deviceId !== deviceId) {
      return res.json({
        success: true,
        isCurrentDeviceActive: false,
        activeDevice: activeDevice
          ? { deviceId: activeDevice.deviceId, deviceName: activeDevice.deviceName, devicePlatform: activeDevice.deviceType }
          : null,
      });
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

    console.log(`[ActiveDevice] Heartbeat: user=${userEmail}, device=${maskDeviceId(deviceId)}`);
    return res.json({
      success: true,
      isCurrentDeviceActive: true,
      activeDevice: {
        deviceId: activeDevice.deviceId,
        deviceName: activeDevice.deviceName,
        devicePlatform: activeDevice.deviceType,
        activeDeviceLastSeenAt: nowTime.toISOString(),
        lastJobEmailAt: updateData.lastJobEmailAt?.toISOString() || null,
        schedulerPreferenceId: schedulerPreferenceId || undefined,
      },
    });
  } catch (err) {
    console.error('[ActiveDevice] heartbeatDevice error:', err.message);
    return res.status(500).json({ success: false, message: 'Failed to heartbeat device.' });
  }
}

// ================================================================
// DEACTIVATE DEVICE
// POST /api/scheduler/active-device/deactivate
// ================================================================
export async function deactivateDevice(req, res) {
  try {
    const userEmail = getUserEmail(req);
    if (!userEmail) {
      return res.status(401).json({ success: false, message: 'User email not found in token.' });
    }

    const { deviceId, schedulerPreferenceId } = req.body;
    if (!deviceId) {
      return res.status(400).json({ success: false, message: 'Missing required field: deviceId.' });
    }

    const activeDevice = await prisma.schedulerDeviceState.findFirst({
      where: { userEmail, isActiveDevice: true },
    });

    if (!activeDevice) {
      return res.json({
        success: true,
        deactivated: false,
        reason: 'no_active_device',
      });
    }

    if (activeDevice.deviceId !== deviceId) {
      return res.json({
        success: true,
        deactivated: false,
        reason: 'not_active_device',
        activeDevice: { deviceId: activeDevice.deviceId, deviceName: activeDevice.deviceName },
      });
    }

    await prisma.schedulerDeviceState.update({
      where: { id: activeDevice.id },
      data: { isActiveDevice: false, stoppedAt: now() },
    });

    console.log(`[ActiveDevice] Deactivated: user=${userEmail}, device=${maskDeviceId(deviceId)}`);
    return res.json({
      success: true,
      deactivated: true,
      stoppedAt: new Date().toISOString(),
      activeDevice: null,
    });
  } catch (err) {
    console.error('[ActiveDevice] deactivateDevice error:', err.message);
    return res.status(500).json({ success: false, message: 'Failed to deactivate device.' });
  }
}

// ================================================================
// VERIFY DEVICE CAN SEND
// POST /api/scheduler/active-device/verify
// ================================================================
export async function verifyDevice(req, res) {
  try {
    const userEmail = getUserEmail(req);
    if (!userEmail) {
      return res.status(401).json({ success: false, message: 'User email not found in token.' });
    }

    const { deviceId, schedulerPreferenceId } = req.body;
    if (!deviceId) {
      return res.status(400).json({ success: false, message: 'Missing required field: deviceId.' });
    }

    const activeDevice = await prisma.schedulerDeviceState.findFirst({
      where: { userEmail, isActiveDevice: true },
    });

    if (!activeDevice) {
      return res.json({
        success: true,
        canSend: false,
        reason: 'no_active_device',
      });
    }

    const isActive = activeDevice.deviceId === deviceId;
    return res.json({
      success: true,
      canSend: isActive,
      ...(isActive
        ? {}
        : {
            reason: 'device_not_active',
            activeDevice: {
              deviceId: activeDevice.deviceId,
              deviceName: activeDevice.deviceName,
              devicePlatform: activeDevice.deviceType,
            },
          }),
    });
  } catch (err) {
    console.error('[ActiveDevice] verifyDevice error:', err.message);
    return res.status(500).json({ success: false, message: 'Failed to verify device.' });
  }
}

// ================================================================
// GET USER TIMELINE (positive-only events)
// GET /api/scheduler/user-timeline
// ================================================================
export async function getUserTimeline(req, res) {
  try {
    const userEmail = getUserEmail(req);
    if (!userEmail) {
      return res.status(401).json({ success: false, message: 'User email not found in token.' });
    }

    const events = [];

    // 1. Device lifecycle events (scheduler_created, scheduler_stopped, setup_email_sent)
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

    // 2. Email sent history (positive-only job alert emails)
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
        metadata: {
          jobsSentCount: email.jobsSentCount,
          pdfAttached: email.pdfAttached,
        },
      });
    }

    // 3. Sort by timestamp descending (newest first)
    events.sort((a, b) => new Date(b.timestamp) - new Date(a.timestamp));

    return res.json({ success: true, timeline: events.slice(0, 100) });
  } catch (err) {
    console.error('[UserTimeline] error:', err.message);
    return res.status(500).json({ success: false, message: 'Failed to fetch user timeline.' });
  }
}

function safeJsonParse(str) {
  try { 
    return JSON.parse(str); 
  } catch (error) { 
    console.warn(`[Scheduler] Failed to parse JSON: ${error.message}`);
    return null; 
  }
}
