
import express from 'express';
import { generateResumePdf } from '../services/PdfServiceAdapter.js';

const router = express.Router();

/**
 * POST /api/download/pdf
 * Generates a PDF from the provided HTML content.
 */
router.post('/pdf', async (req, res) => {
    try {
        const { html } = req.body;

        if (!html) {
            return res.status(400).json({ error: "HTML content is required" });
        }

        const pdfBuffer = await generateResumePdf(html);

        // Ensure we have a Buffer (Puppeteer might return Uint8Array)
        const contentBuffer = Buffer.from(pdfBuffer);

        res.set({
            'Content-Type': 'application/pdf',
            'Content-Length': contentBuffer.length,
            'Content-Disposition': 'attachment; filename="resume.pdf"'
        });

        res.send(contentBuffer);

    } catch (error) {
        console.error("Download Route Error:", error);
        res.status(500).json({ error: error.message || "Failed to generate PDF" });
    }
});

export default router;
