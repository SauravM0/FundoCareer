/**
 * Parse JSON Fields Utility
 * Handles parsing of stringified JSON fields in request body
 */
export const parseJsonFields = (body) => {
  if (!body) return body;
  
  // Handle resumeData wrapper
  if (typeof body.resumeData === "string") {
    try {
      const parsed = JSON.parse(body.resumeData);
      body = { ...body, ...parsed };
      delete body.resumeData;
    } catch (err) {
      console.warn("Invalid resumeData JSON");
    }
  }

  // Parse known JSON fields
  const jsonFields = ["personal_info", "sections", "formatting"];
  jsonFields.forEach((key) => {
    if (typeof body[key] === "string") {
      try {
        body[key] = JSON.parse(body[key]);
      } catch {
        // Keep as string if parsing fails
      }
    }
  });
  
  return body;
};
