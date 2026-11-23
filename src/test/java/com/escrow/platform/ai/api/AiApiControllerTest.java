package com.escrow.platform.ai.api;

import com.escrow.platform.ai.application.AiAccessGuard;
import com.escrow.platform.ai.application.AiFeedbackService;
import com.escrow.platform.ai.application.AiProfileService;
import com.escrow.platform.ai.application.AiQaService;
import com.escrow.platform.ai.application.AiQueryUnderstandingService;
import com.escrow.platform.ai.application.AiResults;
import com.escrow.platform.ai.application.AiSearchService;
import com.escrow.platform.ai.application.AiTaskSearchService;
import com.escrow.platform.ai.application.AiTraceService;
import com.escrow.platform.ai.domain.QuerySlots;
import com.escrow.platform.ai.domain.SlotTag;
import com.escrow.platform.common.config.JacksonConfig;
import com.escrow.platform.common.error.ApiException;
import com.escrow.platform.common.error.ErrorCode;
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

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AI 接口契约（V10）：解析/检索/画像/问答/反馈的成功路径，以及角色门禁的失败路径。
 *
 * @since V10
 */
@WebMvcTest(controllers = {AiQueryController.class, AiProfileController.class, AiQaController.class,
        AiTraceController.class})
@AutoConfigureMockMvc(addFilters = false)
@Import({JacksonConfig.class, AiAccessGuard.class})
class AiApiControllerTest {

