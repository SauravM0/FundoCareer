# Complete Guide: MySQL + Prisma + Docker Setup

## 🎯 What We're Building

A **reliable MySQL database** for your Resume Builder using Docker containers.

**Why Docker instead of XAMPP?**

- ✅ **Never corrupts** - Containers are isolated and consistent
- ✅ **One command** start/stop (vs manually starting XAMPP)
- ✅ **Data persists** - Even if you restart your computer
- ✅ **Professional** - Same setup works on any computer
- ✅ **Includes phpMyAdmin** - Just like XAMPP!

---

## 📋 Prerequisites

### 1. Install Docker Desktop

**Download:** https://www.docker.com/products/docker-desktop

**Why?** Docker runs "containers" - think of them as tiny virtual machines that hold MySQL.

**Installation Steps:**

1. Download Docker Desktop for Windows
2. Run installer
3. Restart your computer
4. Open Docker Desktop (you'll see a whale icon in system tray)

**Verify installation:**

```bash
docker --version
# Should show: Docker version 24.x.x
```

---

## 🚀 Step-by-Step Setup

### Step 1: Create Docker Configuration File

**Location:** `d:\Resume-Builder\backend-app\docker-compose.yml`

**What it does:** Tells Docker to create MySQL + phpMyAdmin containers

**Why this file?** It's like a recipe that Docker follows to set up your database

**Create this file:**

```yaml
version: "3.8"

services:
  # MySQL Database
  mysql:
    image: mysql:8.0
    container_name: resume_mysql
    restart: always
    environment:
      MYSQL_ROOT_PASSWORD: resume_password
      MYSQL_DATABASE: resume_builder
    ports:
      - "3306:3306"
    volumes:
      - mysql_data:/var/lib/mysql
    command: --default-authentication-plugin=mysql_native_password

  # phpMyAdmin (Database UI)
  phpmyadmin:
    image: phpmyadmin/phpmyadmin
    container_name: resume_phpmyadmin
    restart: always
    ports:
      - "8080:80"
    environment:
      PMA_HOST: mysql
      PMA_USER: root
      PMA_PASSWORD: resume_password
    depends_on:
      - mysql

volumes:
  mysql_data:
```

**Explanation of each part:**

- **`image: mysql:8.0`** - Downloads MySQL version 8.0 from Docker Hub
- **`MYSQL_ROOT_PASSWORD`** - Sets the root user password
- **`MYSQL_DATABASE`** - Creates `resume_builder` database automatically
- **`ports: "3306:3306"`** - Makes MySQL accessible on localhost:3306
- **`volumes: mysql_data`** - Stores database files (so data persists)
- **`phpmyadmin`** - Adds phpMyAdmin GUI (just like XAMPP!)

---

### Step 2: Configure Environment Variables

**Location:** `d:\Resume-Builder\backend-app\.env`

**What it does:** Tells your Node.js app how to connect to MySQL

**Update your `.env` file:**

```env
# Database Connection
DATABASE_URL="mysql://root:resume_password@localhost:3306/resume_builder"

# Server
NODE_ENV=development
PORT=5000
FRONTEND_URL=http://localhost:3000

# JWT Secret (for authentication)
JWT_SECRET=your-super-secret-jwt-key-change-this

# Google OAuth (configure later)
GOOGLE_CLIENT_ID=your-google-client-id
GOOGLE_CLIENT_SECRET=your-google-client-secret
GOOGLE_CALLBACK_URL=http://localhost:5000/api/auth/google/callback

# Cloudinary (for image uploads)
CLOUDINARY_CLOUD_NAME=your-cloud-name
CLOUDINARY_API_KEY=your-api-key
CLOUDINARY_API_SECRET=your-api-secret

# AI Services (at least one required)
XIAOMI_API_KEY=your-xiaomi-api-key
OPENROUTER_API_KEY=your-openrouter-api-key
GOOGLE_API_KEY=your-google-api-key
```

**Explanation:**

- **`DATABASE_URL`** format: `mysql://username:password@host:port/database`
- **username:** `root` (MySQL default admin)
- **password:** `resume_password` (matches `MYSQL_ROOT_PASSWORD` in docker-compose)
- **host:** `localhost` (your computer)
- **port:** `3306` (MySQL default port)
- **database:** `resume_builder` (created automatically)

---

### Step 3: Start the Database

**Why?** This command tells Docker to create and start MySQL + phpMyAdmin containers.

**Commands:**

```bash
# Navigate to your backend folder
cd d:\Resume-Builder\backend-app

# Start MySQL + phpMyAdmin
docker-compose up -d
```

**What happens:**

1. Docker downloads MySQL 8.0 image (first time only, ~500MB)
2. Docker downloads phpMyAdmin image
3. Creates containers
4. Starts MySQL on port 3306
5. Starts phpMyAdmin on port 8080
6. Creates `resume_builder` database automatically

**The `-d` flag means "detached" - runs in background**

**Verify it's running:**

```bash
docker ps
```

You should see two containers:

- `resume_mysql`
- `resume_phpmyadmin`

---

### Step 4: Access phpMyAdmin

**URL:** http://localhost:8080

**Login credentials:**

- **Username:** `root`
- **Password:** `resume_password`

**Why?** phpMyAdmin is a web interface to manage MySQL - just like in XAMPP!

**What you'll see:**

- Left sidebar shows `resume_builder` database (empty for now)
- You can run SQL queries, view tables, etc.

---

### Step 5: Install Node.js Dependencies

**Why?** Your app needs Prisma to talk to MySQL.

```bash
cd d:\Resume-Builder\backend-app

# Install all dependencies
npm install --legacy-peer-deps
```

**What gets installed:**

- `@prisma/client` - Prisma database client
- `prisma` - Prisma CLI tools
- All other dependencies (Express, Passport, etc.)

**If this fails with errors:**

```bash
npm install --force
```

---

### Step 6: Generate Prisma Client

**Why?** Prisma reads your `schema.prisma` file and generates TypeScript/JavaScript code to interact with MySQL.

**Command:**

```bash
npx prisma generate
```

**What happens:**

1. Reads `prisma/schema.prisma`
2. Generates client code in `node_modules/@prisma/client`
3. Creates type-safe database queries

**Output should say:**

```
✔ Generated Prisma Client to ./node_modules/@prisma/client
```

---

### Step 7: Push Schema to Database

**Why?** This creates all 10 tables in your MySQL database.

**Command:**

```bash
npx prisma db push
```

**What happens:**

1. Connects to MySQL using `DATABASE_URL`
2. Reads `schema.prisma`
3. Creates these tables:
   - `user`
   - `resume`
   - `source_resume`
   - `plan`
   - `feature`
   - `plan_feature`
   - `subscription`
   - `user_activity`
   - `resume_template`
4. Sets up relationships, indexes, constraints

**Success message:**

```
✔ Your database is now in sync with your schema.
```

**Verify in phpMyAdmin:**

- Refresh http://localhost:8080
- Click `resume_builder` database
- You should see all 9 tables!

---

### Step 8: (Optional) Open Prisma Studio

**Why?** Prisma Studio is a visual editor for your database (better than phpMyAdmin for development).

**Command:**

```bash
npx prisma studio
```

**Opens:** http://localhost:5555

**What you can do:**

- View all tables
- Add/edit/delete records
- See relationships between tables

---

### Step 9: Start Your Backend Server

**Why?** Now that the database is ready, start your Node.js application.

**Command:**

```bash
npm run dev
```

**What happens:**

1. Starts Express server on port 5000
2. Connects to MySQL via Prisma
3. Sets up routes (`/api/auth`, `/api/resume`, `/api/enhance`)

**Success message:**

```
🚀 Resume Builder Backend Server
📡 Server running on: http://localhost:5000
🔐 Google OAuth: ✗ Not configured
🤖 AI Services: ✓ Available
✓ Auth (Google OAuth + JWT)
✓ Resume (Create, Upload, Parse)
✓ Enhance (AI Optimization + Cover Letter)
```

**Test it:**

```bash
# Open browser or use curl
curl http://localhost:5000/health
```

**Response:**

```json
{
  "status": "healthy",
  "timestamp": "2026-01-31T...",
  "service": "Resume Builder API",
  "version": "2.0.0"
}
```

---

## 🔄 Daily Workflow

### Starting Everything

```bash
# 1. Start Docker Desktop (if not running)
# 2. Start database
cd d:\Resume-Builder\backend-app
docker-compose up -d

# 3. Start backend
npm run dev
```

### Stopping Everything

```bash
# Stop backend: Ctrl + C in terminal

# Stop database
docker-compose down
```

---

## 🛠️ Common Commands

### Database Management

```bash
# Start MySQL + phpMyAdmin
docker-compose up -d

# Stop containers (data persists)
docker-compose down

# Stop and DELETE all data (fresh start)
docker-compose down -v

# View logs
docker-compose logs -f mysql

# Restart MySQL
docker-compose restart mysql
```

### Prisma Commands

```bash
# Generate client (after schema changes)
npx prisma generate

# Push schema to database
npx prisma db push

# Create migration (for production)
npx prisma migrate dev --name description

# Open Prisma Studio
npx prisma studio

# Reset database (delete all data)
npx prisma db push --force-reset
```

---

## 📊 Architecture Diagram

```
┌─────────────────────────────────────────┐
│   YOUR COMPUTER                         │
│                                         │
│  ┌──────────────────────────────────┐  │
│  │  Node.js Backend (Port 5000)     │  │
│  │  - Express server                │  │
│  │  - Prisma Client                 │  │
│  └──────────┬───────────────────────┘  │
│             │ DATABASE_URL             │
│             ↓                          │
│  ┌──────────────────────────────────┐  │
│  │  Docker Containers               │  │
│  │                                  │  │
│  │  ┌────────────────────────────┐ │  │
│  │  │ MySQL (Port 3306)          │ │  │
│  │  │ - Database: resume_builder │ │  │
│  │  │ - User: root               │ │  │
│  │  └────────────────────────────┘ │  │
│  │                                  │  │
│  │  ┌────────────────────────────┐ │  │
│  │  │ phpMyAdmin (Port 8080)     │ │  │
│  │  │ - Web UI for MySQL         │ │  │
│  │  └────────────────────────────┘ │  │
│  └──────────────────────────────────┘  │
│                                         │
│  ┌──────────────────────────────────┐  │
│  │  Persistent Storage              │  │
│  │  - mysql_data volume             │  │
│  │  - Stores all database files     │  │
│  └──────────────────────────────────┘  │
└─────────────────────────────────────────┘
```

---

## ❓ Troubleshooting

### Problem: "docker-compose: command not found"

**Solution:** Docker Desktop is not installed or not running.

1. Install Docker Desktop
2. Start Docker Desktop
3. Wait for it to fully start (whale icon in system tray)

---

### Problem: "Port 3306 is already in use"

**Reason:** XAMPP MySQL is still running

**Solution:**

```bash
# Option 1: Stop XAMPP
# Then: docker-compose up -d

# Option 2: Change MySQL port in docker-compose.yml
ports:
  - "3307:3306"  # Use 3307 instead

# Update .env:
DATABASE_URL="mysql://root:resume_password@localhost:3307/resume_builder"
```

---

### Problem: "Cannot connect to MySQL"

**Check:**

1. Is Docker Desktop running?
2. Is container running? `docker ps`
3. Is password correct in `.env`?

**Solution:**

```bash
# Restart containers
docker-compose down
docker-compose up -d

# Check logs
docker-compose logs mysql
```

---

### Problem: "Prisma Client did not initialize yet"

**Solution:**

```bash
npx prisma generate
```

---

### Problem: "Table doesn't exist"

**Solution:**

```bash
npx prisma db push
```

---

## 🎓 Understanding the Technology Stack

### Why Prisma?

**Prisma** is an ORM (Object-Relational Mapping) tool.

**Without Prisma:**

```javascript
// Raw SQL - error-prone, not type-safe
const result = await db.query("SELECT * FROM user WHERE id = ?", [userId]);
```

**With Prisma:**

```javascript
// Type-safe, autocomplete, prevents errors
const user = await prisma.user.findUnique({ where: { id: userId } });
```

**Benefits:**

- ✅ Type safety (catches errors before running)
- ✅ Auto-complete in VS Code
- ✅ Prevents SQL injection
- ✅ Easy migrations
- ✅ Works with MySQL, PostgreSQL, SQLite

---

### Why Docker?

**Docker** runs apps in "containers" - isolated environments.

**Without Docker (XAMPP):**

- ❌ Corrupts easily
- ❌ Hard to reset
- ❌ Different setup on each computer
- ❌ Includes Apache (you don't need it)

**With Docker:**

- ✅ Consistent environment
- ✅ Never corrupts
- ✅ One command start/stop
- ✅ Easy to share with team
- ✅ Professional standard

---

### Why MySQL?

**MySQL** is a relational database - stores data in tables with relationships.

**Your schema has relationships:**

- A `User` has many `Resumes`
- A `Resume` belongs to one `User`
- A `Subscription` has many `UserActivities`

**MySQL is perfect for:**

- ✅ Structured data (users, resumes, subscriptions)
- ✅ Relationships between tables
- ✅ ACID compliance (data integrity)
- ✅ Widely used (lots of support)

---

## ✅ Checklist: Is Everything Working?

- [ ] Docker Desktop installed and running
- [ ] `docker-compose.yml` created
- [ ] `.env` configured with `DATABASE_URL`
- [ ] `docker-compose up -d` runs successfully
- [ ] http://localhost:8080 shows phpMyAdmin
- [ ] Can login to phpMyAdmin (root / resume_password)
- [ ] `npm install` completed
- [ ] `npx prisma generate` successful
- [ ] `npx prisma db push` created tables
- [ ] Can see 9 tables in phpMyAdmin
- [ ] `npm run dev` starts server
- [ ] http://localhost:5000/health returns "healthy"

---

## 🚀 Next Steps

1. **Configure Google OAuth** (for user login)
2. **Get AI API keys** (Xiaomi, OpenRouter, or Google)
3. **Test API endpoints** with Postman
4. **Build your frontend** to connect to this backend

---

## 📚 Additional Resources

- **Prisma Docs:** https://www.prisma.io/docs
- **Docker Docs:** https://docs.docker.com
- **MySQL Docs:** https://dev.mysql.com/doc

---

**Need help?** Check the logs:

```bash
# Backend logs
npm run dev

# MySQL logs
docker-compose logs mysql

# All Docker logs
docker-compose logs -f
``
```
