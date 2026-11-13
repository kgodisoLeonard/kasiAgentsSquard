package com.spaza;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class SpazaApplication {
    public static void main(String[] args) {
        SpringApplication.run(SpazaApplication.class, args);
        System.out.println("\nKASI AGENTS SQUAD BACKEND (Java) · Spaza to the World running on http://localhost:8080\n");
    }
}
