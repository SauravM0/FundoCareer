import express from 'express';
import { authenticate } from '../../../middlewares/auth.middleware.js';
import {
  acquireLock,
  releaseLock,
  checkEmailedJobs,
  markEmailedJobs,
  syncState,
  getState,
  historySummary,
  getEmailHistory,
  getUserTimeline,
  getActiveDevice,
  activateDevice,
  heartbeatDevice,
  deactivateDevice,
  verifyDevice,
} from '../controllers/scheduler.controller.js';

const router = express.Router();

// All scheduler routes require authentication
router.use(authenticate);

router.post('/acquire-lock', acquireLock);
router.post('/release-lock', releaseLock);
router.post('/check-emailed-jobs', checkEmailedJobs);
router.post('/mark-emailed-jobs', markEmailedJobs);
router.post('/sync-state', syncState);
router.get('/state', getState);
router.post('/history-summary', historySummary);
router.get('/history', getEmailHistory);
router.get('/user-timeline', getUserTimeline);

// Active device endpoints
router.get('/active-device', getActiveDevice);
router.post('/active-device/activate', activateDevice);
router.post('/active-device/heartbeat', heartbeatDevice);
router.post('/active-device/deactivate', deactivateDevice);
router.post('/active-device/verify', verifyDevice);

export default router;
