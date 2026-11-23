package com.escrow.platform.task.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.escrow.platform.task.domain.Task;
import com.escrow.platform.task.domain.TaskSearchCriteria;
import org.apache.ibatis.annotations.Param;

/**
 * {@code task} 的持久化入口。
 *
 * <p>草稿创建与编辑（v2-c2）用条件构造器即可完成；列表检索（v2-c3）需要显式列与
 * 静态排序白名单，故新增 {@link #selectTaskPage}，SQL 放在
 * {@code resources/mapper/task/TaskMapper.xml}（docs/decisions.md #42）。</p>
 *
 * <p>接口上不加 {@code @Mapper}：由 {@code MyBatisMapperScanConfig} 的
 * {@code @MapperScan} 统一扫描（docs/decisions.md #44【V2 修订】），两处都写会让
 * 「Mapper 到底在哪一层」出现两个答案。</p>
 *
 * @since V2
 */
public interface TaskMapper extends BaseMapper<Task> {

    /**
     * 任务列表分页查询（FR-TASK.LIST.01~.04，docs/decisions.md #48）。
     *
     * <p>SQL 在 {@code resources/mapper/task/TaskMapper.xml}（docs/decisions.md #42）。
     * 分页由 {@code MybatisPlusConfig} 的 {@code PaginationInnerInterceptor} 改写，
     * 本方法不写 LIMIT；排序只走 XML 的静态白名单分支，不接受字段名参数。</p>
     *
     * @param page     分页请求（页码 / 每页条数），会被拦截器原地改写
     * @param criteria 检索条件
     * @return 当前页数据与总数
     */
    Page<TaskListItemRow> selectTaskPage(Page<TaskListItemRow> page, @Param("c") TaskSearchCriteria criteria);
}
