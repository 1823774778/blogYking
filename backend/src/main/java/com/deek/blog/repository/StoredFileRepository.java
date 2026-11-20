package com.deek.blog.repository;

import com.deek.blog.entity.StoredFile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StoredFileRepository extends JpaRepository<StoredFile, Long> {

    Optional<StoredFile> findByProviderAndObjectKey(String provider, String objectKey);

    List<StoredFile> findByProvider(String provider);
}
