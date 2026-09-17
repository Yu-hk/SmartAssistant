# 历史 Node 原型（非生产后端）

该目录保留作历史参考，不属于 `frontend/src` 的生产构建。默认 `npm run dev`
只启动 Vite，并通过 `vite.config.ts` 将 `/api` 转发到 Java Gateway 的 8081 端口。

`dev:legacy-server` / `legacy:server` 是显式旧入口，依赖旧 SDK 和数据库，
不保证可直接运行；不要把它们当作当前项目启动步骤，也不要复制生产凭据来运行。
本次不删除历史文件、不迁移或清理其中的数据。
