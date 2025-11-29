package com.example.valetkey.repository;

import com.example.valetkey.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface UploadJobRepository extends JpaRepository<UploadJob, Long> {

    // Find job by jobId
    Optional<UploadJob> findByJobId(String jobId);

    // Find all jobs by user
    List<UploadJob> findByUserOrderByCreatedAtDesc(User user);

    // Find active jobs (PENDING or UPLOADING) by user
    @Query("SELECT j FROM UploadJob j WHERE j.user = :user AND j.status IN ('PENDING', 'UPLOADING') ORDER BY j.createdAt DESC")
    List<UploadJob> findActiveJobsByUser(User user);

    // Find all active jobs (for monitoring)
    @Query("SELECT j FROM UploadJob j WHERE j.status IN ('PENDING', 'UPLOADING') ORDER BY j.createdAt ASC")
    List<UploadJob> findAllActiveJobs();

    // Find jobs by status
    List<UploadJob> findByStatus(UploadJob.JobStatus status);

    // Find stale jobs (stuck in UPLOADING for more than X hours)
    @Query("SELECT j FROM UploadJob j WHERE j.status = 'UPLOADING' AND j.startedAt < :thresholdTime")
    List<UploadJob> findStaleJobs(LocalDateTime thresholdTime);

    // Count active jobs by user
    @Query("SELECT COUNT(j) FROM UploadJob j WHERE j.user = :user AND j.status IN ('PENDING', 'UPLOADING')")
    Long countActiveJobsByUser(User user);

    // Delete old completed jobs
    @Query("DELETE FROM UploadJob j WHERE j.status IN ('COMPLETED', 'FAILED', 'CANCELLED') AND j.completedAt < :thresholdTime")
    void deleteOldCompletedJobs(LocalDateTime thresholdTime);
}


