package com.escrow.platform.common.config;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.InnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 分页拦截器装配（v2-c3 卡片 §3.2 / §8 第 4 行）。
 *
 * <p>锁死三条运行期事实：只有一个内部拦截器、类型是分页、上限 100 且不回到第一页。
 * 少任何一条都会让 FR-TASK.LIST.02 的「越界返回空列表」失效。</p>
 *
 * @since V2
 */
class MybatisPlusConfigTest {

    private final MybatisPlusConfig config = new MybatisPlusConfig();

    /** 拦截器链有且只有分页这一个内部拦截器。 */
    @Test
    void shouldRegisterExactlyOneInnerInterceptor() {
        List<InnerInterceptor> interceptors = config.mybatisPlusInterceptor().getInterceptors();
        assertThat(interceptors).hasSize(1);
    }

    /** 唯一的内部拦截器必须是分页拦截器。 */
    @Test
    void innerInterceptorShouldBePagination() {
        MybatisPlusInterceptor interceptor = config.mybatisPlusInterceptor();
        assertThat(interceptor.getInterceptors().get(0)).isInstanceOf(PaginationInnerInterceptor.class);
    }

    /** 单页上限 100，且 overflow=false（页码越界返回空列表，不回到第一页）。 */
    @Test
    void paginationShouldCapAt100AndNotOverflow() {
        MybatisPlusInterceptor interceptor = config.mybatisPlusInterceptor();
        PaginationInnerInterceptor pagination =
                (PaginationInnerInterceptor) interceptor.getInterceptors().get(0);
        assertThat(pagination.getMaxLimit()).isEqualTo(100L);
        assertThat(pagination.isOverflow()).isFalse();
    }
}
