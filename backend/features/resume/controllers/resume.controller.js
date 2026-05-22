import prisma from '../../../config/database.config.js';
import { deepMerge } from '../../../shared/utils/deepMerge.js';
import { parseJsonFields } from '../../../shared/utils/parseJsonFields.js';
import { createSourceResume } from '../services/sourceResume.service.js';
import { parseResumeFile, extractResumeData } from '../services/resumeParser.service.js';
import fs from 'fs';
import path from 'path';

/**
 * Resume Controller
 * Handles all resume CRUD operations, file uploads, and AI parsing
 */

// Helper to deserialize Prisma JSON strings to Objects
const deserializeResume = (resume) => {
  if (!resume) return null;
  return {
    ...resume,
    _id: resume.id, // For frontend compatibility
    formatting: typeof resume.formatting === 'string' ? JSON.parse(resume.formatting || "{}") : resume.formatting,
    personal_info: typeof resume.personal_info === 'string' ? JSON.parse(resume.personal_info || "{}") : resume.personal_info,
    sections: typeof resume.sections === 'string' ? JSON.parse(resume.sections || "[]") : resume.sections,
  };
};

/**
 * Create a new resume
 * POST /api/resume
 */
export const createResume = async (req, res) => {
  try {
    const userId = parseInt(req.userId);
    let payload = parseJsonFields(req.body);

    // Handle profile image upload
    if (req.file) {
      payload.personal_info = {
        ...(payload.personal_info || {}),
        image: req.file.path,
      };
    }

    // --- MONETIZATION & ENTITLEMENT LOGIC ---
    const featureName = "RESUME_CREATE";
    const feature = await prisma.feature.findUnique({
      where: { name: featureName }
    });

    if (!feature) {
      console.error(`CRITICAL: Feature ${featureName} not found in DB.`);
      return res.status(500).json({ success: false, message: "Service misconfiguration: Feature missing." });
    }

    const tokenCost = feature.token_price;

    // Fetch active subscriptions with plan entitlements
    const activeSubs = await prisma.subscription.findMany({
      where: {
        user_id: userId,
        status: 'active',
        end_at: { gt: new Date() }
      },
      include: {
        plan: {
          include: {
            planFeatures: true
          }
        }
      },
      orderBy: {
        end_at: 'asc' // Earliest expiry first
      }
    });

    // Filter for entitled subscriptions
    const entitledSubs = activeSubs.filter(sub =>
      sub.plan.planFeatures.some(pf => pf.feature_id === feature.id)
    );

    if (entitledSubs.length === 0) {
      return res.status(403).json({
        success: false,
        message: "Your current plan does not support Resume Creation. Please upgrade."
      });
    }

    // Calculate total available tokens
    const totalTokens = entitledSubs.reduce((sum, sub) => sum + sub.tokens_remaining, 0);

    if (totalTokens < tokenCost) {
      return res.status(402).json({
        success: false,
        message: `Insufficient Tokens. Cost: ${tokenCost}, Available: ${totalTokens}. Please top up.`
      });
    }

    // Prepare transaction
    const prismaData = {
      userId,
      title: payload.title || "Untitled Resume",
      template: payload.template || "classic",
      accent_color: payload.accent_color || "#3B82F6",
      public: payload.public || false,
      formatting: JSON.stringify(payload.formatting || {}),
      personal_info: JSON.stringify(payload.personal_info || {}),
      professional_summary: payload.professional_summary || "",
      sections: JSON.stringify(payload.sections || []),
      fileUrl: payload.fileUrl,
      fileName: payload.fileName
    };

    const transactionOperations = [];
    let remainingToDeduct = tokenCost;
    let primarySubId = entitledSubs[0].id;

    for (const sub of entitledSubs) {
      if (remainingToDeduct <= 0) break;

      const deduction = Math.min(sub.tokens_remaining, remainingToDeduct);

      if (deduction > 0) {
        transactionOperations.push(
          prisma.subscription.update({
            where: { id: sub.id },
            data: { tokens_remaining: { decrement: deduction } }
          })
        );
        remainingToDeduct -= deduction;
        primarySubId = sub.id;
      }
    }

    // Create resume
    transactionOperations.push(
      prisma.resume.create({ data: prismaData })
    );

    // Log activity
    transactionOperations.push(
      prisma.userActivity.create({
        data: {
          user_id: userId,
          subscription_id: primarySubId,
          feature_id: feature.id,
          tokens_used: tokenCost
        }
      })
    );

    // Execute atomic transaction
    const results = await prisma.$transaction(transactionOperations);
    const createdResume = results[results.length - 2];

    res.status(201).json({ success: true, resume: deserializeResume(createdResume) });

  } catch (err) {
    console.error('[Create Resume] Error:', err);
    res.status(500).json({ success: false, message: err.message });
  }
};

