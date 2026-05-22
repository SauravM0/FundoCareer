import axios from 'axios';
import OpenAI from 'openai';
import { GoogleGenerativeAI } from "@google/generative-ai";
import { cleanAiText } from '../utils/aiJsonUtils.js';

/**
 * AI Service
 * Orchestrates AI content generation with fallback support
 * Priority: 1. Xiaomi Direct -> 2. OpenRouter -> 3. Gemini Fallback
 */

// API Keys
const GOOGLE_API_KEY = process.env.GOOGLE_API_KEY || process.env.GEMINI_API_KEY;
const OPENROUTER_API_KEY = process.env.OPENROUTER_API_KEY;
const XIAOMI_API_KEY = process.env.XIAOMI_API_KEY;
const VITE_URL = process.env.VITE_URL || "http://localhost:5173";

// Google Gemini Client
const genAI = new GoogleGenerativeAI(GOOGLE_API_KEY || "missing-google-key");
const googleModel = genAI.getGenerativeModel({ model: "gemini-2.0-flash-exp" });

// OpenRouter models
const OPENROUTER_MODELS = [
  "xiaomi/mimo-v2-flash:free",
  "nvidia/nemotron-3-nano-30b-a3b:free",
  "allenai/olmo-3.1-32b-think:free",
  "google/gemini-2.0-flash-exp:free"
];

/**
 * Main AI content generation function
 * @param {Array|string} input - Messages array or single prompt
 * @param {boolean} jsonMode - Enable JSON mode
 * @returns {Promise<Object>} - { content, model, usage }
 */
export const generateAIContent = async (input, jsonMode = false) => {
  // Parse input into system and user prompts
  let messages = Array.isArray(input) ? input : [{ role: "user", content: input }];
  let systemRole = messages.find(m => m.role === "system")?.content || "You are a helpful AI.";
  let userRole = messages.filter(m => m.role === "user").map(m => m.content).join("\n\n") || "No input";

  // 1. Try Xiaomi Direct (Priority 1)
  if (XIAOMI_API_KEY) {
    try {
      return await callXiaomiAdapter(systemRole, userRole, jsonMode);
    } catch (e) {
      console.warn(`Xiaomi failed (${e.message}). Falling back...`);
    }
  }

  // 2. Try OpenRouter (Priority 2)
  if (OPENROUTER_API_KEY) {
    try {
      return await callOpenRouterAdapter(systemRole, userRole, jsonMode);
    } catch (e) {
      console.warn(`OpenRouter failed (${e.message}). Falling back...`);
    }
  }

  // 3. Fallback to Gemini (Priority 3)
  try {
    console.log(`[AI Service] Using fallback: Google Gemini Flash...`);
    return await callGeminiAdapter(systemRole, userRole, jsonMode);
  } catch (googleError) {
    console.error(`[AI Service] Gemini fallback failed: ${googleError.message}`);

    // 4. Last resort: Safety mock
    console.warn(`[AI Service] All AI models failed. Engaging safety mock.`);
    const mockContent = jsonMode ? JSON.stringify({
      personal_info: { full_name: "Mock Candidate (AI Failed)" },
      professional_summary: "AI Service Unavailable.",
      sections: []
    }) : "AI Service Unavailable.";

    return {
      content: cleanAiText(mockContent),
      model: "safety-mock-fallback",
      usage: { total_tokens: 0 }
    };
  }
};

/**
 * Xiaomi Adapter
 */
const callXiaomiAdapter = async (systemPrompt, userPrompt, jsonMode = false) => {
  if (!XIAOMI_API_KEY) throw new Error("Missing XIAOMI_API_KEY");

  const API_URL = "https://api.xiaomimimo.com/v1/chat/completions";
  const messages = [
    { role: "system", content: systemPrompt },
    { role: "user", content: userPrompt }
  ];

  const totalChars = systemPrompt.length + userPrompt.length;
  const dynamicTimeout = Math.min(60000, 20000 + (totalChars * 30));

  const response = await axios.post(
    API_URL,
    {
      model: "mimo-v2-flash",
      messages: messages,
      temperature: 0.7,
      stream: false
    },
    {
      headers: {
        "Content-Type": "application/json",
        "Authorization": `Bearer ${XIAOMI_API_KEY}`
      },
      timeout: dynamicTimeout
    }
  );

  if (response.data?.choices?.length > 0) {
    console.log("Success with Xiaomi Direct API");
    return {
      content: cleanAiText(response.data.choices[0].message.content),
      model: "Xiaomi MiMo V2 (Direct)",
      usage: response.data.usage || { total_tokens: 0 }
    };
  } else {
    throw new Error("Empty response from Xiaomi API");
  }
};

/**
 * OpenRouter Adapter
 */
const callOpenRouterAdapter = async (systemPrompt, userPrompt, jsonMode = false) => {
  if (!OPENROUTER_API_KEY) throw new Error("Missing OPENROUTER_API_KEY");

  const messages = [
    { role: "system", content: systemPrompt },
    { role: "user", content: userPrompt }
  ];
  const OPENROUTER_API_URL = "https://openrouter.ai/api/v1/chat/completions";

  // Retry loop across models
  for (const modelId of OPENROUTER_MODELS) {
    for (let attempt = 1; attempt <= 2; attempt++) {
      try {
        const response = await axios.post(
          OPENROUTER_API_URL,
          {
            model: modelId,
            messages: messages,
            temperature: 0.7,
            response_format: jsonMode ? { type: "json_object" } : { type: "text" }
          },
          {
            headers: {
              "Content-Type": "application/json",
              "Authorization": `Bearer ${OPENROUTER_API_KEY}`,
              "HTTP-Referer": VITE_URL,
              "X-Title": "AI Resume Builder"
            },
            timeout: 60000
          }
        );

        if (response.data?.choices?.length > 0) {
          console.log(`Success with OpenRouter: ${modelId}`);
          return {
            content: cleanAiText(response.data.choices[0].message.content),
            model: `${modelId} (OpenRouter)`,
            usage: response.data.usage || { total_tokens: 0 }
          };
        }
      } catch (error) {
        console.warn(`OpenRouter failed: ${modelId} (${error.message})`);
        if (attempt < 2) await new Promise(r => setTimeout(r, 1000));
      }
    }
  }
  throw new Error("All OpenRouter models failed.");
};

/**
 * Gemini Adapter
 */
const callGeminiAdapter = async (systemRole, userRole, jsonMode = false) => {
  const fullPrompt = `SYSTEM: ${systemRole}\n\nUSER: ${userRole}`;
  const finalPrompt = jsonMode ? fullPrompt + "\n\nIMPORTANT: OUTPUT STRICT JSON ONLY." : fullPrompt;

  const timeoutPromise = new Promise((_, reject) =>
    setTimeout(() => reject(new Error("Gemini API Timeout (60s)")), 60000)
  );

  const result = await Promise.race([
    googleModel.generateContent(finalPrompt),
    timeoutPromise
  ]);

  const response = await result.response;
  const text = response.text();

  return {
    content: cleanAiText(text),
    model: "gemini-2.0-flash-exp",
    usage: { total_tokens: 0 }
  };
};
