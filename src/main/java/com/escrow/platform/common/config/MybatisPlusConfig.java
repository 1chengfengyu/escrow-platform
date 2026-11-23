package com.escrow.platform.common.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 插件配置（v2-c3）：只注册分页拦截器。
 *
 * <p>{@code mybatis-plus-jsqlparser} 3.5.9 起从 starter 拆出（docs/decisions.md #4），
 * 少了它编译期不报错、第一次分页查询才抛 {@code ClassNotFoundException}。</p>
 *
 * <p>{@code overflow=false} 是 FR-TASK.LIST.02 的硬要求：页码越界必须返回空列表，
 * 不能回到第一页；{@code maxLimit=100} 是第二道闸，防止绕过 Controller 的 size 校验。</p>
 *
 * <p>本类是普通 {@code @Configuration}，会被 {@code @WebMvcTest} 切片过滤掉
 * （docs/decisions.md #44【V2 修订】），因此不会把 SqlSessionFactory 依赖带进切片。</p>
 *
 * @since V2
 */
@Configuration
public class MybatisPlusConfig {

    /** 单页上限，与 FR-TASK.LIST.02 的「最大 100 条」一致。 */
    private static final long MAX_PAGE_SIZE = 100L;

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        pagination.setMaxLimit(MAX_PAGE_SIZE);
        pagination.setOverflow(false);
        interceptor.addInnerInterceptor(pagination);
        return interceptor;
    }
}
