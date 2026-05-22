import { createRequire } from "module";
import axios from "axios";
import fs from 'fs';

const require = createRequire(import.meta.url);
const pdfParse = require("pdf-parse");
const mammoth = require("mammoth");

/**
 * Resume Parser Service
 * Extracts text content from resume files (PDF, DOCX, DOC, TXT)
 */

// Helper for debug logging
const logDebug = (msg) => {
  try {
    fs.appendFileSync('logs/resume_parser.log', `[${new Date().toISOString()}] ${msg}\n`);
  } catch (e) {
    console.error("Log write failed:", e);
  }
};

/**
 * Parse resume file to extract text content
 * @param {Object} file - Multer file object
 * @returns {Promise<string>} - Extracted text from the resume
 */
export const parseResumeFile = async (file) => {
  try {
    logDebug(`Starting parse for file: ${file.originalname}`);

    const { path, mimetype, buffer: fileBuffer } = file;

    // Get buffer from various sources
    let buffer;
    if (fileBuffer) {
      logDebug("Using memory buffer");
      buffer = fileBuffer;
    } else if (path && path.startsWith("http")) {
      logDebug(`Fetching from URL: ${path}`);
      const response = await axios.get(path, { responseType: "arraybuffer" });
      buffer = Buffer.from(response.data);
    } else if (path) {
      logDebug(`Reading local file: ${path}`);
      if (fs.existsSync(path)) {
        buffer = fs.readFileSync(path);
        logDebug(`File read OK. Size: ${buffer.length}`);
      } else {
        throw new Error(`File not found at path: ${path}`);
      }
    } else {
      throw new Error("Invalid file source");
    }

    const originalName = file.originalname || "";
    const extension = originalName.split('.').pop().toLowerCase();
    let currentMime = mimetype;

    // Fallback: MIME correction based on extension
    if (currentMime === "application/octet-stream" || currentMime === "application/x-zip-compressed") {
      if (extension === "pdf") currentMime = "application/pdf";
      if (extension === "docx") currentMime = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
      if (extension === "doc") currentMime = "application/msword";
      if (extension === "txt") currentMime = "text/plain";
      logDebug(`MIME corrected: ${mimetype} -> ${currentMime}`);
    }

    // Check for empty files
    if (buffer.length < 120) {
      const content = buffer.toString('utf-8').trim();
      if (currentMime === "text/plain" || (content.length > 5 && !content.startsWith("%PDF"))) {
        return content;
      }
      throw new Error("File is too small or empty");
    }

    let extractedText = "";

    // PDF parsing
    if (currentMime === "application/pdf" || extension === "pdf") {
      logDebug("Parsing PDF...");
      try {
        // Timeout wrapper
        const timeoutPromise = new Promise((_, reject) =>
          setTimeout(() => reject(new Error("PDF parsing timeout (60s)")), 60000)
        );

        // Custom render function for link extraction
        let pageCount = 0;
        const render_page = async (pageData) => {
          pageCount++;
          let render_options = {
            normalizeWhitespace: true,
            disableCombineTextItems: false
          };

          let textContent = await pageData.getTextContent(render_options);
          let lastY, text = '';

          text += `\n\n--- PAGE ${pageCount} ---\n`;

          for (let item of textContent.items) {
            if (lastY == item.transform[5] || !lastY) {
              text += item.str;
            } else {
              text += '\n' + item.str;
            }
            lastY = item.transform[5];
          }

          // Extract hyperlinks
          let annotations = await pageData.getAnnotations();
          let links = [];
          for (let annot of annotations) {
            if (annot.subtype === 'Link' && annot.url) {
              links.push(annot.url);
            }
          }

          if (links.length > 0) {
            text += '\n\n--- LINKS ---\n' + links.join('\n') + '\n';
          }

          return text;
        };

        const pdfData = await Promise.race([
          pdfParse(buffer, { pagerender: render_page }),
          timeoutPromise
        ]);

        extractedText = pdfData.text;
        if (!extractedText || extractedText.trim().length === 0) {
          throw new Error("PDF contained no selectable text");
        }
      } catch (err) {
        logDebug(`PDF error: ${err.message}`);
        const fallback = buffer.toString('utf-8');
        if (fallback.startsWith("%PDF")) {
          throw err;
        } else {
          extractedText = fallback;
        }
      }
    }
    // DOCX parsing
    else if (currentMime === "application/vnd.openxmlformats-officedocument.wordprocessingml.document" || extension === "docx") {
      logDebug("Parsing DOCX...");
      try {
        const result = await mammoth.convertToHtml({ buffer });
        let html = result.value;

        // Transform links: <a href="url">Text</a> -> Text (url)
        html = html.replace(/<a[^>]*href=["']([^"']*)["'][^>]*>(.*?)<\/a>/gi, '$2 ($1)');

        // Strip HTML tags
        extractedText = html.replace(/<\/?[^>]+(>|$)/g, "");

        // Decode HTML entities
        extractedText = extractedText
          .replace(/&nbsp;/g, ' ')
          .replace(/&amp;/g, '&')
          .replace(/&lt;/g, '<')
          .replace(/&gt;/g, '>');

        logDebug(`DOCX parsed. Length: ${extractedText.length}`);
      } catch (docxErr) {
        logDebug(`DOCX error: ${docxErr.message}`);
        throw new Error("Failed to parse DOCX file");
      }
    }
    // DOC parsing
    else if (currentMime === "application/msword" || extension === "doc") {
      logDebug("Parsing DOC...");
      const WordExtractor = require("word-extractor");
      const extractor = new WordExtractor();
      const extracted = await extractor.extract(buffer);
      extractedText = extracted.getBody();
    }
    // Plain text fallback
    else {
      logDebug("Fallback to text...");
      extractedText = buffer.toString("utf-8");
    }

    // Cleanup whitespace
    extractedText = extractedText
      .replace(/[ \t]+/g, " ")
      .replace(/\n\s*\n/g, "\n")
      .trim();

    logDebug(`Successfully extracted ${extractedText.length} characters`);
    return extractedText;

  } catch (error) {
    logDebug(`ERROR: ${error.message}`);
    console.error("[Resume Parser] Error:", error);
    throw new Error(`Failed to parse file: ${error.message}`);
  }
};

/**
 * AI-powered resume data extraction
 * Wrapper to import from AI service
 */
export { extractResumeData } from './aiResumeExtractor.service.js';
