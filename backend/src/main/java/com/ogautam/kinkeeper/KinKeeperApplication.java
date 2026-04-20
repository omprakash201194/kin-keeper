package com.ogautam.kinkeeper;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class KinKeeperApplication {
    public static void main(String[] args) {
        SpringApplication.run(KinKeeperApplication.class, args);
    }
}
