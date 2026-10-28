package com.school.bnb;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@MapperScan("com.school.bnb.mapper")
public class BnbApplication {

    public static void main(String[] args) {
        SpringApplication.run(BnbApplication.class, args);
    }
}
