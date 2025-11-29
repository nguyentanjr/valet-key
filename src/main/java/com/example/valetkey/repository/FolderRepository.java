package com.example.valetkey.repository;

import com.example.valetkey.model.Folder;
import com.example.valetkey.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FolderRepository extends JpaRepository<Folder, Long> {
    
    // Find root folders (folders without parent) for a user
    List<Folder> findByOwnerAndParentFolderIsNullOrderByNameAsc(User owner);
    
    // Find subfolders of a parent folder
    List<Folder> findByParentFolderOrderByNameAsc(Folder parentFolder);
    
    // Find folder by owner and path
    Optional<Folder> findByOwnerAndFullPath(User owner, String fullPath);
    
    // Find folder by owner and name within a parent folder
    Optional<Folder> findByOwnerAndNameAndParentFolder(User owner, String name, Folder parentFolder);
    
    // Search folders by name (case-insensitive)
    @Query("SELECT f FROM Folder f WHERE f.owner = :owner AND LOWER(f.name) LIKE LOWER(CONCAT('%', :query, '%'))")
    List<Folder> searchByOwnerAndName(User owner, String query);
    
    // Get all folders for a user
    List<Folder> findByOwnerOrderByFullPathAsc(User owner);
    
    // Check if folder exists with the same name in the same parent
    boolean existsByOwnerAndNameAndParentFolder(User owner, String name, Folder parentFolder);
}

