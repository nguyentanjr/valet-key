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


    public void createDemoUsers() {
        if (userRepository.findUserByUsername("demo").isEmpty()) {
            User demoUser = new User("demo", "$2a$12$Ehos5dhFKC7njPf5mokPyOixJAo5A8NAKBiyZryc6iqHWy99RT5YC");
            demoUser.setRole(User.Role.ROLE_USER);
            userRepository.save(demoUser);
        }
        if (userRepository.findUserByUsername("tan1").isEmpty()) {
            User demoUser = new User("tan1", "$2a$12$Ehos5dhFKC7njPf5mokPyOixJAo5A8NAKBiyZryc6iqHWy99RT5YC");
            demoUser.setRole(User.Role.ROLE_USER);
            userRepository.save(demoUser);
        }
        if (userRepository.findUserByUsername("tan2").isEmpty()) {
            User demoUser = new User("tan2", "$2a$12$Ehos5dhFKC7njPf5mokPyOixJAo5A8NAKBiyZryc6iqHWy99RT5YC");
            demoUser.setRole(User.Role.ROLE_USER);
            userRepository.save(demoUser);
        }
        if (userRepository.findUserByUsername("tan3").isEmpty()) {
            User demoUser = new User("tan3", "$2a$12$Ehos5dhFKC7njPf5mokPyOixJAo5A8NAKBiyZryc6iqHWy99RT5YC");
            demoUser.setRole(User.Role.ROLE_USER);
            userRepository.save(demoUser);
        }
        if (userRepository.findUserByUsername("tan4").isEmpty()) {
            User demoUser = new User("tan4", "$2a$12$Ehos5dhFKC7njPf5mokPyOixJAo5A8NAKBiyZryc6iqHWy99RT5YC");
            demoUser.setRole(User.Role.ROLE_USER);
            userRepository.save(demoUser);
        }

        if (userRepository.findUserByUsername("admin").isEmpty()) {
            User adminUser = new User("admin", "$2a$12$Ehos5dhFKC7njPf5mokPyOixJAo5A8NAKBiyZryc6iqHWy99RT5YC");
            adminUser.setRole(User.Role.ROLE_ADMIN);
            userRepository.save(adminUser);
        }
    }

    public List<User> getAllUsers() {
        return userRepository.findAll();
    }

    public Optional<User> findByUsername(String username) {
        return userRepository.findUserByUsername(username);
    }

}
