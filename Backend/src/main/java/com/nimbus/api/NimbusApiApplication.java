package com.nimbus.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class NimbusApiApplication {

	public static void main(String[] args) {
		SpringApplication.run(NimbusApiApplication.class, args);
	}

}
