import express from 'express';
import { authenticate } from '../../../middlewares/auth.middleware.js';
import uploadImage, { uploadToCloudinary } from '../../../middlewares/uploadImage.middleware.js';
import uploadFile from '../../../middlewares/uploadFile.middleware.js';
import {
  createResume,
  getMyResumes,
  getResumeById,
  updateResume,
  deleteResume,
  duplicateResume,
  uploadResume,
  extractResumeText
} from '../controllers/resume.controller.js';
import {
  getActiveSourceResumes,
  renameSourceResume,
  softDeleteSourceResume
} from '../services/sourceResume.service.js';

const router = express.Router();

/**
 * Resume Routes
 * All routes require authentication
 */

// Extract text from resume file
router.post('/extract-text', authenticate, uploadFile.single('resumeFile'), extractResumeText);

// Source resume management (library)
router.get('/source-resumes', authenticate, async (req, res) => {
  try {
    const resumes = await getActiveSourceResumes(req.userId);
    res.json({ success: true, resumes });
  } catch (err) {
    res.status(500).json({ success: false, message: err.message });
  }
});

router.patch('/source-resumes/:id', authenticate, async (req, res) => {
  try {
    const { display_name } = req.body;
    const { id } = req.params;
    const updated = await renameSourceResume(req.userId, id, display_name);
    res.json({ success: true, resume: updated });
  } catch (err) {
    res.status(400).json({ success: false, message: err.message });
  }
});

router.delete('/source-resumes/:id', authenticate, async (req, res) => {
  try {
    const { id } = req.params;
    await softDeleteSourceResume(req.userId, id);
    res.json({ success: true, message: "Resume deleted" });
  } catch (err) {
    res.status(400).json({ success: false, message: err.message });
  }
});

// Resume file upload and parsing
router.post('/upload', authenticate, uploadFile.single('resumeFile'), uploadResume);

// Resume CRUD operations
router.post('/', authenticate, uploadImage.single('profileImage'), uploadToCloudinary, createResume);
router.get('/', authenticate, getMyResumes);
router.get('/:id', authenticate, getResumeById);
router.patch('/:id', authenticate, uploadImage.single('profileImage'), uploadToCloudinary, updateResume);
router.delete('/:id', authenticate, deleteResume);
router.post('/:id/duplicate', authenticate, duplicateResume);

export default router;
