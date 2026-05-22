# Resume Builder Backend - Modular Architecture

A modern, production-ready Node.js backend with feature-based modular architecture for a Resume Builder application.

## 🏗️ Architecture

```
backend-app/
├── config/                    # Configuration files
│   ├── database.config.js     # Prisma client singleton
│   └── passport.config.js     # Google OAuth strategy
├── features/                  # Feature modules (isolated business logic)
│   ├── auth/                  # Authentication feature
│   │   ├── controllers/
│   │   │   └── auth.controller.js
│   │   └── routes/
│   │       └── auth.routes.js
│   ├── resume/                # Resume management feature
│   │   ├── controllers/
│   │   │   └── resume.controller.js
│   │   ├── services/
│   │   │   ├── sourceResume.service.js
│   │   │   ├── resumeParser.service.js
│   │   │   └── aiResumeExtractor.service.js
│   │   └── routes/
│   │       └── resume.routes.js
│   └── enhance/               # Resume optimization feature
│       ├── controllers/
│       │   └── enhance.controller.js
│       ├── services/
│       │   ├── optimizer.service.js
│       │   ├── diffEngine.service.js
│       │   └── guardianLogic.service.js
│       └── routes/
│           └── enhance.routes.js
├── middlewares/               # Reusable middleware
│   ├── auth.middleware.js     # JWT authentication
│   ├── uploadImage.middleware.js
│   └── uploadFile.middleware.js
├── shared/                    # Shared utilities and services
│   ├── services/
│   │   └── ai.service.js      # Multi-provider AI service
│   └── utils/
│       ├── deepMerge.js
│       ├── parseJsonFields.js
│       └── aiJsonUtils.js
└── server.js                  # Main application entry
```

## ✨ Features

### 🔐 Authentication (`/api/auth`)

- **Google OAuth 2.0** integration with Passport.js
- **JWT token** management (stored in HTTP-only cookies)
- Session-less architecture
- Automatic account linking by email

**Endpoints:**

- `GET /api/auth/google` - Initiate OAuth flow
- `GET /api/auth/google/callback` - OAuth callback
- `GET /api/auth/me` - Get current user (protected)
- `POST /api/auth/logout` - Clear session
- `GET /api/auth/verify` - Verify token

### 📄 Resume Management (`/api/resume`)

- **CRUD operations** with Prisma ORM
- **File upload & parsing** (PDF, DOCX, DOC, TXT)
- **AI-powered text extraction** from resumes
- **Source resume library** (max 5 files per user)
- Profile image upload to Cloudinary
- Resume duplication

**Endpoints:**

- `POST /api/resume` - Create resume
- `GET /api/resume` - List user resumes
- `GET /api/resume/:id` - Get single resume
- `PATCH /api/resume/:id` - Update resume
- `DELETE /api/resume/:id` - Delete resume
- `POST /api/resume/:id/duplicate` - Duplicate resume
- `POST /api/resume/upload` - Upload & parse file
- `POST /api/resume/extract-text` - Extract text only
- `GET /api/resume/source-resumes` - Get source library

### 🚀 Resume Enhancement (`/api/enhance`)

- **AI-powered optimization** based on job descriptions
- **Guardian logic** to prevent data loss
- **Deterministic diff engine** for change tracking
- Conservative & aggressive modes
- Preserves: URLs, facts, metrics, structure

**Endpoints:**

- `POST /api/enhance/optimize` - Optimize resume for JD

## 🛠️ Tech Stack

- **Runtime:** Node.js (ES Modules)
- **Framework:** Express.js
- **Authentication:** Passport.js + JWT
- **Database:** Prisma ORM (MySQL)
- **AI:** Multi-provider fallback (Xiaomi → OpenRouter → Gemini)
- **File Processing:** Multer, pdf-parse, mammoth, word-extractor
- **Storage:** Cloudinary (images), Local (documents)

## 🚀 Getting Started

### Prerequisites

- Node.js 18+
- MySQL database
- Google OAuth credentials
- At least one AI API key (Xiaomi, OpenRouter, or Google Gemini)

### Installation

1. **Install dependencies:**

```bash
cd backend-app
npm install
```

2. **Configure environment:**

```bash
cp .env.example .env
# Edit .env with your credentials
```

3. **Set up database:**

```bash
npx prisma generate
npx prisma db push
```

4. **Run the server:**

```bash
npm run dev  # Development with nodemon
npm start    # Production
```

Server runs on `http://localhost:5000`

## 🔑 Environment Variables

See `.env.example` for complete list. Required:

```env
# Database
DATABASE_URL="mysql://user:password@localhost:3306/resume_builder"

# JWT
JWT_SECRET=your-secret-key

# Google OAuth
GOOGLE_CLIENT_ID=your-client-id
GOOGLE_CLIENT_SECRET=your-client-secret

# AI (at least one)
XIAOMI_API_KEY=your-key
# OR
OPENROUTER_API_KEY=your-key
# OR
GOOGLE_API_KEY=your-key
```

## 🔒 Security Features

- JWT tokens in HTTP-only cookies
- CORS protection with origin whitelist
- File type validation (documents/images only)
- File size limits (10MB documents, 2MB images)
- SQL injection protection via Prisma ORM
- Environment-based configuration

## 📊 AI Service Fallback Chain

1. **Xiaomi MiMo V2** (Primary - Direct API)
2. **OpenRouter** (Secondary - 4 models with retry)
3. **Google Gemini** (Tertiary - Offline fallback)
4. **Safety Mock** (Last resort)

## 🏛️ Design Patterns

- **Feature-based architecture** - Isolated business domains
- **Service layer pattern** - Business logic separation
- **Repository pattern** - Data access abstraction
- **Middleware pattern** - Cross-cutting concerns
- **Singleton pattern** - Prisma client, AI clients

## 📝 Code Structure Philosophy

- **No logic in routes** - Routes delegate to controllers
- **Controllers orchestrate** - Controllers call services
- **Services contain logic** - Pure business logic
- **Shared code extracted** - DRY principle
- **Clear naming conventions** - Self-documenting code

## 🧪 API Testing

Use the root endpoint for documentation:

```bash
curl http://localhost:5000/
```

Health check:

```bash
curl http://localhost:5000/health
```

## 📦 Core Dependencies

- `express` - Web framework
- `@prisma/client` - Database ORM
- `passport` - Authentication
- `jsonwebtoken` - JWT tokens
- `multer` - File uploads
- `axios` - HTTP client
- `openai` - AI client library
- `@google/generative-ai` - Gemini API
- `pdf-parse`, `mammoth` - Document parsing

## 🎯 Future Enhancements

- Rate limiting
- Request validation (Zod)
- API documentation (Swagger)
- Unit tests (Jest)
- Integration tests
- Docker containerization

## 📄 License

ISC

---

**Built with ❤️ using industry-standard practices**
