# Prisma Database Setup Guide

## Step-by-Step Instructions

### 1. Create Database in XAMPP

1. Start XAMPP (Apache + MySQL)
2. Open phpMyAdmin: http://localhost/phpmyadmin
3. Click "**New**" on the left sidebar
4. Database name: `resume_builder`
5. Collation: `utf8mb4_general_ci`
6. Click "**Create**"

### 2. Verify Environment Configuration

Check your `.env` file has:

```env
DATABASE_URL="mysql://root:@localhost:3306/resume_builder"
```

**Note:** If your MySQL root user has a password, use:

```env
DATABASE_URL="mysql://root:YOUR_PASSWORD@localhost:3306/resume_builder"
```

### 3. Install Dependencies

Open terminal in `d:\Resume-Builder\backend-app`:

```bash
npm install
```

If you get dependency conflicts, use:

```bash
npm install --legacy-peer-deps
```

Or force install:

```bash
npm install --force
```

### 4. Generate Prisma Client

```bash
npx prisma generate
```

This creates the Prisma client based on your `schema.prisma` file.

### 5. Push Schema to Database

```bash
npx prisma db push
```

This will:

- Connect to your MySQL database
- Create all 10 tables defined in the schema
- Set up relationships and indexes

### 6. Verify Tables Were Created

Check in phpMyAdmin - you should see these tables:

- `user`
- `resume`
- `source_resume`
- `plan`
- `feature`
- `plan_feature`
- `subscription`
- `user_activity`
- `resume_template`

### 7. (Optional) Seed Basic Data

You can manually insert basic data or use Prisma Studio:

```bash
npx prisma studio
```

This opens a browser interface to view/edit data.

### 8. Test the Connection

Start your backend server:

```bash
npm run dev
```

Visit: http://localhost:5000/health

## Database Schema Overview

### Core Tables

1. **user** - User accounts (Google OAuth)
2. **resume** - User-created resumes
3. **source_resume** - Uploaded resume files library
4. **resume_template** - Available templates

### Monetization Tables

5. **plan** - Subscription plans
6. **feature** - Available features
7. **plan_feature** - Which features are in which plans
8. **subscription** - User subscriptions
9. **user_activity** - Feature usage tracking

## Common Issues

### Issue: "Cannot connect to database"

**Solution:** Make sure XAMPP MySQL is running

### Issue: "Access denied for user 'root'"

**Solution:** Check your DATABASE_URL password is correct

### Issue: "Database 'resume_builder' does not exist"

**Solution:** Create the database first in phpMyAdmin

### Issue: "npm install fails"

**Solution:** Try:

```bash
npm install --legacy-peer-deps
```

Or delete `node_modules` and `package-lock.json` first:

```bash
rm -rf node_modules package-lock.json
npm install
```

## Next Steps

After successful setup:

1. ✅ Database created
2. ✅ Tables created
3. ✅ Prisma client generated
4. 🚀 Start building!

Run the server:

```bash
npm run dev
```

Server should start on http://localhost:5000

## Quick Test

Test if Prisma is working:

```bash
npx prisma studio
```

This opens a UI to browse your database tables.
