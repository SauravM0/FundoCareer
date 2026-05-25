import express from 'express';
import { authenticate } from '../../../middlewares/auth.middleware.js';
import { sendJobAlertEmail, sendSetupConfirmationEmail } from '../controllers/notifications.controller.js';

const router = express.Router();

// All notification routes require authentication
router.use(authenticate);

router.post('/send-email', sendJobAlertEmail);
router.post('/send-setup-email', sendSetupConfirmationEmail);

export default router;
