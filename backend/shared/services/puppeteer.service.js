import puppeteer from 'puppeteer';

/**
 * Puppeteer Service for PDF Generation
 * Self-contained implementation (no external dependencies)
 */

let browserInstance = null;

const getBrowser = async () => {
  if (browserInstance) return browserInstance;

  console.log("[Puppeteer] Launching browser instance...");
  browserInstance = await puppeteer.launch({
    headless: 'new',
    args: [
      '--no-sandbox',
      '--disable-setuid-sandbox',
      '--disable-dev-shm-usage',
      '--disable-file-system',
    ]
  });

  browserInstance.on('disconnected', () => {
    console.warn("[Puppeteer] Browser disconnected. Resetting instance.");
    browserInstance = null;
  });

  return browserInstance;
};

export const generatePdfFromHtml = async (htmlContent, options = {}) => {
  let page = null;
  try {
    const browser = await getBrowser();
    page = await browser.newPage();

    // Security: Request Interception
    await page.setRequestInterception(true);
    page.on('request', (req) => {
      const url = req.url().toLowerCase();

      // Allow data URIs
      if (url.startsWith('data:')) {
        req.continue();
        return;
      }

      // Allow localhost (frontend)
      const allowedOrigin = process.env.FRONTEND_URL || 'http://localhost:5173';
      if (url.startsWith(allowedOrigin)) {
        req.continue();
        return;
      }

      // Allow Google Fonts
      if (url.includes('fonts.googleapis.com') || url.includes('fonts.gstatic.com')) {
        req.continue();
        return;
      }

      // Block everything else
      req.abort();
    });

    // Set content and wait for resources
    await page.setContent(htmlContent, {
      waitUntil: 'networkidle0',
      timeout: 30000
    });

    // 🔥 CRITICAL FIX: Wait for fonts to load
    // networkidle0 only waits for network requests, NOT font rendering
    // This ensures PDF snapshots after fonts are fully loaded
    try {
      await page.evaluateHandle('document.fonts.ready');
      console.log('[Puppeteer] Fonts loaded successfully');
    } catch (fontError) {
      console.warn('[Puppeteer] Font loading wait failed (non-critical):', fontError.message);
      // Continue anyway - fonts might already be loaded or embedded
    }

    // 🔥 CRITICAL FIX: Wait for all images to load
    // This prevents missing images in PDF
    try {
      await page.evaluate(() => Promise.all(
        Array.from(document.images)
          .filter(img => !img.complete)
          .map(img => new Promise(resolve => {
            img.onload = resolve;
            img.onerror = resolve; // Continue even if image fails to load
          }))
      ));
      console.log('[Puppeteer] Images loaded successfully');
    } catch (imageError) {
      console.warn('[Puppeteer] Image loading wait failed (non-critical):', imageError.message);
    }

    // Additional stability wait for layout to settle after font load
    await page.evaluate(() => new Promise(resolve => setTimeout(resolve, 100)));

    // Generate PDF
    const pdfBuffer = await page.pdf({
      format: 'A4',
      printBackground: true,
      margin: {
        top: '0mm',
        right: '0mm',
        bottom: '0mm',
        left: '0mm'
      },
      ...options
    });

    return pdfBuffer;

  } catch (error) {
    console.error("[Puppeteer] PDF generation failed:", error.message);
    if (browserInstance) {
      try { await browserInstance.close(); } catch (e) { console.error("[Puppeteer] Failed to close browser during error:", e); }
      browserInstance = null;
    }
    throw error;
  } finally {
    if (page) {
      await page.close().catch(e => console.warn("[Puppeteer] Failed to close page", e));
    }
  }
};

export const closeBrowser = async () => {
  if (browserInstance) {
    try {
      await browserInstance.close();
    } catch (error) {
      console.error(`[Puppeteer] Failed to close browser: ${error.message}`);
    }
    browserInstance = null;
  }
};
