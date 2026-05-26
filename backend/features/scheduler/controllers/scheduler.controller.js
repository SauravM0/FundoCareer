import { maskDeviceId } from '../../../shared/utils/logMasker.js';
import { sendSuccess, sendAuthError, sendValidationError, sendServerError, sendConflictError } from '../../../shared/utils/apiResponse.js';
import {
  getUserEmail,
  acquireLockService,
  releaseLockService,
  checkEmailedJobsService,
  markEmailedJobsService,
  syncStateService,
  getStateService,
  getEmailHistoryService,
  historySummaryService,
  getActiveDeviceService,
  activateDeviceService,
  heartbeatDeviceService,
  deactivateDeviceService,
  verifyDeviceService,
  getUserTimelineService,
} from '../services/scheduler.service.js';

function maskEmail(email) {
  if (!email || typeof email !== 'string' || !email.includes('@')) return null;
  const [local, domain] = email.split('@');
  return `${local.slice(0, 2)}***@${domain}`;
}

// ================================================================
// GET EMAIL HISTORY
// GET /api/scheduler/history
// ================================================================
export async function getEmailHistory(req, res) {
  try {
    const userEmail = getUserEmail(req);
    if (!userEmail) return sendAuthError(res, 'User email not found in token.');

    const { preferenceId } = req.query;
    const history = await getEmailHistoryService(userEmail, preferenceId);

    return sendSuccess(res, { history });
  } catch (err) {
    console.error('[EmailHistory] error:', err.message);
    return sendServerError(res, 'Failed to fetch email history.');
  }
}

// ================================================================
// LOCK ACQUIRE
// POST /api/scheduler/acquire-lock
// ================================================================
export async function acquireLock(req, res) {
  try {
    const userEmail = getUserEmail(req);
    if (!userEmail) return sendAuthError(res, 'User email not found in token.');

    const { preferenceSetId, deviceId, deviceType, appVersion } = req.body;
    if (!preferenceSetId || !deviceId || !deviceType) {
      return sendValidationError(res, 'Missing required fields: preferenceSetId, deviceId, deviceType.');
    }

    const result = await acquireLockService(userEmail, { preferenceSetId, deviceId, deviceType, appVersion });

    console.log(JSON.stringify({
      event: 'lock_acquire',
      userEmail: maskEmail(userEmail),
      deviceId: maskDeviceId(deviceId),
      preferenceSet: preferenceSetId,
      granted: result.granted,
      reason: result.reason || null,
    }));

    return sendSuccess(res, { data: result });
  } catch (err) {
    console.error('[Lock] acquireLock error:', err.message);
    return sendServerError(res, 'Failed to acquire lock.');
  }
}

// ================================================================
// LOCK RELEASE
// POST /api/scheduler/release-lock
// ================================================================
export async function releaseLock(req, res) {
  try {
    const userEmail = getUserEmail(req);
    if (!userEmail) return sendAuthError(res, 'User email not found in token.');

    const { preferenceSetId, deviceId } = req.body;
    if (!preferenceSetId || !deviceId) {
      return sendValidationError(res, 'Missing required fields: preferenceSetId, deviceId.');
    }

    const result = await releaseLockService(userEmail, { preferenceSetId, deviceId });

    console.log(JSON.stringify({
      event: 'lock_release',
      userEmail: maskEmail(userEmail),
      deviceId: maskDeviceId(deviceId),
      preferenceSet: preferenceSetId,
      released: result.released,
    }));

    return sendSuccess(res, { data: result });
  } catch (err) {
    console.error('[Lock] releaseLock error:', err.message);
    return sendServerError(res, 'Failed to release lock.');
  }
}

// ================================================================
// CHECK EMAILED FINGERPRINTS
// POST /api/scheduler/check-emailed-jobs
// ================================================================
export async function checkEmailedJobs(req, res) {
  try {
    const userEmail = getUserEmail(req);
    if (!userEmail) return sendAuthError(res, 'User email not found in token.');

    const { fingerprints, preferenceId } = req.body;
    if (!Array.isArray(fingerprints) || fingerprints.length === 0) {
      return sendValidationError(res, 'Missing or empty fingerprints array.');
    }

    const data = await checkEmailedJobsService(userEmail, fingerprints);

    console.log(JSON.stringify({
      event: 'check_emailed',
      userEmail: maskEmail(userEmail),
      total: fingerprints.length,
      already: data.alreadyEmailed.length,
      newHits: data.new.length,
    }));

    return sendSuccess(res, { data });
  } catch (err) {
    console.error('[CheckEmailed] error:', err.message);
    return sendServerError(res, 'Failed to check emailed jobs.');
  }
}