/**
 * Get all resumes for the authenticated user
 * GET /api/resume
 */
export const getMyResumes = async (req, res) => {
  try {
    const resumes = await prisma.resume.findMany({
      where: { userId: parseInt(req.userId) },
      orderBy: { createdAt: 'desc' }
    });

    const deserializedResumes = resumes.map(deserializeResume);

    res.json({ success: true, resumes: deserializedResumes });
  } catch (err) {
    console.error("[Get My Resumes] Error:", err);
    res.status(500).json({ success: false, message: err.message });
  }
};

/**
 * Get a specific resume by ID
 * GET /api/resume/:id
 */
export const getResumeById = async (req, res) => {
  try {
    const resume = await prisma.resume.findFirst({
      where: {
        id: parseInt(req.params.id),
        userId: parseInt(req.userId)
      }
    });

    if (!resume) {
      return res.status(404).json({ success: false, message: "Resume not found" });
    }

    const deserialized = deserializeResume(resume);
    console.log(`[Get Resume By ID] ID: ${req.params.id} | Template: ${deserialized.template}`);

    res.json({ success: true, resume: deserialized });
  } catch (err) {
    console.error('[Get Resume By ID] Error:', err);
    res.status(500).json({ success: false, message: err.message });
  }
};

/**
 * Update a resume
 * PATCH /api/resume/:id
 */
export const updateResume = async (req, res) => {
  try {
    let update = parseJsonFields(req.body);

    // Handle profile image upload
    if (req.file) {
      update.personal_info = {
        ...(update.personal_info || {}),
        image: req.file.path,
      };
    }

    const existing = await prisma.resume.findFirst({
      where: { id: parseInt(req.params.id), userId: parseInt(req.userId) }
    });

    if (!existing) {
      return res.status(404).json({ success: false, message: "Resume not found" });
    }

    // Deserialize existing to merge
    const existingObj = deserializeResume(existing);

    console.log(`[Update Resume] ID: ${req.params.id} | Existing Template: ${existingObj.template}`);
    console.log(`[Update Resume] Incoming Keys: ${Object.keys(update).join(', ')}`);

    // Deep merge
    const merged = deepMerge(existingObj, update);

    // Ensure sections is always an array
    if (!Array.isArray(merged.sections)) {
      console.warn(`[Update Resume] Guardrail: sections was ${typeof merged.sections}, resetting to []`);
      merged.sections = [];
    }

    console.log(`[Update Resume] Merged Template: ${merged.template} | Sections: ${merged.sections.length}`);

    // Prepare for update (stringify)
    const updateData = {
      title: merged.title,
      template: merged.template,
      accent_color: merged.accent_color,
      public: merged.public,
      formatting: JSON.stringify(merged.formatting || {}),
      personal_info: JSON.stringify(merged.personal_info || {}),
      professional_summary: merged.professional_summary || "",
      sections: JSON.stringify(merged.sections || []),
      fileUrl: merged.fileUrl,
      fileName: merged.fileName
    };

    const updated = await prisma.resume.update({
      where: { id: parseInt(req.params.id) },
      data: updateData
    });

    res.json({ success: true, resume: deserializeResume(updated) });
  } catch (err) {
    console.error('[Update Resume] Error:', err);
    res.status(500).json({ success: false, message: err.message });
  }
};

/**
 * Delete a resume
 * DELETE /api/resume/:id
 */
