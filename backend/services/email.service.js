import nodemailer from 'nodemailer';
import { randomBytes } from 'crypto';
import { maskEmail } from '../shared/utils/logMasker.js';

const MAX_RETRIES = 3;
const RETRY_BASE_DELAY_MS = 1000;

let transporter = null;

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
 * Send an email notification about new job matches.
 *
 * @param {Object} options
 * @param {string} options.to - Recipient email
 * @param {string} options.subject - Email subject
 * @param {string} options.html - HTML email body
 * @param {string} [options.text] - Plain text fallback
 * @param {Array<{filename: string, content: Buffer|string}>} [options.attachments]
 * @returns {Promise<{success: boolean, messageId?: string, error?: string}>}
 */
export async function sendEmail({ to, subject, html, text, attachments }) {
  const t = getTransporter();
  if (!t) {
    return { success: false, error: 'Email not configured on server' };
  }

  if (!to || !subject || !html) {
    return { success: false, error: 'Missing required fields: to, subject, html' };
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
      console.error(JSON.stringify({
        event: 'email_failed',
        to: maskEmail(to),
        error: err.message,
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
          nextAttemptDelayMs: delay,
          timestamp: new Date().toISOString(),
        }));
        await sleep(delay);
      }
    }
  }

  return { success: false, error: lastError ? lastError.message : 'All retry attempts exhausted' };
}
