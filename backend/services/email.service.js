import nodemailer from 'nodemailer';
import { randomBytes } from 'crypto';
import { maskEmail } from '../shared/utils/logMasker.js';

const MAX_RETRIES = 3;
const RETRY_BASE_DELAY_MS = 1000;

let transporter = null;

/**
 * Get or create the shared nodemailer transporter.
 * Uses Gmail SMTP with App Password.
 * Never logs the password.
 */
function getTransporter() {
  if (transporter) return transporter;

  const user = process.env.SERVER_EMAIL_USERNAME;
  const pass = process.env.SERVER_EMAIL_PASSWORD;

  if (!user || !pass) {
    console.warn('[Email] SERVER_EMAIL_USERNAME or SERVER_EMAIL_PASSWORD not set — email disabled');
    return null;
  }

  transporter = nodemailer.createTransport({
    service: 'gmail',
    auth: { user, pass },
  });

  return transporter;
}

function sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

/**
 * Verify the email transport is configured and can connect to SMTP.
 * Logs readiness without leaking credentials.
 * Safe to call at startup — does not send a test email.
 * @returns {Promise<{configured: boolean, verifiable: boolean, error?: string}>}
 */
export async function verifyEmailTransport() {
  const user = process.env.SERVER_EMAIL_USERNAME;
  const pass = process.env.SERVER_EMAIL_PASSWORD;

  if (!user || !pass) {
    return { configured: false, verifiable: false, error: 'SERVER_EMAIL_USERNAME or SERVER_EMAIL_PASSWORD not set' };
  }

  const hasAppPasswordFormat = pass.length >= 16 && /^[a-z]{16}$/.test(pass);
  const hasLongFormat = pass.length >= 16;

  try {
    const testTransporter = nodemailer.createTransport({
      service: 'gmail',
      auth: { user, pass },
    });
    const verified = await testTransporter.verify();
    testTransporter.close();
    if (verified) {
      console.log(`[Email] SMTP transport verified ✓ (user: ${maskEmail(user)})`);
      return { configured: true, verifiable: true };
    }
    console.warn(`[Email] SMTP verify() returned false for ${maskEmail(user)}`);
    return { configured: true, verifiable: false, error: 'SMTP verify() returned false' };
  } catch (err) {
    const msg = err.message || 'unknown error';
    console.warn(`[Email] SMTP transport verification failed for ${maskEmail(user)}: ${msg}`);
    if (!hasAppPasswordFormat && hasLongFormat) {
      console.warn('[Email] Hint: Password looks long but may not be a valid Gmail App Password.');
      console.warn('[Email] Gmail App Passwords are exactly 16 lowercase letters with no spaces.');
    }
    if (!hasLongFormat) {
      console.warn('[Email] Hint: Password seems too short for a Gmail App Password (expected 16 chars).');
      console.warn('[Email] Generate one at: https://myaccount.google.com/apppasswords');
    }
    if (msg.includes('Invalid login') || msg.includes('Application-specific password required')) {
      console.warn('[Email] This is NOT a regular Gmail password. You MUST use a Gmail App Password.');
      console.warn('[Email] 1. Enable 2FA at https://myaccount.google.com/security');
      console.warn('[Email] 2. Go to https://myaccount.google.com/apppasswords');
      console.warn('[Email] 3. Generate an App Password (16 lowercase letters)');
    }
    return { configured: true, verifiable: false, error: msg };
  }
}

/**
 * Get email config status for startup diagnostics (no secrets exposed).
 */
export function getEmailConfigStatus() {
  const user = process.env.SERVER_EMAIL_USERNAME;
  const pass = process.env.SERVER_EMAIL_PASSWORD;
  if (!user) return { configured: false, reason: 'SERVER_EMAIL_USERNAME not set' };
  if (!pass) return { configured: false, reason: 'SERVER_EMAIL_PASSWORD not set' };
  return {
    configured: true,
    user: maskEmail(user),
    hasPassword: pass.length > 0,
    passwordLength: pass.length,
    looksLikeAppPassword: /^[a-z]{16}$/.test(pass),
  };
}