export const deleteResume = async (req, res) => {
  try {
    const existing = await prisma.resume.findFirst({
      where: { id: parseInt(req.params.id), userId: parseInt(req.userId) }
    });

    if (!existing) {
      return res.status(404).json({ success: false, message: "Resume not found" });
    }

    await prisma.resume.delete({
      where: { id: parseInt(req.params.id) }
    });

    res.json({ success: true, message: "Resume deleted successfully" });
  } catch (err) {
    console.error('[Delete Resume] Error:', err);
    res.status(500).json({ success: false, message: err.message });
  }
};

/**
 * Duplicate a resume
 * POST /api/resume/:id/duplicate
 */
export const duplicateResume = async (req, res) => {
  try {
    const original = await prisma.resume.findFirst({
      where: { id: parseInt(req.params.id), userId: parseInt(req.userId) }
    });

    if (!original) {
      return res.status(404).json({ success: false, message: "Resume not found" });
    }

    const copyData = {
      userId: parseInt(req.userId),
      title: `${original.title} (Copy)`,
      template: original.template,
      accent_color: original.accent_color,
      public: false,
      formatting: original.formatting,
      personal_info: original.personal_info,
      sections: original.sections,
      professional_summary: original.professional_summary,
      fileUrl: original.fileUrl,
      fileName: original.fileName
    };

    const newResume = await prisma.resume.create({
      data: copyData
    });

    res.json({ success: true, resume: deserializeResume(newResume) });
  } catch (err) {
    console.error('[Duplicate Resume] Error:', err);
    res.status(500).json({ success: false, message: err.message });
  }
};

/**
 * Upload and parse resume file
 * POST /api/resume/upload
 */
