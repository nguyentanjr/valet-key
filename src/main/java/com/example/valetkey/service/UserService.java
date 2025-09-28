package com.example.valetkey.service;

import com.example.valetkey.model.User;
import com.example.valetkey.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class UserService {

    @Autowired
    private UserRepository userRepository;

    public Optional<User> login(String username, String password) {
        Optional<User> user = userRepository.findUserByUsername(username);
        if (user.isPresent() && user.get().getPassword().equals(password)) {
            return user;
        }
        return Optional.empty();
    }

    public void createDemoUsers() {
        if (userRepository.findUserByUsername("demo").isEmpty()) {
            User demoUser = new User("demo", "demo123");
            userRepository.save(demoUser);
        }
        if (userRepository.findUserByUsername("tan1").isEmpty()) {
            User demoUser = new User("tan1", "1");
            userRepository.save(demoUser);
        }
        if (userRepository.findUserByUsername("tan2").isEmpty()) {
            User demoUser = new User("tan2", "1");
            userRepository.save(demoUser);
        }
        if (userRepository.findUserByUsername("tan3").isEmpty()) {
            User demoUser = new User("tan3", "1");
            userRepository.save(demoUser);
        }
        if (userRepository.findUserByUsername("tan4").isEmpty()) {
            User demoUser = new User("tan4", "1");
            userRepository.save(demoUser);
        }
        if (userRepository.findUserByUsername("tan5").isEmpty()) {
            User demoUser = new User("tan5", "1");
            userRepository.save(demoUser);
        }

        if (userRepository.findUserByUsername("tan").isEmpty()) {
            User demoUser = new User("tan", "tan");
            userRepository.save(demoUser);
        }

        if (userRepository.findUserByUsername("admin").isEmpty()) {
            User adminUser = new User("admin", "admin123");
            userRepository.save(adminUser);
        }
    }

    public List<User> getAllUsers() {
        return userRepository.findAll();
    }

}
