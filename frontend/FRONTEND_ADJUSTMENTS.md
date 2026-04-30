# Frontend Adjustment Summary

## 📋 Changes Made

### 1. Vite Proxy Configuration (vite.config.js)
**Updated:** API proxy to support new backend path structure

**Before:**
```javascript
proxy: {
  '/api': {
    target: 'http://localhost:8082',
    changeOrigin: true,
    rewrite: (path) => path.replace(/^\/api/, '/api')
  }
}
```

**After:**
```javascript
proxy: {
  // New path (recommended)
  '/assistant/api': {
    target: 'http://localhost:8080',  // API Gateway
    changeOrigin: true
  },
  // Legacy path compatibility (auto-rewrite)
  '/api': {
    target: 'http://localhost:8080',
    changeOrigin: true,
    rewrite: (path) => path.replace(/^\/api/, '/assistant/api')
  }
}
```

### 2. Documentation Updates (README.md)
- ✅ Added startup script usage instructions
- ✅ Updated API proxy configuration examples
- ✅ Added backend service dependency table
- ✅ Updated troubleshooting section with monitoring tools

### 3. New Scripts Created
- ✅ `start-frontend.ps1` - Automated frontend setup and launch
  - Checks dependencies
  - Validates backend services
  - Starts dev server

## 🔄 Request Flow

### Authentication Flow
```
Frontend (3000)
  ↓ POST /api/auth/login
Vite Proxy (rewrites to /assistant/api/auth/login)
  ↓ 
API Gateway (8080)
  ↓ StripPrefix=1 → /api/auth/login
User Service (8086)
  ↓ JWT Token
Frontend (stores in localStorage)
```

### Chat Flow
```
Frontend (3000)
  ↓ POST /api/math/chat (with Bearer Token)
Vite Proxy (rewrites to /assistant/api/math/chat)
  ↓
API Gateway (8080)
  ↓ JWT Validation + StripPrefix=1 → /api/math/chat
Consumer Service (8082)
  ↓ Response
Frontend (displays reply)
```

## ✅ Compatibility

The frontend maintains **backward compatibility**:
- Old code using `/api/*` continues to work (auto-rewritten)
- New code can use `/assistant/api/*` directly
- No changes needed in existing Vue components

## 🚀 Quick Start

### Option 1: Using Startup Script (Recommended)
```powershell
cd frontend
.\start-frontend.ps1
```

### Option 2: Manual Start
```powershell
cd frontend
npm install  # if not already installed
npm run dev
```

Access: http://localhost:3000

## 📊 Service Status Check

Before starting frontend, ensure backend services are running:

```powershell
cd ..\monitoring
.\check-monitoring-status.ps1
```

Required services:
- ✅ API Gateway (8080)
- ✅ User Service (8086)
- ✅ Consumer Service (8082)

## 🎯 Next Steps

1. **Start Backend Services** (if not already running)
2. **Run Frontend**: `.\start-frontend.ps1`
3. **Test Login**: Use test_user_1525 / Test@123456
4. **Test Chat**: Send a message and verify response

## 🔍 Troubleshooting

### Issue: 401 Unauthorized
- **Cause**: Token expired or invalid
- **Solution**: Re-login through the login page

### Issue: 404 Not Found
- **Cause**: Backend service not running
- **Solution**: Check service status with monitoring script

### Issue: CORS Error
- **Cause**: Direct API call without proxy
- **Solution**: Ensure all API calls use `/api` or `/assistant/api` prefix

### Issue: Port 3000 in use
- **Solution**: Change port in `vite.config.js`
```javascript
server: {
  port: 3001  // Use different port
}
```
