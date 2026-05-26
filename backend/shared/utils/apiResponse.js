export function sendSuccess(res, data = {}, statusCode = 200) {
  const response = { success: true };
  const requestId = res.req?.requestId;
  if (requestId) response.requestId = requestId;
  if (data && typeof data === 'object' && !Array.isArray(data)) {
    Object.assign(response, data);
  }
  return res.status(statusCode).json(response);
}

export function sendError(res, message, statusCode = 500, errorCode = null, extra = {}) {
  const response = {
    success: false,
    message,
    ...(errorCode ? { errorCode } : {}),
  };
  const requestId = res.req?.requestId;
  if (requestId) response.requestId = requestId;
  if (process.env.NODE_ENV === 'development' && extra.stack) {
    response.stack = extra.stack;
  }
  return res.status(statusCode).json(response);
}

export function sendAuthError(res, message = 'Authentication required.') {
  return sendError(res, message, 401, 'AUTH_REQUIRED');
}

export function sendValidationError(res, message, errorCode = 'VALIDATION_ERROR') {
  return sendError(res, message, 400, errorCode);
}

export function sendNotFoundError(res, message = 'Resource not found') {
  return sendError(res, message, 404, 'NOT_FOUND');
}

export function sendConflictError(res, message, errorCode = 'CONFLICT', extra = {}) {
  return sendError(res, message, 409, errorCode, extra);
}

export function sendServerError(res, message = 'Internal server error', errorCode = 'SERVER_ERROR') {
  return sendError(res, message, 500, errorCode);
}
