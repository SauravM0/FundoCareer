export function maskDeviceId(id) {
  if (!id || id.length < 8) return id || '(none)';
  return id.substring(0, 4) + '...' + id.substring(id.length - 4);
}

export function maskEmail(email) {
  if (!email || !email.includes('@')) return email || '(none)';
  const [local, domain] = email.split('@');
  if (local.length <= 1) return email;
  return local[0] + '***@' + domain;
}