export const uploadResume = async (req, res) => {
  try {
    const userId = parseInt(req.userId);
    
    if (!req.file) {
      return res.status(400).json({ success: false, message: "No file uploaded" });
    }

    const { originalname, path: filePath, mimetype } = req.file;

    // Reject image files early
    if (mimetype && mimetype.startsWith('image/')) {
      return res.status(400).json({ 
        success: false, 
        message: "Image files are not supported. Please upload a PDF or DOCX." 
      });
    }

    const title = originalname.split(".").slice(0, -1).join(".");
    console.log(`[Upload Resume] Processing: ${originalname} (User: ${userId})`);

    // Extract text from file
    let extractedText;
    try {
      extractedText = await parseResumeFile(req.file);

      if (!extractedText || extractedText.length < 50) {
        throw new Error("Extracted text is too short (possibly scanned PDF)");
      }
      
      console.log(`[Upload Resume] Text extracted. Length: ${extractedText.length}`);

      // Persist to source resume library
      try {
        await createSourceResume({
          userId: userId,
          file: req.file,
          extractedText: extractedText
        });
      } catch (e) {
        console.warn("[Upload Resume] SourceResume persistence failed (non-blocking):", e.message);
      }

    } catch (parseError) {
      console.error("[Upload Resume] Parse error:", parseError.message);

      // Create fallback resume
      const fallbackResume = await prisma.resume.create({
        data: {
          userId: userId,
          title: title || "Uploaded Resume",
          fileUrl: filePath,
          fileName: originalname,
          template: "classic",
          personal_info: JSON.stringify({ full_name: "Extraction Failed" }),
          professional_summary: "Could not extract text. Please fill details manually."
        }
      });
      
      return res.status(201).json({
        success: true,
        resume: deserializeResume(fallbackResume),
        warning: "File uploaded but text extraction failed."
      });
    }

    // AI parsing
    let structuredData;
    try {
      const textToAnalyze = extractedText.length > 25000 
        ? extractedText.substring(0, 25000) 
        : extractedText;
      
      structuredData = await extractResumeData(textToAnalyze);
      console.log("[Upload Resume] AI extraction successful");
      
    } catch (aiError) {
      console.error("[Upload Resume] AI extraction error:", aiError.message);

      const fallbackResume = await prisma.resume.create({
        data: {
          userId: userId,
          title: title || "Uploaded Resume",
          fileUrl: filePath,
          fileName: originalname,
          template: "classic",
          personal_info: JSON.stringify({ full_name: "Candidate" }),
          professional_summary: extractedText.substring(0, 2000)
        }
      });
      
      return res.status(201).json({
        success: true,
        resume: deserializeResume(fallbackResume),
        warning: "AI analysis failed. Raw text placed in summary."
      });
    }

    // Data sanitization & mapping
    const safeSections = structuredData.sections || [];

    safeSections.forEach(section => {
      // Normalize section IDs
      let id = section.id;
      if (section.type === 'experience') id = 'experience';
      if (section.type === 'education') id = 'education';
      if (section.type === 'skills') id = 'skills';
      if (section.type === 'project') id = 'project';
      section.id = id;

      // Map items based on type
      if (Array.isArray(section.data)) {
        section.data = section.data.map(item => {
          if (!item.id) {
            item.id = `imported-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;
          }

          // Experience mapping
          if (section.type === 'experience') {
            if (!item.position) item.position = item.role || "";
            if (!item.location) item.location = item.city || "";

            // Convert bullets to HTML description
            if (Array.isArray(item.bullets) && item.bullets.length > 0) {
              const bulletsHtml = "<ul>" + item.bullets.map(b => `<li>${b}</li>`).join('') + "</ul>";
              const existingDesc = item.description ? `<p>${item.description}</p>` : "";
              item.description = existingDesc + bulletsHtml;
            }
          }

          // Education mapping
          if (section.type === 'education') {
            if (!item.institution) item.institution = item.school || "";
            if (!item.location) item.location = item.city || "";
            if (item.description === undefined) item.description = "";
          }

          // Date sanitization
          if (item.start_date) item.start_date = String(item.start_date);
          if (item.end_date) item.end_date = String(item.end_date);

          return item;
        });
      }
    });

    // Create resume in database
    const newResume = await prisma.resume.create({
      data: {
        userId: userId,
        title: (structuredData.personal_info?.full_name || structuredData.personal_info?.fullName)
          ? `${structuredData.personal_info.full_name || structuredData.personal_info.fullName}'s Resume`
          : (title || "Smart Resume"),
        fileUrl: filePath,
        fileName: originalname,
        template: "classic",
        personal_info: JSON.stringify(structuredData.personal_info || {}),
        professional_summary: structuredData.professional_summary || "",
        sections: JSON.stringify(safeSections)
      }
    });

    console.log(`[Upload Resume] Success! Created Resume ID: ${newResume.id}`);

    res.status(201).json({
      success: true,
      resume: deserializeResume(newResume),
      message: "Resume created and parsed successfully!"
    });

  } catch (err) {
    console.error("[Upload Resume] Critical Error:", err);
    res.status(500).json({ success: false, message: err.message });
  }
};

/**
 * Extract text from resume file
 * POST /api/resume/extract-text
 */
export const extractResumeText = async (req, res) => {
  try {
    if (!req.file) {
      return res.status(400).json({ success: false, message: "No file uploaded" });
    }

    // Parse text
    const text = await parseResumeFile(req.file);

    // Persist to source resume if user is authenticated
    if (req.userId) {
      try {
        // Handle memory storage (Buffer) -> Disk
        if (!req.file.path && req.file.buffer) {
          const uploadDir = 'uploads';
          if (!fs.existsSync(uploadDir)) {
            fs.mkdirSync(uploadDir, { recursive: true });
          }
          const tempFilename = `upload-${Date.now()}-${req.file.originalname}`;
          const tempPath = path.join(uploadDir, tempFilename);
          fs.writeFileSync(tempPath, req.file.buffer);
          req.file.path = path.resolve(tempPath);
        }

        await createSourceResume({
          userId: parseInt(req.userId),
          file: req.file,
          extractedText: text
        });
      } catch (persistErr) {
        console.error("SourceResume persistence failed (non-blocking):", persistErr);
      }
    }

    res.json({ success: true, text });
  } catch (err) {
    console.error("Extract text error:", err);
    res.status(500).json({ 
      success: false, 
      message: "Failed to extract text from resume" 
    });
  }
};