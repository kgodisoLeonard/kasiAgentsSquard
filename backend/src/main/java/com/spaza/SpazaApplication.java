package com.spaza;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class SpazaApplication {
    public static void main(String[] args) {
        SpringApplication.run(SpazaApplication.class, args);
        System.out.println("\nSPAZA TO THE WORLD BACKEND (Java) running on http://localhost:8080\n");
    }
}
