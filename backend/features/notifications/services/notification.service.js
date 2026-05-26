import prisma from '../../../config/database.config.js';
import { sendEmail } from '../../../services/email.service.js';
import { generateJobAlertPdf } from './jobAlertPdf.service.js';
import { normalizeFingerprint } from '../../../shared/services/fingerprint.service.js';

export function now() { return new Date(); }

export function getUserEmail(req) {
  return req.user?.email || null;
}

export function getUserRole(req) {
  return req.user?.role || 'user';
}

export function escapeHtml(str) {
  if (!str) return '';
  return String(str)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#039;');
}

export function isSafeUrl(url) {
  if (!url) return false;
  try {
    const parsed = new URL(url);
    return ['http:', 'https:'].includes(parsed.protocol);
  } catch {
    return false;
  }
}

export function truncateText(str, maxLen) {
  if (!str) return '';
  const s = String(str).trim();
  if (s.length <= maxLen) return s;
  return s.substring(0, maxLen).replace(/\s+\S*$/, '') + '…';
}

function buildNoJobsHtml(preferenceName, preferenceId) {
  return `<!DOCTYPE html>
<html>
<head><meta charset="utf-8"></head>
<body style="margin:0;padding:0;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif">
  <table width="100%" cellpadding="0" cellspacing="0"><tr><td style="padding:24px 16px;background:#f3f4f6">
    <table width="600" cellpadding="0" cellspacing="0" style="margin:0 auto;background:#fff;border-radius:12px;overflow:hidden">
      <tr><td style="padding:24px 32px;background:#2563eb;text-align:center">
        <h1 style="margin:0;color:#fff;font-size:20px">No New Jobs This Time</h1>
      </td></tr>
      <tr><td style="padding:24px 32px">
        <p style="margin:0 0 16px;color:#111827;font-size:15px">No new matching jobs were found for your saved preference.</p>
        <p style="margin:0 0 16px;color:#6b7280;font-size:13px">
          Preference: <strong>${escapeHtml(preferenceName || preferenceId || 'your search')}</strong>
        </p>
        <p style="margin:0;color:#6b7280;font-size:13px">We will keep searching and notify you when new matching jobs appear.</p>
      </td></tr>
      <tr><td style="padding:16px 32px;background:#f9fafb;text-align:center;color:#9ca3af;font-size:12px">
        FundoCareer &middot; Powered by AI
      </td></tr>
    </table>
  </td></tr></table>
</body>
</html>`;
}

