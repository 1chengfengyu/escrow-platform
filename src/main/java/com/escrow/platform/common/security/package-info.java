/**
 * 本包职责：安全骨架——过滤器链配置、401/403 统一响应、口令哈希策略，V1 起。
 * 允许依赖：common.web、common.error（复用 ApiResponse / ErrorCode）。
 * 禁止依赖：任何业务域包（V8 拆服务时本包不外移）。
 * @since V1
 */
package com.escrow.platform.common.security;
