package dev.catananti.repository;

import dev.catananti.entity.MediaAsset;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface MediaAssetRepository extends R2dbcRepository<MediaAsset, Long> {

    Flux<MediaAsset> findByUploaderIdOrderByCreatedAtDesc(Long uploaderId);

    Flux<MediaAsset> findByPurposeOrderByCreatedAtDesc(String purpose);

    @Query("SELECT * FROM media_assets WHERE uploader_id = :uploaderId ORDER BY created_at DESC LIMIT :limit OFFSET :offset")
    Flux<MediaAsset> findByUploaderIdPaginated(Long uploaderId, int limit, long offset);

    @Query("SELECT * FROM media_assets ORDER BY created_at DESC LIMIT :limit OFFSET :offset")
    Flux<MediaAsset> findAllPaginated(int limit, long offset);

    @Query("SELECT * FROM media_assets WHERE purpose = :purpose ORDER BY created_at DESC LIMIT :limit OFFSET :offset")
    Flux<MediaAsset> findByPurposePaginated(String purpose, int limit, long offset);

    @Query("SELECT COUNT(*) FROM media_assets")
    Mono<Long> countAll();

    @Query("SELECT COUNT(*) FROM media_assets WHERE uploader_id = :uploaderId")
    Mono<Long> countByUploaderId(Long uploaderId);

    @Query("SELECT COUNT(*) FROM media_assets WHERE purpose = :purpose")
    Mono<Long> countByPurpose(String purpose);

    Mono<MediaAsset> findByStorageKey(String storageKey);

    Mono<MediaAsset> findByUrl(String url);
}
