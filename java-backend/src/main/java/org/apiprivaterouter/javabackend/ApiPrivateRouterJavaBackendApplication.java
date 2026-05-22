package org.apiprivaterouter.javabackend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ApiPrivateRouterJavaBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiPrivateRouterJavaBackendApplication.class, args);
    }
}
