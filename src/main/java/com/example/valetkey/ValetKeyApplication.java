package com.example.valetkey;

import com.example.valetkey.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ValetKeyApplication implements CommandLineRunner {

    @Autowired
    private UserService userService;

    public static void main(String[] args) {
        SpringApplication.run(ValetKeyApplication.class, args);
    }

    @Override
    public void run(String... args) throws Exception {
        userService.createDemoUsers();
    }

}
