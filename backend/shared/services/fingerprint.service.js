import { createHash } from 'crypto';

function sha256(input) {
  return createHash('sha256').update(input).digest('hex');
}

function normalizeUrl(url) {
  if (!url) return '';
  try {
    const parsed = new URL(url);
    parsed.hash = '';
    parsed.search = '';
    let path = parsed.pathname.replace(/\/+$/, '');
    if (path === '') path = '/';
    return `${parsed.protocol}//${parsed.host}${path}`.toLowerCase();
  } catch {
    return url.trim().toLowerCase();
  }
}

function extractUrlFromFingerprint(fp) {
  const urlMatch = fp.match(/url:(https?:\/\/[^\s|]+)/i);
  if (urlMatch) return urlMatch[1];
  return null;
}

function normalizeAndHash(raw) {
  const s = (raw || '').trim();
  if (!s) return '';

  const extractedUrl = extractUrlFromFingerprint(s);
  if (extractedUrl) {
    const normalizedUrl = normalizeUrl(extractedUrl);
    const sourcePrefix = s.includes('|') ? s.split('|')[0] : 'UNKNOWN';
    return sha256(`${sourcePrefix}|url:${normalizedUrl}`);
  }

  const parts = s.split('|').filter(p => p);
  const source = parts.length > 0 ? parts[0] : 'UNKNOWN';
  const fieldParts = parts.slice(1)
    .map(p => p.trim().toLowerCase().replace(/\s+/g, ' '))
    .filter(p => p.length > 0)
    .sort();
  return sha256(`${source}|${fieldParts.join('|')}`);
}

export function normalizeFingerprint(raw) {
  return normalizeAndHash(raw);
}

export function normalizeFingerprints(rawList) {
  return rawList.map(r => normalizeAndHash(r)).filter(h => h.length > 0);
}
