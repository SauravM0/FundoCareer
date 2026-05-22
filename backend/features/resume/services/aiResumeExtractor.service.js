import { generateAIContent } from '../../../shared/services/ai.service.js';
import { cleanAndParseJSON } from '../../../shared/utils/aiJsonUtils.js';

/**
 * AI Resume Extractor Service
 * Uses AI to extract structured data from resume text
 */

/**
 * Extract structured resume data from raw text using AI
 * @param {string} text - Raw text extracted from resume
 * @returns {Promise<Object>} - Structured resume data matching our Resume model
 */
export const extractResumeData = async (text) => {
  try {
    console.log("[AI Resume Extractor] Starting extraction...");

    const prompt = `You are a HIGH-FIDELITY Resume Parser.
    
**GOAL:** Convert the resume text below into structured JSON compatible with our Resume Builder.

**CRITICAL RULES:**
1. **NAME EXTRACTION:** The Name is usually the VERY FIRST LINE. If you see "Name: ...", use it. If NOT, assume the first capitalized line is the Name. Ignore "Resume" or "CV" labels.
2. **SKILLS:** "Technical Expertise", "Core Competencies", "Technologies" -> Map ALL to "skills". Start a new Skill Object for EACH item. Example: "Cloud: AWS, Azure" -> [{"name": "AWS", "level": "Cloud"}, {"name": "Azure", "level": "Cloud"}]. DO NOT return long strings.
3. **MISSING SECTIONS:** Certifications, Achievements, Awards -> Map to "custom" sections.
4. **MAPPING:** Use "role", "company", "city" for experience. Use "school" for education institution.

**OUTPUT SCHEMA:**
{
  "personal_info": {
    "full_name": "String",
    "email": "String",
    "phone": "String",
    "city": "String",
    "country": "String",
    "jobTitle": "String",
    "linkedin": "String",
    "github": "String",
    "website": "String"
  },
  "professional_summary": "String",
  "sections": [
    {
      "id": "experience",
      "type": "experience",
      "title": "Work Experience",
      "data": [
        {
          "id": "job-1",
          "role": "String",
          "company": "String",
          "city": "String",
          "start_date": "String",
          "end_date": "String",
          "is_current": Boolean,
          "description": "String",
          "bullets": ["String"]
        }
      ]
    },
    {
      "id": "education",
      "type": "education",
      "title": "Education",
      "data": [
        { 
          "id": "edu-1", 
          "school": "String", 
          "degree": "String", 
          "city": "String",
          "start_date": "String", 
          "end_date": "String",
          "description": "String"
        }
      ]
    },
    {
      "id": "skills",
      "type": "skills",
      "title": "Skills",
      "data": [ 
        { "name": "String", "level": "String" }
      ]
    },
    {
      "id": "project",
      "type": "project",
      "title": "Projects",
      "data": [
        { "id": "proj-1", "name": "String", "role": "String", "tech": "String", "link": "String", "description": "String", "bullets": ["String"] }
      ]
    }
  ]
}

**RESUME TEXT:**
${text}`;

    const { content, model, usage } = await generateAIContent([
      { role: "system", content: "You are a Resume Parser. Output strict JSON." },
      { role: "user", content: prompt }
    ], true); // Enable JSON Mode

    const parsedData = cleanAndParseJSON(content);

    // Heuristic name fallback
    const lines = text.split('\n').map(l => l.trim()).filter(l => l.length > 2);
    let heuristicName = "";

    if (lines.length > 0) {
      let potential = lines[0];
      if (["resume", "cv", "curriculum vitae", "bio"].includes(potential.toLowerCase())) {
        potential = lines[1] || "";
      }
      if (potential && potential.length < 50 && !/\d{3}/.test(potential)) {
        heuristicName = potential;
      }
    }

    if (heuristicName) {
      const aiName = parsedData.personal_info?.full_name || "";
      if (!aiName || aiName === "String" || aiName === "Candidate" || aiName.length < 3) {
        console.log(`[AI Extractor] Using heuristic name: '${heuristicName}'`);
        parsedData.personal_info = parsedData.personal_info || {};
        parsedData.personal_info.full_name = heuristicName;
      }
    }

    // Skills repair - ensure objects not strings
    if (parsedData.sections) {
      parsedData.sections = parsedData.sections.map(sec => {
        if (sec.type === 'skills' && Array.isArray(sec.data)) {
          sec.data = sec.data.map(item => {
            if (typeof item === 'string') {
              return { name: item, level: 'Experienced' };
            }
            return item;
          });
        }
        return sec;
      });
    }

    console.log(`[AI Extractor] Success with ${model}`);
    return { ...parsedData, meta: { model, usage } };

  } catch (error) {
    console.error("[AI Resume Extractor] Error:", error);
    return {
      personal_info: { full_name: "Extraction Failed" },
      professional_summary: "",
      sections: [],
      meta: { error: error.message }
    };
  }
};
