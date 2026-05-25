import { generatePdfFromHtml } from '../../../shared/services/puppeteer.service.js';

function escapeHtml(str) {
  if (!str) return '';
  return String(str)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#039;');
}

function truncateText(str, maxLen) {
  if (!str) return '';
  const s = String(str).trim();
  if (s.length <= maxLen) return s;
  return s.substring(0, maxLen).replace(/\s+\S*$/, '') + '\u2026';
}

function formatDate(date) {
  const d = date || new Date();
  const year = d.getFullYear();
  const month = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  const hours = String(d.getHours()).padStart(2, '0');
  const minutes = String(d.getMinutes()).padStart(2, '0');
  return `${year}-${month}-${day} ${hours}:${minutes}`;
}

function buildJobAlertHtml({ preferenceName, newJobs, generatedAt }) {
  const jobs = (newJobs || []).slice(0, 50);
  const genDate = formatDate(generatedAt || new Date());
  const safeName = escapeHtml(preferenceName || '');

  const jobRows = jobs.map((job, idx) => {
    const title = escapeHtml(job.jobTitle || 'Untitled Position');
    const company = escapeHtml(job.company || '');
    const location = escapeHtml(job.location || '');
    const source = escapeHtml(job.source || '');
    const url = escapeHtml(job.url || '');
    const description = escapeHtml(truncateText(job.description, 300));

    const metaParts = [];
    if (company) metaParts.push(company);
    if (location) metaParts.push(location);
    const metaLine = metaParts.join(' \u00b7 ');

    const detailParts = [];
    if (source) detailParts.push(`Source: ${source}`);
    if (url) detailParts.push(`URL: ${url}`);
    const detailLine = detailParts.join('\n');

    return `
      <tr>
        <td class="job-num">${idx + 1}.</td>
        <td class="job-body">
          <div class="job-title">${title}</div>
          ${metaLine ? `<div class="job-meta">${metaLine}</div>` : ''}
          ${detailLine ? `<div class="job-detail">${detailLine.replace(/\n/g, '<br>')}</div>` : ''}
          ${description ? `<div class="job-desc">${description}</div>` : ''}
        </td>
      </tr>
      ${idx < jobs.length - 1 ? '<tr><td colspan="2"><hr></td></tr>' : ''}`;
  }).join('');

  return `<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<style>
  * { margin: 0; padding: 0; box-sizing: border-box; }
  body {
    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif;
    font-size: 10pt;
    color: #202124;
    line-height: 1.5;
    padding: 0;
  }
  .container { max-width: 100%; }
  .header {
    border-bottom: 2px solid #1a73e8;
    padding-bottom: 12px;
    margin-bottom: 16px;
  }
  .header h1 {
    color: #1a73e8;
    font-size: 18pt;
    font-weight: 700;
    margin-bottom: 4px;
  }
  .header .meta {
    color: #5f6368;
    font-size: 9pt;
  }
  .summary {
    background: #f8f9fa;
    border: 1px solid #e0e0e0;
    border-radius: 6px;
    padding: 12px 16px;
    margin-bottom: 16px;
  }
  .summary-title {
    font-weight: 600;
    color: #202124;
    font-size: 10pt;
    margin-bottom: 4px;
  }
  .summary-row {
    color: #5f6368;
    font-size: 9pt;
    margin-top: 2px;
  }
  .count-badge {
    display: inline-block;
    background: #e8f0fe;
    color: #1a73e8;
    font-weight: 600;
    font-size: 9pt;
    padding: 2px 10px;
    border-radius: 10px;
    margin-top: 6px;
  }
  table.jobs { width: 100%; border-collapse: collapse; }
  td.job-num {
    width: 28px;
    vertical-align: top;
    font-weight: 600;
    color: #5f6368;
    font-size: 10pt;
    padding: 4px 8px 4px 0;
  }
  td.job-body {
    vertical-align: top;
    padding: 4px 0;
  }
  .job-title {
    font-weight: 600;
    color: #1a73e8;
    font-size: 11pt;
    margin-bottom: 2px;
  }
  .job-meta {
    color: #202124;
    font-size: 10pt;
    margin-bottom: 2px;
  }
  .job-detail {
    color: #5f6368;
    font-size: 9pt;
    margin-bottom: 2px;
    word-break: break-all;
  }
  .job-desc {
    color: #3c4043;
    font-size: 9pt;
    margin-top: 4px;
    line-height: 1.45;
  }
  hr {
    border: none;
    border-top: 1px solid #e8eaed;
    margin: 4px 0;
  }
  .footer {
    margin-top: 20px;
    padding-top: 10px;
    border-top: 1px solid #e0e0e0;
    text-align: center;
    color: #9aa0a6;
    font-size: 8pt;
  }
</style>
</head>
<body>
<div class="container">

  <div class="header">
    <h1>FundoCareer Job Alert Report</h1>
    <div class="meta">Generated: ${escapeHtml(genDate)}</div>
  </div>

  ${safeName ? `
  <div class="summary">
    <div class="summary-title">Preference Summary</div>
    <div class="summary-row">Role: ${safeName}</div>
    <div>
      <span class="count-badge">Jobs included: ${jobs.length}</span>
    </div>
  </div>` : `
  <div class="summary">
    <div class="summary-title">Job Alert Report</div>
    <div>
      <span class="count-badge">Jobs included: ${jobs.length}</span>
    </div>
  </div>`}

  ${jobs.length > 0 ? `
  <table class="jobs">
    ${jobRows}
  </table>` : '<p style="color:#5f6368;font-size:10pt">No jobs to display.</p>'}

  <div class="footer">
    Powered by FundoCareer
  </div>

</div>
</body>
</html>`;
}

/**
 * Generate a PDF job alert report.
 *
 * @param {Object} params
 * @param {string} [params.preferenceName] - Name/role of the job search preference
 * @param {Array<Object>} params.newJobs - Array of job objects with fields: jobTitle, company, location, source, url, description
 * @param {Date}   [params.generatedAt] - Custom generation date (defaults to now)
 * @returns {Promise<{success: boolean, buffer?: Buffer, error?: string}>}
 */
export async function generateJobAlertPdf({ preferenceName, newJobs, generatedAt }) {
  try {
    const jobs = (newJobs || []).slice(0, 50);
    const html = buildJobAlertHtml({ preferenceName, newJobs: jobs, generatedAt });
    const raw = await generatePdfFromHtml(html, {
      margin: { top: '15mm', right: '15mm', bottom: '15mm', left: '15mm' },
    });
    return { success: true, buffer: Buffer.from(raw) };
  } catch (error) {
    console.error('[JobAlertPdf] Generation failed:', error.message);
    return { success: false, error: error.message };
  }
}
