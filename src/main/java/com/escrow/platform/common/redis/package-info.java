/**
 * 本包职责：Redis 运行时能力——固定窗口限流（V1 起）。不承载账务。
 * 允许依赖：common.error（复用 ErrorCode / ApiException）。
 * 禁止依赖：任何业务域包。
 * @since V1
 */
package com.escrow.platform.common.redis;
