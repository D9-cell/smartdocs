package com.deepon.smartdocs;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SmartdocsApplication {

    public static void main(String[] args) {
        SpringApplication.run(SmartdocsApplication.class, args);
    }
}
