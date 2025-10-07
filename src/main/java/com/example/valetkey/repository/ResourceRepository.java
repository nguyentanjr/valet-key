package com.example.valetkey.repository;

import com.example.valetkey.model.Resource;
import com.example.valetkey.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ResourceRepository extends JpaRepository<Resource, Long> {
    List<Resource> findByUploaderOrderByUploadedAtDesc(User uploader);
    List<Resource> findByUploader(User uploader);
}
