package com.skybook.assistant;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class BookingAssistantApplication {

    public static void main(String[] args) {
        SpringApplication.run(BookingAssistantApplication.class, args);
    }
}
