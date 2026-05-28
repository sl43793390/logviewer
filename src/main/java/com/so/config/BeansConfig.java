package com.so.config;

import java.util.ResourceBundle;

import javax.sql.DataSource;

import org.apache.ibatis.session.SqlSessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.web.socket.server.standard.ServerEndpointExporter;

@Configuration
public class BeansConfig {

	
	private String url = ResourceBundle.getBundle("application").getString("spring.datasource.url");
	private String driverClass = ResourceBundle.getBundle("application").getString("spring.datasource.driver-class-name");;
	
	@Bean(destroyMethod = "close")
	public DataSource demoDataSource() {
		HikariConfig config = new HikariConfig();
		config.setDriverClassName(driverClass);
		config.setJdbcUrl(url);
		HikariDataSource ds = new HikariDataSource(config);
		return ds;
	}
	@Bean
	public SqlSessionFactory demoSqlSessionFactory(@Qualifier("demoDataSource") DataSource demoDataSource) throws Exception {
		MybatisSqlSessionFactoryBean sqlSessionFactoryBean = new MybatisSqlSessionFactoryBean();
		sqlSessionFactoryBean.setDataSource(demoDataSource);
		PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
//		sqlSessionFactoryBean.setMapperLocations(resolver.getResources("classpath:/mapping/demo/*.xml"));
		sqlSessionFactoryBean.setConfigLocation(resolver.getResource("classpath:/dataAccessConfiguration.xml"));
		return sqlSessionFactoryBean.getObject();
	}

}
