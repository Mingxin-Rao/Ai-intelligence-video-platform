package com.example.server;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;

@SpringBootApplication
@MapperScan("com.example.server.mapper")
public class ServerApplication {

	public static void main(String[] args) {
		// Use the Builder pattern to force specification as a SERVLET web application
		// This thoroughly prevents it from failing to find the web server
		new SpringApplicationBuilder(ServerApplication.class)
				.web(WebApplicationType.SERVLET)
				.run(args);
	}
}