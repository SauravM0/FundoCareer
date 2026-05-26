import { getEmailConfigStatus } from '../../../services/email.service.js';
import { maskDeviceId, maskEmail } from '../../../shared/utils/logMasker.js';
import { sendSuccess, sendAuthError, sendValidationError, sendServerError } from '../../../shared/utils/apiResponse.js';
import {
  getUserEmail,
  getUserRole,
  sendJobAlertEmailService,
  sendSetupConfirmationEmailService,
} from '../services/notification.service.js';
import { randomBytes } from 'crypto';

function randomId() {
  return randomBytes(4).toString('hex');
}

function log(level, event, data) {
  const entry = { event, timestamp: new Date().toISOString(), ...data };
  if (level === 'error') {
    console.error(JSON.stringify(entry));
  } else {
    console.log(JSON.stringify(entry));
  }
}

// ================================================================
// GET EMAIL CONFIG STATUS (safe diagnostic — no secrets)
// GET /api/notifications/email-status
// ================================================================
export async function getEmailStatus(req, res) {
  const status = getEmailConfigStatus();
  return sendSuccess(res, { email: status });
}

// ================================================================
// SEND JOB ALERT EMAIL
// POST /api/notifications/send-email
// ================================================================
export async function sendJobAlertEmail(req, res) {
  const requestLogId = randomId();
  try {
    const userEmail = getUserEmail(req);
    if (!userEmail) {
      log('error', 'email_request_no_auth', { requestLogId });
      return sendAuthError(res, 'User email not found in token.');
    }

    const {
      to, preferenceId, preferenceName, newJobs, allJobs, deviceId, runId,
    } = req.body;

    log('info', 'email_request_received', {
      requestLogId, userEmail, to: maskEmail(to),
      preferenceId, newJobsCount: Array.isArray(newJobs) ? newJobs.length : 0,
      allJobsCount: typeof allJobs === 'number' ? allJobs : 0,
      deviceId: maskDeviceId(deviceId || null), runId: runId || null,
    });

    if (!to || !preferenceId) {
      log('warn', 'email_validation_failed', { requestLogId, reason: 'Missing to or preferenceId' });
      return sendValidationError(res, 'Missing required fields: to, preferenceId.', 'MISSING_FIELDS');
    }

    const role = getUserRole(req);
    const result = await sendJobAlertEmailService(userEmail, role, {
      to, preferenceId, preferenceName, newJobs, allJobs, deviceId, runId,
    });

    if (result.error) {
      log('error', 'email_send_failed', {
        requestLogId, userEmail, preferenceId,
        errorCode: result.errorCode || 'UNKNOWN',
      });
      return res.status(result.statusCode).json({
        success: false,
        requestId: req.requestId || undefined,
        emailSent: result.emailSent || false,
        jobsSent: result.jobsSent || 0,
        message: result.message,
        errorCode: result.errorCode || 'EMAIL_SEND_FAILED',
      });
    }

    log('info', 'email_sent_success', {
      requestLogId, userEmail, to: maskEmail(to), preferenceId,
      runId: runId || null, jobsInEmail: newJobs?.length || 0,
      pdfAttached: result.pdfAttached,
      errorCode: result.errorCode,
    });

    return sendSuccess(res, {
      emailSent: true, jobEmailSent: result.jobEmailSent,
      jobsSent: result.jobsSent, pdfAttached: result.pdfAttached,
      errorCode: result.errorCode,
    });
  } catch (err) {
    log('error', 'email_unexpected_error', {
      requestLogId, error: err.message,
      stack: process.env.NODE_ENV === 'development' ? err.stack : undefined,
    });
    return sendServerError(res, 'Failed to send email.', 'UNEXPECTED_ERROR');
  }
}

// ================================================================
// SEND SETUP CONFIRMATION EMAIL
// POST /api/notifications/send-setup-email
// ================================================================
export async function sendSetupConfirmationEmail(req, res) {
  const requestLogId = randomId();
  const endpoint = '/api/notifications/send-setup-email';
  try {
    const userEmail = getUserEmail(req);
    if (!userEmail) {
      log('error', 'setup_email_no_auth', { requestLogId, endpoint, httpStatus: 401, errorCode: 'AUTH_REQUIRED' });
      return res.status(401).json({
        success: false, requestId: req.requestId || undefined,
        emailSent: false, setupEmailSent: false,
        message: 'User email not found in token.', errorCode: 'AUTH_REQUIRED',
      });
    }

    const { to, preferenceId, preferenceDetails, role: jobRole, location, experience, skills, remote, datePosted, intervalMinutes, deviceId } = req.body;

    log('info', 'setup_email_request_received', {
      requestLogId, endpoint, userEmail, to: maskEmail(to), preferenceId, deviceId: maskDeviceId(deviceId || null),
    });

    if (!to || !preferenceId) {
      log('warn', 'setup_email_validation_failed', { requestLogId, endpoint, httpStatus: 400, errorCode: 'MISSING_FIELDS', reason: 'Missing to or preferenceId' });
      return res.status(400).json({
        success: false, requestId: req.requestId || undefined,
        emailSent: false, setupEmailSent: false,
        message: 'Missing required fields: to, preferenceId.', errorCode: 'MISSING_FIELDS',
      });
    }

    const userRole = getUserRole(req);
    const result = await sendSetupConfirmationEmailService(userEmail, userRole, {
      to, preferenceId, preferenceDetails,
      jobRole, location, experience, skills, remote, datePosted, intervalMinutes,
      deviceId,
    });

    if (result.error) {
      log('error', 'setup_email_send_failed', {
        requestLogId, endpoint, httpStatus: result.statusCode,
        userEmail, preferenceId, errorCode: result.errorCode || 'UNKNOWN',
      });
      return res.status(result.statusCode).json({
        success: false, requestId: req.requestId || undefined,
        emailSent: result.emailSent || false, setupEmailSent: result.setupEmailSent || false,
        message: result.message, errorCode: result.errorCode || 'EMAIL_SEND_FAILED',
      });
    }

    log('info', 'setup_email_sent_success', {
      requestLogId, endpoint, httpStatus: 200, userEmail,
      to: maskEmail(to), preferenceId, errorCode: result.errorCode,
    });

    return sendSuccess(res, {
      emailSent: true, setupEmailSent: true,
      errorCode: result.errorCode, message: result.message,
    });
  } catch (err) {
    log('error', 'setup_email_unexpected_error', {
      requestLogId, endpoint, httpStatus: 500, errorCode: 'UNEXPECTED_ERROR',
      error: err.message,
      stack: process.env.NODE_ENV === 'development' ? err.stack : undefined,
    });
    return sendServerError(res, 'Failed to send setup confirmation email.', 'UNEXPECTED_ERROR');
  }
}
