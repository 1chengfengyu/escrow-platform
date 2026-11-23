package com.escrow.platform.common.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis Mapper 扫描配置。
 *
 * <p>不要搬回启动类：启动类是 {@code @WebMvcTest} 等切片测试的 root configuration，
 * 永远会被解析；切片过滤器只排除普通 {@code @Configuration}。一旦把 {@code @MapperScan}
 * 放回启动类，所有 {@code @WebMvcTest} 都会重新扫描 Mapper，上下文加载失败随之复发。</p>
 *
 * <p>Mapper 接口一律放在各域的 {@code infrastructure.mapper} 下，此处统一扫描；
 * V0 尚无 Mapper，空扫描不会报错。</p>
 *
 * @since V2
 */
@Configuration
@MapperScan("com.escrow.platform.**.infrastructure.mapper")
public class MyBatisMapperScanConfig {
}
