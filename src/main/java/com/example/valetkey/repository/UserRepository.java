package com.example.valetkey.repository;

import com.example.valetkey.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long>   {

    Optional<User> findUserByUsername(String username);

    List<User> findAll();

    User getUserById(Long id);
}
