package com.splitbill;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SplitBillApplication {

    public static void main(String[] args) {
        SpringApplication.run(SplitBillApplication.class, args);
    }
}
