import multer from "multer";
import { v2 as cloudinary } from "cloudinary";
import { Readable } from "stream";

/**
 * Cloudinary Upload Middleware
 * Handles profile image uploads to Cloudinary using memory storage + upload stream.
 * Compatible with cloudinary v2.
 */

// Configure Cloudinary
cloudinary.config({
  cloud_name: process.env.CLOUDINARY_CLOUD_NAME,
  api_key: process.env.CLOUDINARY_API_KEY,
  api_secret: process.env.CLOUDINARY_API_SECRET,
});

// Use memory storage — we stream the buffer straight to Cloudinary
const storage = multer.memoryStorage();

// File filter - only images allowed
const fileFilter = (req, file, cb) => {
  if (file.mimetype.startsWith("image/")) {
    cb(null, true);
  } else {
    cb(new Error("Only image files are allowed"), false);
  }
};

// Multer instance
const upload = multer({
  storage,
  fileFilter,
  limits: { fileSize: 2 * 1024 * 1024 }, // 2MB limit
});

/**
 * Streams a buffer to Cloudinary and attaches the result to req.cloudinaryResult.
 * Use after upload.single('image') (or similar) in your route.
 */
export const uploadToCloudinary = (req, res, next) => {
  if (!req.file) return next();

  const publicId = `${req.file.originalname.split(".")[0]}-${Date.now()}`;

  const uploadStream = cloudinary.uploader.upload_stream(
    {
      folder: "resumes/profile_images",
      resource_type: "image",
      public_id: publicId,
    },
    (error, result) => {
      if (error) return next(error);
      req.cloudinaryResult = result;
      // Mirror the shape that multer-storage-cloudinary used to set on req.file
      req.file.path = result.secure_url;
      req.file.filename = result.public_id;
      next();
    },
  );

  Readable.from(req.file.buffer).pipe(uploadStream);
};

// Default export keeps the original single-middleware pattern.
// Routes that previously did:  uploadImage.single('image')
// should now do:               uploadImage.single('image'), uploadToCloudinary
const uploadImage = upload;
export default uploadImage;
