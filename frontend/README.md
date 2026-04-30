# A2A 智能对话系统 - 前端

基于 Vue 3 + Vite 构建的现代化前端应用。

## 🚀 快速开始

### 安装依赖

```bash
npm install
```

### 开发模式（推荐）

```bash
# 使用启动脚本（自动检查后端服务）
.\start-frontend.ps1
```

或手动启动：

```bash
npm run dev
```

访问 http://localhost:3000

### 生产构建

```bash
npm run build
```

### 预览生产版本

```bash
npm run preview
```

## 📁 项目结构

```
frontend/
├── src/
│   ├── components/          # 组件
│   │   ├── SmartChat.vue           # 智能对话组件
│   │   └── AnalyticsDashboard.vue  # 数据分析面板
│   ├── router/              # 路由配置
│   │   └── index.js
│   ├── App.vue              # 根组件
│   └── main.js              # 入口文件
├── index.html               # HTML 模板
├── vite.config.js           # Vite 配置
├── package.json             # 依赖配置
└── README.md                # 项目说明
```

## 🎯 核心功能

### 1. 智能对话（/chat）
- 💬 实时对话交互
- 💡 智能建议按钮
- 🎨 优雅的 UI 设计
- 📱 响应式布局

### 2. 数据分析（/analytics）
- 📊 A/B 测试结果可视化
- 🥧 用户偏好分布图
- 📈 点击趋势分析
- 🏆 热门建议排行榜

## 🛠️ 技术栈

- **Vue 3** - 渐进式 JavaScript 框架
- **Vite** - 下一代前端构建工具
- **Vue Router** - 官方路由管理器
- **Pinia** - Vue 状态管理
- **ECharts** - 数据可视化库
- **Axios** - HTTP 客户端

## 🔧 配置说明

### API 代理配置

在 `vite.config.js` 中配置：

```javascript
server: {
  port: 3000,
  proxy: {
    // ⭐ 新路径（推荐）
    '/assistant/api': {
      target: 'http://localhost:8080',  // API Gateway
      changeOrigin: true
    },
    // ⭐ 兼容旧路径（自动重写）
    '/api': {
      target: 'http://localhost:8080',
      changeOrigin: true,
      rewrite: (path) => path.replace(/^\/api/, '/assistant/api')
    }
  }
}
```

**路径映射关系：**
- 前端请求: `/api/auth/login` → 代理重写 → `/assistant/api/auth/login` → Gateway
- 前端请求: `/api/math/chat` → 代理重写 → `/assistant/api/math/chat` → Gateway
- Gateway 转发: `/assistant/api/math/chat` → StripPrefix=1 → `/api/math/chat` → Consumer Service

### 后端服务依赖

前端需要以下后端服务运行：

| 服务 | 端口 | 说明 |
|------|------|------|
| API Gateway | 8080 | 路由转发 + JWT 认证 |
| User Service | 8086 | 用户认证（登录/注册） |
| Consumer Service | 8082 | 聊天业务处理 |

### 环境变量

创建 `.env.local` 文件：

```env
VITE_API_BASE_URL=http://localhost:8080
```

## 📝 开发规范

### 组件命名
- 使用 PascalCase：`SmartChat.vue`
- 文件名与组件名一致

### 代码风格
- 使用 ESLint 进行代码检查
- 遵循 Vue 3 组合式 API 最佳实践

### Git 提交
```bash
git commit -m "feat: 添加智能建议功能"
git commit -m "fix: 修复路由跳转问题"
git commit -m "docs: 更新 README"
```

## 🐛 常见问题

### 1. 端口被占用

修改 `vite.config.js`：

```javascript
server: {
  port: 3001  // 改为其他端口
}
```

### 2. API 请求失败

检查后端服务是否启动：

```bash
# 检查 Gateway
Test-NetConnection localhost -Port 8080

# 检查 User Service
Test-NetConnection localhost -Port 8086

# 检查 Consumer Service
Test-NetConnection localhost -Port 8082
```

或使用监控状态检查脚本：

```bash
cd ..\monitoring
.\check-monitoring-status.ps1
```

### 3. 依赖安装失败

清除缓存后重试：

```bash
rm -rf node_modules package-lock.json
npm install
```

## 📄 许可证

MIT License