    private static final LoginUser EMPLOYER =
            new LoginUser(2001L, "employer@example.com", UserRole.EMPLOYER, IdentityType.EMPLOYER);
    private static final LoginUser WORKER =
            new LoginUser(3001L, "worker@example.com", UserRole.WORKER, IdentityType.WORKER);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AiQueryUnderstandingService understandingService;
    @MockitoBean
    private AiSearchService searchService;
    @MockitoBean
    private AiTaskSearchService taskSearchService;
    @MockitoBean
    private AiProfileService profileService;
    @MockitoBean
    private AiQaService qaService;
    @MockitoBean
    private AiTraceService traceService;
    @MockitoBean
    private AiFeedbackService feedbackService;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /** 解析成功返回草稿ID与标签，前端据此回显。 */
    @Test
    void v10c2ParseReturnsDraftAndTags() throws Exception {
        loginAs(EMPLOYER);
        QuerySlots slots = new QuerySlots("短视频剪辑", 50000L, "北京", true, null, List.of(), 80);
        when(understandingService.parse(any(), eq("找个会剪短视频的"))).thenReturn(
                new AiResults.Understand("draft-1", slots, SlotTag.from(slots), false, "query-slot-v1"));

        mockMvc.perform(post("/api/ai/query/parse")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"找个会剪短视频的\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.draftId").value("draft-1"))
                .andExpect(jsonPath("$.data.tags").isArray());
    }

    /** 找人是雇主专属：服务者调用被拒。 */
    @Test
    void v10c4SearchRejectsWorker() throws Exception {
        loginAs(WORKER);

        mockMvc.perform(post("/api/ai/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"draftId\":\"draft-1\"}"))
                .andExpect(status().isForbidden());
    }

    /** 找活：服务者身份可检索任务，返回任务条目与链路号。 */
    @Test
    void v12c1WorkerSearchesTasks() throws Exception {
        loginAs(WORKER);
        when(taskSearchService.search(any(), eq("draft-1"), any())).thenReturn(
                new AiResults.TaskSearch(List.of(new AiResults.TaskSearchItem(9001L, "展会布展", "展会", 40000L,
                                "北京", java.time.LocalDateTime.now(), java.time.LocalDateTime.now(),
                                2, 80, "报酬 400.00 元/名额", List.of("unitPrice"))),
                        "WEIGHTED", 0, "t-task-1", null));

        mockMvc.perform(post("/api/ai/search/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"draftId\":\"draft-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].taskId").value("9001"))
                .andExpect(jsonPath("$.data.traceNo").value("t-task-1"));
    }

    /** 找活是服务者专属：雇主调用被拒。 */
    @Test
    void v12c1TaskSearchRejectsEmployer() throws Exception {
        loginAs(EMPLOYER);

        mockMvc.perform(post("/api/ai/search/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"draftId\":\"draft-1\"}"))
                .andExpect(status().isForbidden());
    }

    /** 服务者维护自己的画像。 */
    @Test
    void v10c3WorkerSavesProfile() throws Exception {
        loginAs(WORKER);
        when(profileService.upsert(any(), any())).thenReturn(new AiResults.Profile(3001L,
                List.of("短视频剪辑"), null, null, null, true, null, null,
                java.util.Map.of("expectedUnitPrice", "MISSING"), 1));

        mockMvc.perform(put("/api/ai/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"skills\":[\"短视频剪辑\"],\"weekendAvailable\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.profileVersion").value(1));
    }

    /** 雇主不能写服务者画像。 */
    @Test
    void v10c3EmployerCannotSaveProfile() throws Exception {
        loginAs(EMPLOYER);

        mockMvc.perform(put("/api/ai/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"skills\":[]}"))
                .andExpect(status().isForbidden());
    }

    /** 问答返回答案与引用。 */
    @Test
    void v10c5QaReturnsCitations() throws Exception {
        loginAs(WORKER);
        when(qaService.ask(any(), eq("验收后多久打款"))).thenReturn(
                new AiResults.Qa("验收通过后，平台在一个工作日内完成打款", List.of("KB-PAYOUT-01"), false, false, "t-1"));

        mockMvc.perform(post("/api/ai/qa")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"验收后多久打款\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.citations[0]").value("KB-PAYOUT-01"));
    }

    /** 上报点击 / 二次搜索：成功即 200。 */
    @Test
    void v10c6FeedbackAccepted() throws Exception {
        loginAs(EMPLOYER);

        mockMvc.perform(post("/api/ai/feedback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"traceNo\":\"t-1\",\"clicked\":true}"))
                .andExpect(status().isOk());
    }

    /** 回放自己的链路：返回阶段数据。 */
    @Test
    void v10c6ReplayReturnsOwnTrace() throws Exception {
        loginAs(EMPLOYER);
        when(traceService.replay(eq("t-1"), eq(2001L))).thenReturn(new AiResults.Trace(
                "t-1", 2001L, "找个会剪短视频的", "WEIGHTED", 1, 3, 0, 0L, List.of(),
                java.time.LocalDateTime.now()));

        mockMvc.perform(get("/api/ai/trace/t-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.traceNo").value("t-1"));
    }

    /** 别人的链路：403（trace 含查询词与用户ID，只允许本人查看）。 */
    @Test
    void v10c6ReplayForeignTraceIsForbidden() throws Exception {
        loginAs(WORKER);
        when(traceService.replay(eq("t-2"), anyLong()))
                .thenThrow(new ApiException(ErrorCode.FORBIDDEN, "无权查看该链路"));

        mockMvc.perform(get("/api/ai/trace/t-2"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    /** 链路不存在：404。 */
    @Test
    void v10c6ReplayMissingTraceIsNotFound() throws Exception {
        loginAs(EMPLOYER);
        when(traceService.replay(eq("missing"), anyLong()))
                .thenThrow(new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "链路记录不存在"));

        mockMvc.perform(get("/api/ai/trace/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    /** 空问题：400。 */
    @Test
    void v10c5QaRejectsBlankQuestion() throws Exception {
        loginAs(WORKER);
        when(qaService.ask(any(), eq(" ")))
                .thenThrow(new ApiException(ErrorCode.INVALID_PARAMETER, "question 不能为空"));

        mockMvc.perform(post("/api/ai/qa")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\" \"}"))
                .andExpect(status().isBadRequest());
    }

    /** 空需求：400。 */
    @Test
    void v10c2ParseRejectsBlankQuery() throws Exception {
        loginAs(EMPLOYER);
        when(understandingService.parse(any(), eq("")))
                .thenThrow(new ApiException(ErrorCode.INVALID_PARAMETER, "query 不能为空"));

        mockMvc.perform(post("/api/ai/query/parse")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    private void loginAs(LoginUser user) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
        SecurityContextHolder.setContext(context);
    }
}
