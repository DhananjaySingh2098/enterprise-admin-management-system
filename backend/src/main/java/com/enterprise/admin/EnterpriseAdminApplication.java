package com.enterprise.admin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class EnterpriseAdminApplication {

    public static void main(String[] args) {
        SpringApplication.run(EnterpriseAdminApplication.class, args);
    }
}