/**
 * Send an email notification.
 *
 * @param {Object} options
 * @param {string} options.to - Recipient email
 * @param {string} options.subject - Email subject
 * @param {string} options.html - HTML email body
 * @param {string} [options.text] - Plain text fallback
 * @param {Array<{filename: string, content: Buffer|string}>} [options.attachments]
 * @returns {Promise<{success: boolean, messageId?: string, error?: string, errorCode?: string}>}
 */
function classifyEmailError(err) {
  if (!err || !err.message) return 'EMAIL_SEND_FAILED';
  const msg = err.message;
  if (msg.includes('Invalid login') || msg.includes('Application-specific password required')) return 'SMTP_AUTH_FAILED';
  if (msg.includes('connect') || msg.includes('ECONN')) return 'SMTP_CONNECT_FAILED';
  if (msg.includes('quota') || msg.includes('rate limit') || msg.includes('421')) return 'SMTP_RATE_LIMITED';
  if (msg.includes('ENOTFOUND') || msg.includes('DNS')) return 'SMTP_DNS_FAILED';
  if (msg.includes('ETIMEDOUT') || msg.includes('timeout')) return 'SMTP_TIMEOUT';
  if (msg.includes('Message blocked') || msg.includes('spam')) return 'SMTP_BLOCKED';
  return 'EMAIL_SEND_FAILED';
}

export async function sendEmail({ to, subject, html, text, attachments }) {
  const t = getTransporter();
  if (!t) {
    return { success: false, error: 'Email not configured on server', errorCode: 'EMAIL_ENV_MISSING' };
  }

  if (!to || !subject || !html) {
    return { success: false, error: 'Missing required fields: to, subject, html', errorCode: 'MISSING_FIELDS' };
  }

  let lastError = null;

  for (let attempt = 1; attempt <= MAX_RETRIES; attempt++) {
    try {
      const mailOptions = {
        from: `"FundoCareer" <${process.env.SERVER_EMAIL_USERNAME}>`,
        to,
        subject,
        html,
        text: text || '',
      };

      if (Array.isArray(attachments) && attachments.length > 0) {
        mailOptions.attachments = attachments.map(a => {
          const entry = { filename: a.filename, content: a.content };
          if (a.contentType) entry.contentType = a.contentType;
          return entry;
        });
      }

      const info = await t.sendMail(mailOptions);

      const logId = randomBytes(4).toString('hex');
      console.log(JSON.stringify({
        event: 'email_sent',
        logId,
        to: maskEmail(to),
        messageId: info.messageId,
        subject: subject.substring(0, 80),
        attachmentCount: attachments?.length || 0,
        attempt,
        timestamp: new Date().toISOString(),
      }));
      return { success: true, messageId: info.messageId };
    } catch (err) {
      lastError = err;
      const errorCode = classifyEmailError(err);
      console.error(JSON.stringify({
        event: 'email_failed',
        to: maskEmail(to),
        error: err.message,
        errorCode,
        attempt,
        maxRetries: MAX_RETRIES,
        timestamp: new Date().toISOString(),
      }));

      if (attempt < MAX_RETRIES) {
        const delay = RETRY_BASE_DELAY_MS * Math.pow(2, attempt - 1);
        console.log(JSON.stringify({
          event: 'email_retry',
          to: maskEmail(to),
          attempt,
          errorCode,
          nextAttemptDelayMs: delay,
          timestamp: new Date().toISOString(),
        }));
        await sleep(delay);
      }
    }
  }

  const errorMessage = lastError ? lastError.message : 'All retry attempts exhausted';
  const errorCode = classifyEmailError(lastError);
  return { success: false, error: errorMessage, errorCode };
}