// ================================================================
// MARK FINGERPRINTS AS EMAILED
// POST /api/scheduler/mark-emailed-jobs
// ================================================================
export async function markEmailedJobs(req, res) {
  try {
    const userEmail = getUserEmail(req);
    if (!userEmail) return sendAuthError(res, 'User email not found in token.');

    const { fingerprints, preferenceId, emailRecordId } = req.body;
    if (!Array.isArray(fingerprints) || fingerprints.length === 0) {
      return sendValidationError(res, 'Missing or empty fingerprints array.');
    }
    if (!preferenceId) {
      return sendValidationError(res, 'Missing preferenceId.');
    }

    const data = await markEmailedJobsService(userEmail, { fingerprints, preferenceId, emailRecordId });

    console.log(JSON.stringify({
      event: 'mark_emailed',
      userEmail: maskEmail(userEmail),
      requested: fingerprints.length,
      marked: data.marked,
      duplicates: data.skippedDuplicates,
    }));

    return sendSuccess(res, { data });
  } catch (err) {
    console.error('[MarkEmailed] error:', err.message);
    return sendServerError(res, 'Failed to mark emailed jobs.');
  }
}

// ================================================================
// SYNC DEVICE STATE
// POST /api/scheduler/sync-state
// ================================================================
export async function syncState(req, res) {
  try {
    const userEmail = getUserEmail(req);
    if (!userEmail) return sendAuthError(res, 'User email not found in token.');

    const { deviceId, deviceType, appVersion, preferences, schedulerState, recentRuns } = req.body;
    if (!deviceId || !deviceType) {
      return sendValidationError(res, 'Missing required fields: deviceId, deviceType.');
    }

    const data = await syncStateService(userEmail, { deviceId, deviceType, appVersion, preferences, schedulerState, recentRuns });

    console.log(JSON.stringify({
      event: 'sync_state',
      userEmail: maskEmail(userEmail),
      deviceId: maskDeviceId(deviceId),
      type: deviceType,
    }));

    return sendSuccess(res, { data });
  } catch (err) {
    console.error('[SyncState] error:', err.message);
    return sendServerError(res, 'Failed to sync state.');
  }
}

// ================================================================
// GET DEVICE STATE
// GET /api/scheduler/state?deviceId=xxx
// ================================================================
export async function getState(req, res) {
  try {
    const userEmail = getUserEmail(req);
    if (!userEmail) return sendAuthError(res, 'User email not found in token.');

    const deviceId = req.query.deviceId;
    if (!deviceId) {
      return sendValidationError(res, 'Missing deviceId query parameter.');
    }

    const data = await getStateService(userEmail, deviceId);

    return sendSuccess(res, { data });
  } catch (err) {
    console.error('[GetState] error:', err.message);
    return sendServerError(res, 'Failed to get state.');
  }
}

// ================================================================
// HISTORY SUMMARY
// POST /api/scheduler/history-summary
// ================================================================
export async function historySummary(req, res) {
  try {
    const userEmail = getUserEmail(req);
    if (!userEmail) return sendAuthError(res, 'User email not found in token.');

    const { preferenceId } = req.body;
    const data = await historySummaryService(userEmail, preferenceId);

    return sendSuccess(res, { data });
  } catch (err) {
    console.error('[HistorySummary] error:', err.message);
    return sendServerError(res, 'Failed to get history summary.');
  }
}

// ================================================================
// GET ACTIVE DEVICE
// GET /api/scheduler/active-device?deviceId=xxx
// ================================================================
export async function getActiveDevice(req, res) {
  try {
    const userEmail = getUserEmail(req);
    if (!userEmail) return sendAuthError(res, 'User email not found in token.');

    const deviceId = req.query.deviceId;
    const result = await getActiveDeviceService(userEmail, deviceId);

    return sendSuccess(res, result);
  } catch (err) {
    console.error('[ActiveDevice] getActiveDevice error:', err.message);
    return sendServerError(res, 'Failed to get active device.');
  }
}

