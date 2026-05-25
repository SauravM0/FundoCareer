import app from './app.js';

const PORT = process.env.PORT || 5000;

app.listen(PORT, "0.0.0.0", () => {
  console.log("\n" + "=".repeat(60));
  console.log("🚀 Resume Builder Backend Server");
  console.log("=".repeat(60));
  console.log(`📡 Server running on: http://localhost:${PORT}`);
  console.log(`🌍 Environment: ${process.env.NODE_ENV || "development"}`);
  console.log(
    `🔐 Google OAuth: ${process.env.GOOGLE_CLIENT_ID ? "✓ Configured" : "✗ Not configured"}`,
  );
  console.log(
    `🤖 AI Services: ${process.env.XIAOMI_API_KEY || process.env.OPENROUTER_API_KEY || process.env.GOOGLE_API_KEY ? "✓ Available" : "✗ Not configured"}`,
  );
  console.log("=".repeat(60));
  console.log("\n📚 Features:");
  console.log("  ✓ Auth (Google OAuth + JWT)");
  console.log("  ✓ Resume (Create, Upload, Parse)");
  console.log("\n" + "=".repeat(60) + "\n");
});
