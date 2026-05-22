import prisma from '../../../config/database.config.js';
import path from 'path';

/**
 * Source Resume Service
 * Handles canonical resume file management with max-5 limit enforcement
 */

/**
 * Creates a SourceResume entry for an uploaded file
 * Enforces Max-5 Active Resume Limit per User
 */
export const createSourceResume = async ({ userId, file, extractedText }) => {
  if (!userId || !file) {
    throw new Error("createSourceResume: Missing userId or file");
  }

  const originalFilename = file.originalname;
  const fileSize = file.size;
  const filePath = file.path; // Absolute path or S3 URL

  // Determine file type
  const ext = path.extname(originalFilename).toLowerCase().replace('.', '');
  let fileType = 'pdf'; // Default
  if (['pdf', 'docx', 'doc', 'txt'].includes(ext)) {
    fileType = ext;
  }

  // Determine storage provider
  const storageProvider = (filePath.startsWith('http') || filePath.startsWith('s3:'))
    ? 's3'
    : 'local';

  // Enforce MAX-5 LIMIT
  const activeCount = await prisma.sourceResume.count({
    where: {
      user_id: userId,
      status: 'active'
    }
  });

  if (activeCount >= 5) {
    // Soft delete oldest active resume
    const oldest = await prisma.sourceResume.findFirst({
      where: {
        user_id: userId,
        status: 'active'
      },
      orderBy: {
        created_at: 'asc'
      }
    });

    if (oldest) {
      console.log(`[SourceResume] Limit reached (5). Soft deleting oldest ID: ${oldest.id}`);
      await prisma.sourceResume.update({
        where: { id: oldest.id },
        data: { status: 'deleted' }
      });
    }
  }

  // Create new record
  const newResume = await prisma.sourceResume.create({
    data: {
      user_id: userId,
      original_filename: originalFilename,
      s3_object_key: filePath,
      file_type: fileType,
      file_size: fileSize,
      extracted_text: extractedText || "",
      storage_provider: storageProvider,
      display_name: originalFilename,
      status: 'active'
    }
  });

  console.log(`[SourceResume] Created ID: ${newResume.id} for User: ${userId}`);
  return newResume;
};

/**
 * Fetches all active SourceResumes for a user
 */
export const getActiveSourceResumes = async (userId) => {
  return await prisma.sourceResume.findMany({
    where: {
      user_id: userId,
      status: 'active'
    },
    orderBy: {
      created_at: 'desc'
    },
    select: {
      id: true,
      display_name: true,
      original_filename: true,
      file_type: true,
      file_size: true,
      created_at: true,
      s3_object_key: true,
      extracted_text: true
    }
  });
};

/**
 * Renames a SourceResume (Display Name only)
 */
export const renameSourceResume = async (userId, resumeId, newName) => {
  if (!newName || newName.trim().length === 0) {
    throw new Error("New name cannot be empty");
  }

  // Verify ownership
  const resume = await prisma.sourceResume.findFirst({
    where: { id: parseInt(resumeId), user_id: userId, status: 'active' }
  });

  if (!resume) {
    throw new Error("Resume not found or access denied");
  }

  return await prisma.sourceResume.update({
    where: { id: parseInt(resumeId) },
    data: { display_name: newName }
  });
};

/**
 * Soft Deletes a SourceResume
 */
export const softDeleteSourceResume = async (userId, resumeId) => {
  // Verify ownership
  const resume = await prisma.sourceResume.findFirst({
    where: { id: parseInt(resumeId), user_id: userId, status: 'active' }
  });

  if (!resume) {
    throw new Error("Resume not found or access denied");
  }

  return await prisma.sourceResume.update({
    where: { id: parseInt(resumeId) },
    data: { status: 'deleted', updated_at: new Date() }
  });
};
