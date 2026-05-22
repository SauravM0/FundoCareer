import dJSON from 'dirty-json';

/**
 * AI JSON Utilities
 * Cleaning and parsing utilities for AI-generated content
 */

/**
 * Cleans AI-generated text from special characters
 * @param {string} text - Raw AI text
 * @returns {string} - Cleaned text
 */
export const cleanAiText = (text) => {
  if (!text) return text;
  return text
    .replace(/ﬁ/g, 'fi')
    .replace(/ﬂ/g, 'fl')
    .replace(/–/g, '-')
    .replace(/—/g, '-')
    .replace(/"/g, '\"')
    .replace(/"/g, '\"')
    .replace(/'/g, "'")
    .replace(/'/g, "'")
    .replace(/□/g, ""); // Remove unknown blocks
};

/**
 * Validates and parses JSON string, with fallback for malformed JSON
 * @param {string} text - JSON string (possibly with markdown or malformed)
 * @returns {Object} - Parsed JSON object
 */
export const cleanAndParseJSON = (text) => {
  // Remove markdown code blocks
  let cleanText = text.replace(/```json/g, "").replace(/```/g, "").trim();

  try {
    // Find first '{' and last '}' to handle preamble/postscript
    const firstOpen = cleanText.indexOf('{');
    const lastClose = cleanText.lastIndexOf('}');

    if (firstOpen !== -1 && lastClose !== -1 && lastClose > firstOpen) {
      cleanText = cleanText.substring(firstOpen, lastClose + 1);
    }

    return JSON.parse(cleanText);
  } catch (e) {
    console.warn("Standard JSON parse failed, trying brace balancing & dirty-json...");

    try {
      // Brace balancing strategy
      let openBraces = (cleanText.match(/\{/g) || []).length;
      let closeBraces = (cleanText.match(/\}/g) || []).length;
      let openBrackets = (cleanText.match(/\[/g) || []).length;
      let closeBrackets = (cleanText.match(/\]/g) || []).length;

      while (openBraces > closeBraces) { cleanText += "}"; closeBraces++; }
      while (openBrackets > closeBrackets) { cleanText += "]"; closeBrackets++; }

      // Try dirty-json on balanced text
      return dJSON.parse(cleanText);
    } catch (dError) {
      console.error("JSON Parse Error:", e.message);
      console.error("Dirty JSON Error:", dError.message);
      console.log("DEBUG: Raw AI output =", text);
      throw new Error("AI returned invalid JSON.");
    }
  }
};
