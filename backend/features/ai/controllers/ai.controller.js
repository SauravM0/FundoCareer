import { generateAIContent } from '../../../shared/services/ai.service.js';

/**
 * AI Controller
 * Handles AI-powered features like text enhancement
 * Uses Xiaomi AI with automatic fallback to OpenRouter/Gemini
 */

export const enhanceText = async (req, res) => {
  try {
    const { text, type } = req.body;

    if (!text) {
      return res.status(400).json({ success: false, message: "Text is required" });
    }

    // Build prompt based on type
    let systemPrompt = "You are a professional resume editor.";
    let userPrompt = "";

    if (type === 'experience') {
      userPrompt = `Rewrite the following work experience bullet point to be more impactful, using action verbs and quantifying results if possible. Provide 3 distinct variations. Format as:\nOption 1: [content]\nOption 2: [content]\nOption 3: [content]\n\nInput: "${text}"`;
    } else if (type === 'summary') {
      userPrompt = `Rewrite the following professional summary to be more engaging and professional. Provide 3 distinct variations. Format as:\nOption 1: [content]\nOption 2: [content]\nOption 3: [content]\n\nInput: "${text}"`;
    } else {
      userPrompt = `Improve the professionalism and clarity of this text for a resume. Provide 3 distinct variations. Format as:\nOption 1: [content]\nOption 2: [content]\nOption 3: [content]\n\nInput: "${text}"`;
    }

    // Call shared AI service (Xiaomi with fallbacks)
    const result = await generateAIContent([
      { role: "system", content: systemPrompt },
      { role: "user", content: userPrompt }
    ], false);

    // Extract text from result
    const enhancedText = typeof result === 'string' 
      ? result 
      : (result.content || text);

    // Clean up quotes if AI adds them
    const cleanText = enhancedText.replace(/^"|"$/g, '').trim();

    res.json({ 
      success: true, 
      enhancedText: cleanText
    });

  } catch (error) {
    console.error('[AI Enhance] Error:', error);
    res.status(500).json({ 
      success: false, 
      message: error.message || "Failed to enhance text" 
    });
  }
};
