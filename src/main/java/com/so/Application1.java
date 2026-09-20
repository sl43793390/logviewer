package com.so;

import cn.hutool.core.net.NetUtil;
import com.so.util.Util;
import org.mybatis.spring.annotation.MapperScan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.hazelcast.HazelcastAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Enumeration;


@SpringBootApplication(exclude = { DataSourceTransactionManagerAutoConfiguration.class,
		DataSourceAutoConfiguration.class, HazelcastAutoConfiguration.class})
@MapperScan("com.so.mapper")
public class Application1 extends org.springframework.boot.web.servlet.support.SpringBootServletInitializer{
	
	private static Logger logger = LoggerFactory.getLogger(Application1.class);
	
	public static void main(String[] args) {
		SpringApplication app = new SpringApplication(Application1.class); 
        ConfigurableApplicationContext ctx = app.run(args);
		logger.info("the application start success!!!");
		String port = ctx.getEnvironment().getProperty("server.port");
		String contextPath = ctx.getEnvironment().getProperty("server.servlet.context-path");
		if (null == contextPath) {
			contextPath = "";
		}
		System.out.println("====================address============================");
		for (NetworkInterface networkInterface : NetUtil.getNetworkInterfaces()) {
			try {
				if (networkInterface.isUp()){
					Enumeration<InetAddress> inetAddresses = networkInterface.getInetAddresses();
					while (inetAddresses.hasMoreElements()){
						InetAddress inetAddress = inetAddresses.nextElement();
						if (!inetAddress.isSiteLocalAddress()){
							continue;
						}
						System.out.println(inetAddress.getHostAddress()+":"+port+contextPath);
					}
				}
			} catch (SocketException e) {
				logger.warn("获取网卡地址失败：{}", e.getMessage());
			}
		}
		try {
			// 释放 server.sh 脚本（运行目录 + bin 目录），Linux 下顺带加执行权限。
			// 原来只在 main 里做，以 WAR 方式部署时 main 不执行，bin/server.sh 就缺了
			Util.ensureServerScript();
		} catch (Exception e) {
			logger.error("生成server.sh脚本错误{}",e.getMessage());
		}
	}

	@Override
	protected SpringApplicationBuilder configure (SpringApplicationBuilder application){
		return application.sources(Application1.class);
	}
}
