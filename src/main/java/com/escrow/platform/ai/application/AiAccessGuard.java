package com.escrow.platform.ai.application;

import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
import com.escrow.platform.identity.domain.IdentityType;
import com.escrow.platform.identity.domain.LoginUser;
import org.springframework.stereotype.Component;

/**
 * AI 接口的角色门禁（V10-c5，D5 裁决）。
 *
 * <p>找人 = 雇主，找活 = 服务者，规则问答双方都可用。门禁放在应用层而不是 Controller：
 * 以后加新入口不会漏判；{@code userId} 只从会话取，请求体里没有这个字段可填。</p>
 *
 * @since V10
 */
@Component
public class AiAccessGuard {

    /** 找人：只有雇主身份可检索候选人。 */
    public void requireEmployer(LoginUser current) {
        if (current == null || current.currentIdentity() != IdentityType.EMPLOYER) {
            throw new ApiException(ErrorCode.FORBIDDEN, "仅雇主身份可使用候选人检索");
        }
    }

    /** 找活 / 维护画像：只有服务者身份可写自己的画像。 */
    public void requireWorker(LoginUser current) {
        if (current == null || current.currentIdentity() != IdentityType.WORKER) {
            throw new ApiException(ErrorCode.FORBIDDEN, "仅服务者身份可维护服务者画像");
        }
    }
}
