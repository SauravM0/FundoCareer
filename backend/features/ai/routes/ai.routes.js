import express from 'express';
import { authenticate } from '../../../middlewares/auth.middleware.js';
import { enhanceText } from '../controllers/ai.controller.js';

const router = express.Router();

/**
 * AI Routes
 * /api/ai
 */

// Enhance text (Protected)
router.post('/enhance', authenticate, enhanceText);

export default router;