function buildJobsHtml(newJobs, preferenceName, preferenceId, allJobs) {
  const jobCards = newJobs.map(job => {
    const safeTitle = escapeHtml(job.jobTitle || 'Untitled Position');
    const safeCompany = escapeHtml(job.company || '');
    const safeLocation = escapeHtml(job.location || '');
    const safeSalary = escapeHtml(job.salary || '');
    const safeSource = escapeHtml(job.source || '');
    const safePosted = escapeHtml(job.postedDate || '');
    const safeDescription = escapeHtml(truncateText(job.description, 250));
    const applyUrl = isSafeUrl(job.url) ? job.url : null;

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
          <h2 style="margin:0 0 4px;color:#1a73e8;font-size:16px;font-weight:600">${safeTitle}</h2>
          ${metaLine ? `<p style="margin:0 0 6px;color:#202124;font-size:14px">${metaLine}</p>` : ''}
          ${detailLine ? `<p style="margin:0 0 8px;color:#5f6368;font-size:13px">${detailLine}</p>` : ''}
          ${safeDescription ? `<p style="margin:0 0 12px;color:#3c4043;font-size:13px;line-height:1.4">${safeDescription}</p>` : ''}
          ${applyUrl ? `<a href="${applyUrl}" target="_blank" style="display:inline-block;background:#1a73e8;color:#ffffff;padding:8px 20px;border-radius:6px;text-decoration:none;font-size:14px;font-weight:500">Apply</a>` : ''}
        </td>
      </tr>
    </table>`;
  }).join('');

  return `<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width,initial-scale=1.0">
</head>
<body style="margin:0;padding:0;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;background-color:#f4f6f8">
  <div style="display:none;font-size:1px;color:#f4f6f8;line-height:1px;max-height:0;max-width:0;opacity:0;overflow:hidden;mso-hide:all">
    ${newJobs.length} new job${newJobs.length > 1 ? 's' : ''} matching "${escapeHtml(preferenceName || preferenceId || 'your search')}"
  </div>
  <table width="100%" cellpadding="0" cellspacing="0" role="presentation" style="background-color:#f4f6f8">
    <tr>
      <td align="center" style="padding:24px 16px">
        <table width="100%" cellpadding="0" cellspacing="0" role="presentation" style="max-width:600px;margin:0 auto;background-color:#ffffff;border-radius:8px;overflow:hidden">
          <tr>
            <td style="background-color:#1a73e8;padding:28px 24px;text-align:center">
              <h1 style="margin:0;color:#ffffff;font-size:22px;font-weight:700;letter-spacing:-0.3px">FundoCareer Job Alerts</h1>
            </td>
          </tr>
          <tr>
            <td style="padding:24px 24px 12px">
              <p style="margin:0 0 6px;color:#202124;font-size:16px;line-height:1.4">Here are the latest jobs matching your saved preference.</p>
              <p style="margin:0 0 4px;color:#5f6368;font-size:14px;font-weight:500">
                ${newJobs.length} new job${newJobs.length > 1 ? 's' : ''} for
                &quot;${escapeHtml(preferenceName || preferenceId || 'your search')}&quot;
              </p>
              ${allJobs > newJobs.length ? `<p style="margin:8px 0 0;color:#80868b;font-size:13px">${allJobs - newJobs.length} of ${allJobs} jobs were previously seen and are not included.</p>` : ''}
            </td>
          </tr>
          <tr>
            <td style="padding:8px 24px 24px">
              ${jobCards}
            </td>
          </tr>
          <tr>
            <td style="padding:16px 24px;background-color:#f8f9fa;text-align:center;color:#80868b;font-size:12px;line-height:1.5">
              <p style="margin:0 0 4px">You are receiving this because you enabled job alerts in FundoCareer.</p>
            </td>
          </tr>
        </table>
      </td>
    </tr>
  </table>
</body>
</html>`;
}

function buildSetupEmailHtml({ detailsRows, deviceName, devicePlatform }) {
  const tableHtml = detailsRows.length > 0
    ? `<table width="100%" cellpadding="0" cellspacing="0" style="background:#f9fafb;border:1px solid #e5e7eb;border-radius:8px;margin-bottom:16px">${detailsRows.join('')}</table>`
    : '';

  return `<!DOCTYPE html>
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
}

export function buildEmailContent(newJobs, preferenceName, preferenceId, allJobs) {
  if (!Array.isArray(newJobs) || newJobs.length === 0) {
    return {
      subject: `FundoCareer — No new matching jobs for "${escapeHtml(preferenceName || preferenceId || 'your search')}"`,
      html: buildNoJobsHtml(preferenceName, preferenceId),
    };
  }

  return {
    subject: `FundoCareer — ${newJobs.length} new job${newJobs.length > 1 ? 's' : ''} for "${escapeHtml(preferenceName || preferenceId || 'your search')}"`,
    html: buildJobsHtml(newJobs, preferenceName, preferenceId, allJobs),
  };
}

// ================================================================
// SEND JOB ALERT EMAIL SERVICE
// ================================================================

export async function sendJobAlertEmailService(userEmail, role, {
  to, preferenceId, preferenceName, newJobs, allJobs, deviceId, runId,
}) {
  const jobCount = Array.isArray(newJobs) ? newJobs.length : 0;

  if (to !== userEmail && role !== 'admin') {
    return { error: true, statusCode: 403, errorCode: 'RECIPIENT_MISMATCH', message: 'Recipient email must match authenticated user.' };
  }

  if (!Array.isArray(newJobs)) {
    return { error: true, statusCode: 400, errorCode: 'INVALID_NEWJOBS', message: 'newJobs must be an array.' };
  }

  if (newJobs.length > 50) {
    return { error: true, statusCode: 400, errorCode: 'TOO_MANY_JOBS', message: 'Too many jobs in single email (max 50).' };
  }

  const { subject, html } = buildEmailContent(newJobs, preferenceName, preferenceId, allJobs);

  let pdfBuffer = null;
  let pdfGenerated = false;

  if (newJobs.length > 0) {
    try {
      const pdfResult = await generateJobAlertPdf({
        preferenceName: preferenceName || preferenceId,
        newJobs,
        generatedAt: new Date(),
      });
      if (pdfResult.success) {
        pdfBuffer = pdfResult.buffer;
        pdfGenerated = true;
      }
    } catch (pdfErr) {
      console.warn(JSON.stringify({ event: 'email_pdf_generation_error', error: pdfErr.message }));
    }
  }

  const attachments = pdfBuffer
    ? [{ filename: 'FundoCareer-Job-Alert-Report.pdf', content: pdfBuffer, contentType: 'application/pdf' }]
    : [];

  const emailResult = await sendEmail({ to, subject, html, attachments });

  if (!emailResult.success) {
    return {
      error: true, statusCode: 500,
      emailSent: false, jobsSent: 0,
      message: 'Unable to send job alert email right now.',
      errorCode: emailResult.errorCode || 'EMAIL_SEND_FAILED',
    };
  }

  if (newJobs.length > 0) {
    const fingerprints = newJobs
      .filter(j => j.fingerprint)
      .map(j => ({
        userEmail, preferenceId,
        fingerprint: normalizeFingerprint(j.fingerprint),
        emailedAt: now(),
        emailRecordId: emailResult.messageId || null,
      }))
      .filter(f => f.fingerprint.length > 0);

    if (fingerprints.length > 0) {
      try {
        await prisma.emailedJobFingerprint.createMany({
          data: fingerprints, skipDuplicates: true,
        });
      } catch (fpErr) {
        console.warn(JSON.stringify({ event: 'email_fingerprint_write_failed', error: fpErr.message }));
      }
    }

    try {
      await prisma.emailSentHistory.create({
        data: {
          userEmail, schedulerPreferenceId: preferenceId,
          emailSentAt: now(), jobsSentCount: newJobs.length,
          pdfAttached: pdfGenerated, searchTitle: preferenceName || null,
        },
      });
    } catch (histErr) {
      console.warn(JSON.stringify({ event: 'email_history_write_failed', error: histErr.message }));
    }
  }

  if (deviceId) {
    try {
      await prisma.schedulerDeviceState.updateMany({
        where: { userEmail, isActiveDevice: true, deviceId },
        data: { lastJobEmailAt: now() },
      });
    } catch (updateErr) {
      console.warn(JSON.stringify({ event: 'email_lastJobEmailAt_update_failed', error: updateErr.message }));
    }
  }

  return {
    success: true, emailSent: true,
    jobEmailSent: newJobs.length > 0,
    jobsSent: newJobs.length,
    pdfAttached: pdfGenerated,
    errorCode: newJobs.length === 0 ? 'ZERO_JOB_EMAIL_SENT' : 'EMAIL_SENT',
  };
}

// ================================================================
// SEND SETUP CONFIRMATION EMAIL SERVICE
// ================================================================

export async function sendSetupConfirmationEmailService(userEmail, role, {
  to, preferenceId, preferenceDetails,
  jobRole, location, experience, skills, remote, datePosted, intervalMinutes,
  deviceId,
}) {
  if (to !== userEmail && role !== 'admin') {
    return { error: true, statusCode: 403, errorCode: 'RECIPIENT_MISMATCH', message: 'Recipient email must match authenticated user.' };
  }

  if (deviceId) {
    const activeCheck = await prisma.schedulerDeviceState.findFirst({
      where: { userEmail, isActiveDevice: true },
      select: { deviceId: true },
    });
    if (!activeCheck) {
      return { error: true, statusCode: 403, errorCode: 'NO_ACTIVE_DEVICE', message: 'No active device found. Activate a device first.' };
    }
    if (activeCheck.deviceId !== deviceId) {
      return { error: true, statusCode: 403, errorCode: 'DEVICE_NOT_ACTIVE', message: 'Setup email can only be sent from the active device.' };
    }
  }

  let deviceName = 'Unknown Device';
  let deviceType = '';
  let lastSetupEmailAt = null;
  try {
    const activeDevice = await prisma.schedulerDeviceState.findFirst({
      where: { userEmail, isActiveDevice: true },
      select: { deviceName: true, deviceType: true, lastSetupEmailAt: true },
    });
    if (activeDevice) {
      deviceName = activeDevice.deviceName || 'Unknown Device';
      deviceType = activeDevice.deviceType || '';
      lastSetupEmailAt = activeDevice.lastSetupEmailAt;
    }
  } catch (queryErr) {
    console.warn(JSON.stringify({ event: 'setup_email_device_query_failed', error: queryErr.message }));
  }

  if (lastSetupEmailAt) {
    return {
      success: true, emailSent: true, setupEmailSent: true,
      errorCode: 'EMAIL_SENT',
      message: 'Setup confirmation email already sent.',
    };
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

  addRow('Managing Device', deviceType ? `${deviceName} (${deviceType})` : deviceName);

  const html = buildSetupEmailHtml({ detailsRows, deviceName, devicePlatform: deviceType });
  const subject = 'FundoCareer — Job Search Preferences Set Successfully';

  const emailResult = await sendEmail({ to, subject, html });

  if (!emailResult.success) {
    return {
      error: true, statusCode: 500,
      emailSent: false, setupEmailSent: false,
      message: 'Failed to send confirmation email',
      errorCode: emailResult.errorCode || 'EMAIL_SEND_FAILED',
    };
  }

  try {
    await prisma.schedulerDeviceState.updateMany({
      where: { userEmail, isActiveDevice: true },
      data: { lastSetupEmailAt: now() },
    });
  } catch (updateErr) {
    console.warn(JSON.stringify({ event: 'setup_email_lastSetupEmailAt_update_failed', error: updateErr.message }));
  }

  return {
    success: true, emailSent: true, setupEmailSent: true,
    errorCode: 'EMAIL_SENT', message: 'Setup confirmation email sent.',
  };
}
