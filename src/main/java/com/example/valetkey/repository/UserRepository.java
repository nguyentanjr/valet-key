package com.example.valetkey.repository;

import com.example.valetkey.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long>   {

    Optional<User> findUserByUsername(String username);

    List<User> findAll();

    User getUserById(Long id);

    /**
     * Atomic update storage_used to avoid deadlock
     * Uses database-level atomic operation
     */
    @Modifying
    @Query("UPDATE User u SET u.storageUsed = u.storageUsed + :fileSize WHERE u.id = :userId")
    int incrementStorageUsed(@Param("userId") Long userId, @Param("fileSize") Long fileSize);

    /**
     * Atomic decrement storage_used
     */
    @Modifying
    @Query("UPDATE User u SET u.storageUsed = u.storageUsed - :fileSize WHERE u.id = :userId")
    int decrementStorageUsed(@Param("userId") Long userId, @Param("fileSize") Long fileSize);

    /**
     * Get user with pessimistic lock to prevent concurrent updates
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.id = :userId")
    Optional<User> findByIdWithLock(@Param("userId") Long userId);
}
