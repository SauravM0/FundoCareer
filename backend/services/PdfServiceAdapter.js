
import { generatePdfFromHtml } from '../shared/services/puppeteer.service.js';

/**
 * PDF Service Adapter
 * Wraps the local Puppeteer service to provide a clean interface.
 * @param {string} html - Full HTML string to render
 * @returns {Promise<Buffer>} - PDF Buffer
 */
export const generateResumePdf = async (html) => {
    try {
        const buffer = await generatePdfFromHtml(html);
        return buffer;
    } catch (error) {
        console.error("PDF Adapter Error:", error);
        throw error;
    }
};
