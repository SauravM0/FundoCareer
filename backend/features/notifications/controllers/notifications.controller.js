import prisma from '../../../config/database.config.js';
import { sendEmail } from '../../../services/email.service.js';
import { randomBytes } from 'crypto';
import { generateJobAlertPdf } from '../services/jobAlertPdf.service.js';
import { normalizeFingerprint } from '../../../shared/services/fingerprint.service.js';
import { maskDeviceId, maskEmail } from '../../../shared/utils/logMasker.js';

function now() { return new Date(); }

function getUserEmail(req) {
  return req.user?.email || null;
}

function getUserRole(req) {
  return req.user?.role || 'user';
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
// SEND JOB ALERT EMAIL
// POST /api/notifications/send-email
// ================================================================
export async function sendJobAlertEmail(req, res) {
  const requestLogId = randomId();
  try {
    const userEmail = getUserEmail(req);
    if (!userEmail) {
      log('error', 'email_request_no_auth', { requestLogId });
      return res.status(401).json({ success: false, message: 'User email not found in token.' });
    }

    const {
      to,
      preferenceId,
      preferenceName,
      newJobs,
      allJobs,
      deviceId,
      runId,
    } = req.body;

    log('info', 'email_request_received', {
      requestLogId,
      userEmail,
      to: maskEmail(to),
      preferenceId,
      preferenceName: (preferenceName || preferenceId || '').substring(0, 60),
      newJobsCount: Array.isArray(newJobs) ? newJobs.length : 0,
      allJobsCount: typeof allJobs === 'number' ? allJobs : 0,
      deviceId: maskDeviceId(deviceId || null),
      runId: runId || null,
    });

    if (!to || !preferenceId) {
      log('warn', 'email_validation_failed', { requestLogId, reason: 'Missing to or preferenceId' });
      return res.status(400).json({ success: false, message: 'Missing required fields: to, preferenceId.' });
    }

    const role = getUserRole(req);
    if (to !== userEmail && role !== 'admin') {
      log('warn', 'email_recipient_mismatch', { requestLogId, userEmail, requestedTo: maskEmail(to) });
      return res.status(403).json({ success: false, message: 'Recipient email must match authenticated user.' });
    }

    if (!Array.isArray(newJobs) || newJobs.length === 0) {
      log('warn', 'email_no_new_jobs', { requestLogId, preferenceId });
      return res.status(400).json({ success: false, message: 'No new jobs to email.' });
    }

    if (newJobs.length > 50) {
      log('warn', 'email_too_many_jobs', { requestLogId, count: newJobs.length });
      return res.status(400).json({ success: false, message: 'Too many jobs in single email (max 50).' });
    }

    const subject = `FundoCareer — ${newJobs.length} new job${newJobs.length > 1 ? 's' : ''} for "${escapeHtml(preferenceName || preferenceId || 'your search')}"`;

    const jobCards = newJobs.map(job => {
      const safeTitle = escapeHtml(job.jobTitle || 'Untitled Position');
      const safeCompany = escapeHtml(job.company || '');
      const safeLocation = escapeHtml(job.location || '');
      const safeSalary = escapeHtml(job.salary || '');
      const safeSource = escapeHtml(job.source || '');
      const safePosted = escapeHtml(job.postedDate || '');
      const safeDescription = escapeHtml(truncateText(job.description, 250));
      const applyUrl = isSafeUrl(job.url) ? job.url : null;
      const titleName = safeTitle;

      const metaParts = [];
      if (safeCompany) metaParts.push(safeCompany);
      if (safeLocation) metaParts.push(safeLocation);
      const metaLine = metaParts.join(' &middot; ');

      const detailParts = [];
      if (safeSource) detailParts.push(safeSource);
      if (safePosted) detailParts.push(safePosted);
      if (safeSalary) detailParts.push(safeSalary);
      const detailLine = detailParts.join(' &middot; ');

      return `
        <table width="100%" cellpadding="0" cellspacing="0" role="presentation" style="border:1px solid #e0e0e0;border-radius:8px;margin-bottom:12px">
          <tr>
            <td style="padding:16px">
              <h2 style="margin:0 0 4px;color:#1a73e8;font-size:16px;font-weight:600">${titleName}</h2>
              ${metaLine ? `<p style="margin:0 0 6px;color:#202124;font-size:14px">${metaLine}</p>` : ''}
              ${detailLine ? `<p style="margin:0 0 8px;color:#5f6368;font-size:13px">${detailLine}</p>` : ''}
              ${safeDescription ? `<p style="margin:0 0 12px;color:#3c4043;font-size:13px;line-height:1.4">${safeDescription}</p>` : ''}
              ${applyUrl ? `<a href="${applyUrl}" target="_blank" style="display:inline-block;background:#1a73e8;color:#ffffff;padding:8px 20px;border-radius:6px;text-decoration:none;font-size:14px;font-weight:500">Apply</a>` : ''}
            </td>
          </tr>
        </table>`;
    }).join('');

    const html = `<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width,initial-scale=1.0">
  <!--[if mso]><xml><o:OfficeDocumentSettings><o:PixelsPerInch>96</o:PixelsPerInch></o:OfficeDocumentSettings></xml><![endif]-->
</head>
<body style="margin:0;padding:0;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;background-color:#f4f6f8">
  <div style="display:none;font-size:1px;color:#f4f6f8;line-height:1px;max-height:0;max-width:0;opacity:0;overflow:hidden;mso-hide:all">
    ${newJobs.length} new job${newJobs.length > 1 ? 's' : ''} matching "${escapeHtml(preferenceName || preferenceId || 'your search')}"
  </div>
  <table width="100%" cellpadding="0" cellspacing="0" role="presentation" style="background-color:#f4f6f8">
    <tr>
      <td align="center" style="padding:24px 16px">
        <!--[if mso]><table width="600" cellpadding="0" cellspacing="0"><tr><td><![endif]-->
        <table width="100%" cellpadding="0" cellspacing="0" role="presentation" style="max-width:600px;margin:0 auto;background-color:#ffffff;border-radius:8px;overflow:hidden">

          <!-- HEADER -->
          <tr>
            <td style="background-color:#1a73e8;padding:28px 24px;text-align:center">
              <h1 style="margin:0;color:#ffffff;font-size:22px;font-weight:700;letter-spacing:-0.3px">FundoCareer Job Alerts</h1>
            </td>
          </tr>

          <!-- INTRO -->
          <tr>
            <td style="padding:24px 24px 12px">
              <p style="margin:0 0 6px;color:#202124;font-size:16px;line-height:1.4">Here are the latest jobs matching your saved preference.</p>
              <p style="margin:0 0 4px;color:#5f6368;font-size:14px">Showing up to 50 matching jobs.</p>
              <p style="margin:0 0 4px;color:#5f6368;font-size:14px;font-weight:500">
                ${newJobs.length} new job${newJobs.length > 1 ? 's' : ''} for
                &quot;${escapeHtml(preferenceName || preferenceId || 'your search')}&quot;
              </p>
              ${allJobs > newJobs.length ? `<p style="margin:8px 0 0;color:#80868b;font-size:13px">${allJobs - newJobs.length} of ${allJobs} jobs were previously seen and are not included.</p>` : ''}
            </td>
          </tr>

          <!-- JOB LIST -->
          <tr>
            <td style="padding:8px 24px 24px">
              ${jobCards}
            </td>
          </tr>

          <!-- FOOTER -->
          <tr>
            <td style="padding:16px 24px;background-color:#f8f9fa;text-align:center;color:#80868b;font-size:12px;line-height:1.5">
              <p style="margin:0 0 4px">You are receiving this because you enabled job alerts in FundoCareer.</p>
            </td>
          </tr>

        </table>
        <!--[if mso]></td></tr></table><![endif]-->
      </td>
    </tr>
  </table>
</body>
</html>`;

    // Generate PDF attachment server-side
    let pdfBuffer = null;
    let pdfGenerated = false;
    try {
      const pdfResult = await generateJobAlertPdf({
        preferenceName: preferenceName || preferenceId,
        newJobs,
        generatedAt: new Date(),
      });
      if (pdfResult.success) {
        pdfBuffer = pdfResult.buffer;
        pdfGenerated = true;
      } else {
        log('warn', 'email_pdf_generation_failed', { requestLogId, error: pdfResult.error });
      }
    } catch (pdfErr) {
      log('warn', 'email_pdf_generation_error', { requestLogId, error: pdfErr.message });
    }

    const attachments = pdfBuffer
      ? [{ filename: 'FundoCareer-Job-Alert-Report.pdf', content: pdfBuffer, contentType: 'application/pdf' }]
      : [];

    const emailResult = await sendEmail({ to, subject, html, attachments });

    if (!emailResult.success) {
      log('error', 'email_send_failed', {
        requestLogId,
        userEmail,
        preferenceId,
        error: emailResult.error,
        pdfGenerated,
      });
      return res.status(500).json({
        success: false,
        emailSent: false,
        message: 'Unable to send job alert email right now.',
      });
    }

    // Record normalized fingerprints to prevent re-sending
    const fingerprints = newJobs
      .filter(j => j.fingerprint)
      .map(j => ({
        userEmail,
        preferenceId,
        fingerprint: normalizeFingerprint(j.fingerprint),
        emailedAt: now(),
        emailRecordId: emailResult.messageId || null,
      }))
      .filter(f => f.fingerprint.length > 0);

    if (fingerprints.length > 0) {
      await prisma.emailedJobFingerprint.createMany({
        data: fingerprints,
        skipDuplicates: true,
      });
    }

    // Record positive-only email history
    try {
      await prisma.emailSentHistory.create({
        data: {
          userEmail,
          schedulerPreferenceId: preferenceId,
          emailSentAt: now(),
          jobsSentCount: newJobs.length,
          pdfAttached: pdfGenerated,
          searchTitle: preferenceName || null,
        },
      });
    } catch (histErr) {
      log('warn', 'email_history_write_failed', { requestLogId, error: histErr.message });
    }

    // Update lastJobEmailAt on the active device for this user
    if (deviceId) {
      try {
        await prisma.schedulerDeviceState.updateMany({
          where: { userEmail, isActiveDevice: true, deviceId },
          data: { lastJobEmailAt: now() },
        });
      } catch (updateErr) {
        log('warn', 'email_lastJobEmailAt_update_failed', { requestLogId, error: updateErr.message });
      }
    }

    log('info', 'email_sent_success', {
      requestLogId,
      userEmail,
      to: maskEmail(to),
      preferenceId,
      runId: runId || null,
      messageId: emailResult.messageId,
      jobsInEmail: newJobs.length,
      fingerprintsRecorded: fingerprints.length,
      pdfAttached: pdfGenerated,
    });

    return res.json({
      success: true,
      emailSent: true,
      jobEmailSent: true,
      jobsSent: newJobs.length,
      pdfAttached: pdfGenerated,
    });
  } catch (err) {
    log('error', 'email_unexpected_error', {
      requestLogId,
      error: err.message,
      stack: process.env.NODE_ENV === 'development' ? err.stack : undefined,
    });
    return res.status(500).json({ success: false, message: 'Failed to send email.' });
  }
}

// ================================================================
// SEND SETUP CONFIRMATION EMAIL
// POST /api/notifications/send-setup-email
// ================================================================
export async function sendSetupConfirmationEmail(req, res) {
  const requestLogId = randomId();
  try {
    const userEmail = getUserEmail(req);
    if (!userEmail) {
      log('error', 'setup_email_no_auth', { requestLogId });
      return res.status(401).json({ success: false, message: 'User email not found in token.' });
    }

    const { to, preferenceId, preferenceDetails, role: jobRole, location, experience, skills, remote, datePosted, intervalMinutes } = req.body;

    log('info', 'setup_email_request_received', {
      requestLogId, userEmail, to: maskEmail(to), preferenceId,
    });

    if (!to || !preferenceId) {
      return res.status(400).json({ success: false, message: 'Missing required fields: to, preferenceId.' });
    }

    const userRole = getUserRole(req);
    if (to !== userEmail && userRole !== 'admin') {
      return res.status(403).json({ success: false, message: 'Recipient email must match authenticated user.' });
    }

    let deviceName = 'Unknown Device';
    let devicePlatform = '';
    try {
      const activeDevice = await prisma.schedulerDeviceState.findFirst({
        where: { userEmail, isActiveDevice: true },
        select: { deviceName: true, devicePlatform: true },
      });
      if (activeDevice) {
        deviceName = activeDevice.deviceName || 'Unknown Device';
        devicePlatform = activeDevice.devicePlatform || '';
      }
    } catch (queryErr) {
      log('warn', 'setup_email_device_query_failed', { requestLogId, error: queryErr.message });
    }

    const detailsRows = [];
    const addRow = (label, value) => {
      if (value != null && value !== '') {
        detailsRows.push(`<tr><td style="padding:6px 12px;font-weight:600;color:#374151;font-size:13px;border-bottom:1px solid #e5e7eb;white-space:nowrap">${escapeHtml(label)}</td><td style="padding:6px 12px;color:#374151;font-size:13px;border-bottom:1px solid #e5e7eb">${escapeHtml(String(value))}</td></tr>`);
      }
    };

    if (jobRole || location || experience || skills || remote !== undefined || datePosted || intervalMinutes) {
      addRow('Role', jobRole);
      addRow('Location', location);
      addRow('Experience', experience);
      addRow('Skills', skills);
      if (remote !== undefined) addRow('Remote', remote ? 'Yes' : 'No');
      addRow('Date Posted', datePosted);
      addRow('Check Interval', intervalMinutes ? `${intervalMinutes} minutes` : null);
      addRow('Max Jobs Per Email', '50');
    } else if (preferenceDetails) {
      preferenceDetails.split('\n').forEach(line => {
        const colonIdx = line.indexOf(':');
        if (colonIdx > 0) {
          addRow(line.substring(0, colonIdx).trim(), line.substring(colonIdx + 1).trim());
        }
      });
    }

    addRow('Managing Device', devicePlatform ? `${deviceName} (${devicePlatform})` : deviceName);

    const tableHtml = detailsRows.length > 0
      ? `<table width="100%" cellpadding="0" cellspacing="0" style="background:#f9fafb;border:1px solid #e5e7eb;border-radius:8px;margin-bottom:16px">${detailsRows.join('')}</table>`
      : '';

    const subject = 'FundoCareer — Job Search Preferences Set Successfully';

    const html = `<!DOCTYPE html>
<html>
<head><meta charset="utf-8"></head>
<body style="margin:0;padding:0;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif">
  <table width="100%" cellpadding="0" cellspacing="0"><tr><td style="padding:24px 16px;background:#f3f4f6">
    <table width="600" cellpadding="0" cellspacing="0" style="margin:0 auto;background:#fff;border-radius:12px;overflow:hidden">
      <tr><td style="padding:24px 32px;background:#2563eb;text-align:center">
        <h1 style="margin:0;color:#fff;font-size:20px">FundoCareer Job Search</h1>
      </td></tr>
      <tr><td style="padding:24px 32px">
        <p style="margin:0 0 16px;color:#111827;font-size:15px">Your job search preferences have been set successfully!</p>
        ${tableHtml}
        <p style="margin:0 0 16px;color:#6b7280;font-size:13px">Your scheduler is now active. You will receive email notifications when new matching jobs are found.</p>
      </td></tr>
      <tr><td style="padding:16px 32px;background:#f9fafb;text-align:center;color:#9ca3af;font-size:12px">
        FundoCareer &middot; Powered by AI
      </td></tr>
    </table>
  </td></tr></table>
</body>
</html>`;

    const emailResult = await sendEmail({ to, subject, html });

    if (!emailResult.success) {
      log('error', 'setup_email_send_failed', {
        requestLogId, userEmail, preferenceId, error: emailResult.error,
      });
      return res.status(500).json({ success: false, message: 'Failed to send confirmation email' });
    }

    try {
      await prisma.schedulerDeviceState.updateMany({
        where: { userEmail, isActiveDevice: true },
        data: { lastSetupEmailAt: now() },
      });
    } catch (updateErr) {
      log('warn', 'setup_email_lastSetupEmailAt_update_failed', { requestLogId, error: updateErr.message });
    }

    log('info', 'setup_email_sent_success', {
      requestLogId, userEmail, to: maskEmail(to), preferenceId, messageId: emailResult.messageId,
    });

    return res.json({
      success: true,
      setupEmailSent: true,
    });
  } catch (err) {
    log('error', 'setup_email_unexpected_error', {
      requestLogId, error: err.message,
      stack: process.env.NODE_ENV === 'development' ? err.stack : undefined,
    });
    return res.status(500).json({ success: false, message: 'Failed to send setup confirmation email.' });
  }
}

function escapeHtml(str) {
  if (!str) return '';
  return String(str)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#039;');
}

function isSafeUrl(url) {
  if (!url) return false;
  try {
    const parsed = new URL(url);
    return ['http:', 'https:'].includes(parsed.protocol);
  } catch {
    return false;
  }
}

function truncateText(str, maxLen) {
  if (!str) return '';
  const s = String(str).trim();
  if (s.length <= maxLen) return s;
  return s.substring(0, maxLen).replace(/\s+\S*$/, '') + '…';
}

function randomId() {
  return randomBytes(4).toString('hex');
}