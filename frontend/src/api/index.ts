/**
 * API 层统一导出
 * 
 * 所有后端 API 调用通过此模块访问，不再在 hooks/components 中直接使用 fetch。
 * 集中管理 base URL、错误处理、请求头。
 */
export { apiClient, ApiError } from './client';
export * as sessions from './sessions';
export * as admin from './admin';