// ================================================================
// ACTIVATE DEVICE
// POST /api/scheduler/active-device/activate
// ================================================================
export async function activateDevice(req, res) {
  try {
    const userEmail = getUserEmail(req);
    if (!userEmail) return sendAuthError(res, 'User email not found in token.');

    const { deviceId, deviceName, devicePlatform, schedulerPreferenceId, intervalMinutes, takeover } = req.body;
    if (!deviceId || !devicePlatform) {
      return sendValidationError(res, 'Missing required fields: deviceId, devicePlatform.');
    }

    const result = await activateDeviceService(userEmail, {
      deviceId, deviceName, devicePlatform, schedulerPreferenceId, intervalMinutes, takeover,
    });

    if (result.conflict) {
      console.log(JSON.stringify({
        event: 'device_activation_conflict',
        userEmail: maskEmail(userEmail),
        requester: maskDeviceId(deviceId),
        active: maskDeviceId(result.activeDevice?.deviceId),
      }));
      return res.status(409).json({
        success: false,
        requestId: req.requestId || undefined,
        errorCode: 'ACTIVE_DEVICE_EXISTS',
        message: 'Another device is already managing this scheduler.',
        activeDevice: result.activeDevice,
        isCurrentDeviceActive: false,
      });
    }

    console.log(JSON.stringify({
      event: result.isTakeover ? 'device_takeover' : 'device_activated',
      userEmail: maskEmail(userEmail),
      deviceId: maskDeviceId(deviceId),
      platform: devicePlatform,
    }));

    return sendSuccess(res, {
      activeDevice: result.activeDevice,
      isCurrentDeviceActive: true,
    });
  } catch (err) {
    console.error('[ActiveDevice] activateDevice error:', err.message);
    return sendServerError(res, 'Failed to activate device.');
  }
}

// ================================================================
// HEARTBEAT DEVICE
// POST /api/scheduler/active-device/heartbeat
// ================================================================
export async function heartbeatDevice(req, res) {
  try {
    const userEmail = getUserEmail(req);
    if (!userEmail) return sendAuthError(res, 'User email not found in token.');

    const { deviceId, schedulerPreferenceId, lastJobEmailAt } = req.body;
    if (!deviceId) {
      return sendValidationError(res, 'Missing required field: deviceId.');
    }

    const result = await heartbeatDeviceService(userEmail, { deviceId, schedulerPreferenceId, lastJobEmailAt });

    console.log(JSON.stringify({
      event: 'device_heartbeat',
      userEmail: maskEmail(userEmail),
      deviceId: maskDeviceId(deviceId),
      active: result.isCurrentDeviceActive,
    }));

    return sendSuccess(res, result);
  } catch (err) {
    console.error('[ActiveDevice] heartbeatDevice error:', err.message);
    return sendServerError(res, 'Failed to heartbeat device.');
  }
}

// ================================================================
// DEACTIVATE DEVICE
// POST /api/scheduler/active-device/deactivate
// ================================================================
export async function deactivateDevice(req, res) {
  try {
    const userEmail = getUserEmail(req);
    if (!userEmail) return sendAuthError(res, 'User email not found in token.');

    const { deviceId } = req.body;
    if (!deviceId) {
      return sendValidationError(res, 'Missing required field: deviceId.');
    }

    const result = await deactivateDeviceService(userEmail, { deviceId });

    if (!result.deactivated) {
      console.log(JSON.stringify({
        event: 'device_deactivation_skipped',
        userEmail: maskEmail(userEmail),
        deviceId: maskDeviceId(deviceId),
        reason: result.reason,
      }));
      return sendSuccess(res, {
        deactivated: false,
        reason: result.reason,
        ...(result.activeDevice ? { activeDevice: result.activeDevice } : {}),
      });
    }

    console.log(JSON.stringify({
      event: 'device_deactivated',
      userEmail: maskEmail(userEmail),
      deviceId: maskDeviceId(deviceId),
    }));

    return sendSuccess(res, {
      deactivated: true,
      stoppedAt: result.stoppedAt,
      activeDevice: null,
    });
  } catch (err) {
    console.error('[ActiveDevice] deactivateDevice error:', err.message);
    return sendServerError(res, 'Failed to deactivate device.');
  }
}

// ================================================================
// VERIFY DEVICE CAN SEND
// POST /api/scheduler/active-device/verify
// ================================================================
export async function verifyDevice(req, res) {
  try {
    const userEmail = getUserEmail(req);
    if (!userEmail) return sendAuthError(res, 'User email not found in token.');

    const { deviceId } = req.body;
    if (!deviceId) {
      return sendValidationError(res, 'Missing required field: deviceId.');
    }

    const result = await verifyDeviceService(userEmail, { deviceId });

    return sendSuccess(res, result);
  } catch (err) {
    console.error('[ActiveDevice] verifyDevice error:', err.message);
    return sendServerError(res, 'Failed to verify device.');
  }
}

// ================================================================
// GET USER TIMELINE
// GET /api/scheduler/user-timeline
// ================================================================
export async function getUserTimeline(req, res) {
  try {
    const userEmail = getUserEmail(req);
    if (!userEmail) return sendAuthError(res, 'User email not found in token.');

    const timeline = await getUserTimelineService(userEmail);

    return sendSuccess(res, { timeline });
  } catch (err) {
    console.error('[UserTimeline] error:', err.message);
    return sendServerError(res, 'Failed to fetch user timeline.');
  }
}
