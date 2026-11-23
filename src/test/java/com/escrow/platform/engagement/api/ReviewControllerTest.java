package com.escrow.platform.engagement.api;

import com.escrow.platform.common.config.JacksonConfig;
import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
import com.escrow.platform.common.web.PageView;
import com.escrow.platform.engagement.application.ReviewService;
import com.escrow.platform.identity.domain.IdentityType;
import com.escrow.platform.identity.domain.LoginUser;
import com.escrow.platform.identity.domain.UserRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 评价与信誉接口契约测试（V7，FR-SIGN.REVIEW.01 / FR-REPUTATION.01）。
 *
 * @since V7
 */
@WebMvcTest(controllers = ReviewController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(JacksonConfig.class)
class ReviewControllerTest {

    private static final LoginUser WORKER =
            new LoginUser(1001L, "worker@example.com", UserRole.WORKER, IdentityType.WORKER);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ReviewService reviewService;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /** 创建评价：200 + 评分与昵称。 */
    @Test
    void createShouldReturnReview() throws Exception {
        loginAs(WORKER);
        when(reviewService.create(any(), eq(7001L), any(ReviewCreateRequest.class)))
                .thenReturn(new ReviewView(1001L, "打工人", 5, "结算及时",
                        LocalDateTime.of(2026, 11, 18, 9, 0)));

        mockMvc.perform(post("/api/contracts/7001/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":5,\"comment\":\"结算及时\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reviewerId").value("1001"))
                .andExpect(jsonPath("$.data.rating").value(5))
                .andExpect(jsonPath("$.data.reviewerNickname").value("打工人"));

        verify(reviewService).create(any(), eq(7001L), eq(new ReviewCreateRequest(5, "结算及时")));
    }

    /** 重复评价：409。 */
    @Test
    void duplicateShouldConflict() throws Exception {
        loginAs(WORKER);
        when(reviewService.create(any(), eq(7001L), any(ReviewCreateRequest.class)))
                .thenThrow(new ApiException(ErrorCode.CONFLICT, "该签约已评价过"));

        mockMvc.perform(post("/api/contracts/7001/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":5}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    /** 信誉：整数比率字段按契约输出。 */
    @Test
    void reputationShouldReturnAggregates() throws Exception {
        loginAs(WORKER);
        ReviewView review = new ReviewView(2001L, "雇主甲", 5, "不错",
                LocalDateTime.of(2026, 11, 18, 9, 0));
        when(reviewService.reputation(eq(1001L), eq(1), eq(10)))
                .thenReturn(new ReputationView(1001L, 8L, 2L, 2_000, 433, 3L,
                        PageView.of(List.of(review), 1, 10, 3)));

        mockMvc.perform(get("/api/users/1001/reputation")
                        .param("page", "1")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.completedContracts").value("8"))
                .andExpect(jsonPath("$.data.timeoutRateBps").value(2000))
                .andExpect(jsonPath("$.data.averageRatingX100").value(433))
                .andExpect(jsonPath("$.data.reviews.items[0].reviewerNickname").value("雇主甲"));
    }

    private static void loginAs(LoginUser principal) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, List.of()));
        SecurityContextHolder.setContext(context);
    }
}
