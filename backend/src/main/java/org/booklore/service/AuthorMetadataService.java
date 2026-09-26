package org.booklore.service;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.exception.ApiError;
import org.booklore.config.security.service.AuthenticationService;
import org.booklore.model.dto.AuthorDetails;
import org.booklore.model.dto.AuthorPage;
import org.booklore.model.dto.AuthorSearchResult;
import org.booklore.model.dto.AuthorSummary;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.CoverImage;
import org.booklore.model.dto.Library;
import org.booklore.model.dto.request.AuthorMatchRequest;
import org.booklore.model.dto.request.AuthorUpdateRequest;
import org.booklore.model.dto.settings.AppSettings;
import org.booklore.model.entity.AuthorEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.enums.AuditAction;
import org.booklore.model.enums.AuthorMetadataSource;
import org.booklore.model.enums.ReadStatus;
import org.booklore.repository.AuthorRepository;
import org.booklore.service.appsettings.AppSettingService;
import org.booklore.service.audit.AuditService;
import org.booklore.service.metadata.DuckDuckGoCoverService;
import org.booklore.service.metadata.parser.AuthorParser;
import org.booklore.util.FileService;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.data.domain.Pageable;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;

@Slf4j
@Service
@AllArgsConstructor
@Transactional(readOnly = true)
public class AuthorMetadataService {

    private final AuthorRepository authorRepository;
    private final Map<AuthorMetadataSource, AuthorParser> authorParserMap;
    private final AuditService auditService;
    private final FileService fileService;
    private final DuckDuckGoCoverService duckDuckGoCoverService;
    private final AuthenticationService authenticationService;
    private final AppSettingService appSettingService;

    public AuthorPage getAllAuthors(Pageable pageable) {
        BookLoreUser user = authenticationService.getAuthenticatedUser();
        List<Object[]> rows;
        long total;
        // Null libraryIds means admin, unscoped; a non-null (possibly empty) set is the
        // non-admin's assigned libraries, and loadPageEnrichment filters every enrichment
        // query by it - an author's page row is already library-scoped, but the enrichment
        // queries below join back to bm.book independently and must be scoped the same way,
        // or they leak library/category/series/addedOn/progress from books outside scope.
        Set<Long> libraryIds = null;
        if (user.getPermissions().isAdmin()) {
            rows = authorRepository.findAllWithBookCount(pageable);
            total = authorRepository.countAllAuthors();
        } else {
            libraryIds = user.getAssignedLibraries().stream()
                    .map(Library::getId)
                    .collect(Collectors.toSet());
            rows = authorRepository.findAllWithBookCountByLibraryIds(libraryIds, pageable);
            total = authorRepository.countAllAuthorsByLibraryIds(libraryIds);
        }

        Set<Long> authorIds = rows.stream()
                .map(row -> ((AuthorEntity) row[0]).getId())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        // One readdir for the whole page instead of one Files.exists() per author.
        Set<Long> authorIdsWithPhotos = fileService.listAuthorIdsWithPhotos();
        PageEnrichment enrichment = loadPageEnrichment(authorIds, user.getId(), libraryIds);

        List<AuthorSummary> summaries = rows.stream()
                .map(row -> toSummary((AuthorEntity) row[0], (Long) row[1], authorIdsWithPhotos, enrichment))
                .toList();

        return AuthorPage.builder().content(summaries).totalElements(total).build();
    }

    private AuthorSummary toSummary(AuthorEntity author, long bookCount, Set<Long> authorIdsWithPhotos, PageEnrichment enrichment) {
        Long id = author.getId();
        ProgressAggregate progress = enrichment.progressByAuthor.getOrDefault(id, ProgressAggregate.EMPTY);
        return AuthorSummary.builder()
                .id(id)
                .name(author.getName())
                .asin(author.getAsin())
                .bookCount((int) bookCount)
                .hasPhoto(authorIdsWithPhotos.contains(id))
                .libraryNames(List.copyOf(enrichment.libraryNamesByAuthor.getOrDefault(id, Set.of())))
                .categories(List.copyOf(enrichment.categoriesByAuthor.getOrDefault(id, Set.of())))
                .seriesCount(enrichment.seriesByAuthor.getOrDefault(id, Set.of()).size())
                .latestAddedOn(enrichment.latestAddedOnByAuthor.get(id))
                .lastReadTime(progress.lastReadTime)
                .readCount(progress.readCount)
                .inProgressCount(progress.inProgressCount)
                .avgPersonalRating(progress.averageRating())
                .build();
    }

    // Bulk-fetches everything the frontend used to derive from bookService.books() - scoped to the
    // current page's author IDs, so cost tracks page size, not the full author table. A non-null
    // libraryIds (non-admin) additionally scopes every query to those libraries, so an author with
    // books both inside and outside the caller's scope never enriches with the out-of-scope ones.
    private PageEnrichment loadPageEnrichment(Set<Long> authorIds, Long userId, Set<Long> libraryIds) {
        if (authorIds.isEmpty()) {
            return PageEnrichment.EMPTY;
        }

        List<AuthorRepository.AuthorLibraryRow> libraryRows = libraryIds == null
                ? authorRepository.findLibraryNamesForAuthors(authorIds)
                : authorRepository.findLibraryNamesForAuthorsByLibraryIds(authorIds, libraryIds);
        Map<Long, Set<String>> libraryNamesByAuthor = new HashMap<>();
        for (AuthorRepository.AuthorLibraryRow row : libraryRows) {
            if (row.getLibraryName() == null) {
                continue;
            }
            libraryNamesByAuthor.computeIfAbsent(row.getAuthorId(), k -> new LinkedHashSet<>()).add(row.getLibraryName());
        }

        List<AuthorRepository.AuthorCategoryRow> categoryRows = libraryIds == null
                ? authorRepository.findCategoriesForAuthors(authorIds)
                : authorRepository.findCategoriesForAuthorsByLibraryIds(authorIds, libraryIds);
        Map<Long, Set<String>> categoriesByAuthor = new HashMap<>();
        for (AuthorRepository.AuthorCategoryRow row : categoryRows) {
            categoriesByAuthor.computeIfAbsent(row.getAuthorId(), k -> new LinkedHashSet<>()).add(row.getCategoryName());
        }

        List<AuthorRepository.AuthorSeriesRow> seriesRows = libraryIds == null
                ? authorRepository.findSeriesNamesForAuthors(authorIds)
                : authorRepository.findSeriesNamesForAuthorsByLibraryIds(authorIds, libraryIds);
        Map<Long, Set<String>> seriesByAuthor = new HashMap<>();
        for (AuthorRepository.AuthorSeriesRow row : seriesRows) {
            seriesByAuthor.computeIfAbsent(row.getAuthorId(), k -> new LinkedHashSet<>()).add(row.getSeriesName().toLowerCase());
        }

        List<AuthorRepository.AuthorAddedOnRow> addedOnRows = libraryIds == null
                ? authorRepository.findAddedOnForAuthors(authorIds)
                : authorRepository.findAddedOnForAuthorsByLibraryIds(authorIds, libraryIds);
        Map<Long, Instant> latestAddedOnByAuthor = new HashMap<>();
        for (AuthorRepository.AuthorAddedOnRow row : addedOnRows) {
            if (row.getAddedOn() == null) {
                continue;
            }
            latestAddedOnByAuthor.merge(row.getAuthorId(), row.getAddedOn(), (a, b) -> a.isAfter(b) ? a : b);
        }

        List<AuthorRepository.AuthorProgressRow> progressRows = libraryIds == null
                ? authorRepository.findProgressForAuthors(authorIds, userId)
                : authorRepository.findProgressForAuthorsByLibraryIds(authorIds, userId, libraryIds);
        Map<Long, ProgressAggregate> progressByAuthor = new HashMap<>();
        for (AuthorRepository.AuthorProgressRow row : progressRows) {
            progressByAuthor.computeIfAbsent(row.getAuthorId(), k -> new ProgressAggregate()).accumulate(row);
        }

        return new PageEnrichment(libraryNamesByAuthor, categoriesByAuthor, seriesByAuthor, latestAddedOnByAuthor, progressByAuthor);
    }

    private record PageEnrichment(
            Map<Long, Set<String>> libraryNamesByAuthor,
            Map<Long, Set<String>> categoriesByAuthor,
            Map<Long, Set<String>> seriesByAuthor,
            Map<Long, Instant> latestAddedOnByAuthor,
            Map<Long, ProgressAggregate> progressByAuthor
    ) {
        static final PageEnrichment EMPTY = new PageEnrichment(Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
    }

    private static final class ProgressAggregate {
        static final ProgressAggregate EMPTY = new ProgressAggregate();

        int readCount;
        int inProgressCount;
        int ratingSum;
        int ratingCount;
        Instant lastReadTime;

        void accumulate(AuthorRepository.AuthorProgressRow row) {
            ReadStatus status = row.getReadStatus();
            if (status == ReadStatus.READ) {
                readCount++;
            } else if (status == ReadStatus.READING || status == ReadStatus.RE_READING) {
                inProgressCount++;
            }
            if (row.getPersonalRating() != null) {
                ratingSum += row.getPersonalRating();
                ratingCount++;
            }
            Instant candidate = row.getLastReadTime();
            if (candidate != null && (lastReadTime == null || candidate.isAfter(lastReadTime))) {
                lastReadTime = candidate;
            }
        }

        Double averageRating() {
            return ratingCount > 0 ? (double) ratingSum / ratingCount : null;
        }
    }

    public List<AuthorSearchResult> searchAuthorMetadata(String name, String region) {
        return authorParserMap.values().stream()
                .flatMap(provider -> {
                    List<AuthorSearchResult> results = provider.searchAuthors(name, region);
                    return results != null ? results.stream() : Stream.empty();
                })
                .toList();
    }

    public List<AuthorSearchResult> lookupAuthorByAsin(String asin, String region) {
        return authorParserMap.values().stream()
                .map(provider -> provider.getAuthorByAsin(asin, region))
                .filter(Objects::nonNull)
                .toList();
    }

    @Transactional
    public AuthorDetails matchAuthor(Long authorId, AuthorMatchRequest request) {
        AuthorEntity author = authorRepository.findById(authorId)
                .orElseThrow(() -> ApiError.AUTHOR_NOT_FOUND.createException(authorId));

        AuthorParser provider = request.getSource() != null ? authorParserMap.get(request.getSource()) : null;
        if (provider == null) {
            throw ApiError.GENERIC_BAD_REQUEST.createException("Unsupported author metadata source: " + request.getSource());
        }

        AuthorSearchResult result = provider.getAuthorByAsin(request.getAsin(), request.getRegion());
        if (result == null) {
            throw ApiError.GENERIC_BAD_REQUEST.createException("Failed to fetch author metadata");
        }

        applyMetadataResult(author, result);
        authorRepository.save(author);

        if (!author.isPhotoLocked() && result.getImageUrl() != null && !result.getImageUrl().isBlank()) {
            fileService.createAuthorThumbnailFromUrl(author.getId(), result.getImageUrl());
        }

        auditService.log(AuditAction.AUTHOR_METADATA_UPDATED, "Author", authorId,
                "Matched author '" + author.getName() + "' via " + result.getSource() + " (ASIN: " + result.getAsin() + ")");

        return toAuthorDetails(author);
    }

    @Transactional
    public AuthorDetails quickMatchAuthor(Long authorId, String region) {
        AuthorEntity author = authorRepository.findById(authorId)
                .orElseThrow(() -> ApiError.AUTHOR_NOT_FOUND.createException(authorId));

        for (AuthorParser provider : authorParserMap.values()) {
            AuthorSearchResult result = provider.quickSearch(author.getName(), region);
            if (result != null) {
                applyMetadataResult(author, result);
                authorRepository.save(author);

                if (!author.isPhotoLocked() && result.getImageUrl() != null && !result.getImageUrl().isBlank()) {
                    fileService.createAuthorThumbnailFromUrl(author.getId(), result.getImageUrl());
                }

                auditService.log(AuditAction.AUTHOR_METADATA_UPDATED, "Author", authorId,
                        "Quick-matched author '" + author.getName() + "' via " + result.getSource() + " (ASIN: " + result.getAsin() + ")");

                return toAuthorDetails(author);
            }
        }

        throw ApiError.GENERIC_BAD_REQUEST.createException("No metadata found for author: " + author.getName());
    }

    public Flux<AuthorSummary> autoMatchAuthors(List<Long> authorIds) {
        return Flux.fromIterable(authorIds)
                .concatMap(authorId ->
                        Mono.fromCallable(() -> {
                            AuthorEntity author = authorRepository.findById(authorId).orElse(null);
                            if (author == null) return null;
                            AuthorDetails details = quickMatchAuthor(authorId, "us");
                            return AuthorSummary.builder()
                                    .id(details.getId())
                                    .name(details.getName())
                                    .asin(details.getAsin())
                                    .hasPhoto(Files.exists(Paths.get(fileService.getAuthorThumbnailFile(authorId))))
                                    .build();
                        })
                        .subscribeOn(Schedulers.boundedElastic())
                        .delayElement(Duration.ofMillis(
                                ThreadLocalRandom.current().nextLong(250, 750)))
                        .onErrorResume(e -> {
                            log.warn("Failed to auto-match author ID {}: {}", authorId, e.getMessage());
                            return Mono.empty();
                        })
                )
                .filter(Objects::nonNull);
    }

    @Transactional
    public void unmatchAuthors(List<Long> authorIds) {
        for (Long authorId : authorIds) {
            AuthorEntity author = authorRepository.findById(authorId).orElse(null);
            if (author == null) continue;

            author.setDescription(null);
            author.setAsin(null);
            authorRepository.save(author);
            fileService.deleteAuthorImages(authorId);

            auditService.log(AuditAction.AUTHOR_METADATA_UPDATED, "Author", authorId,
                    "Unmatched author '" + author.getName() + "'");
        }
    }

    @Transactional
    public void deleteAuthors(List<Long> authorIds) {
        for (Long authorId : authorIds) {
            AuthorEntity author = authorRepository.findById(authorId).orElse(null);
            if (author == null) continue;

            String authorName = author.getName();

            if (author.getBookMetadataEntityList() != null) {
                for (BookMetadataEntity metadata : author.getBookMetadataEntityList()) {
                    metadata.getAuthors().remove(author);
                }
            }

            fileService.deleteAuthorImages(authorId);
            authorRepository.delete(author);

            auditService.log(AuditAction.AUTHOR_DELETED, "Author", authorId,
                    "Deleted author '" + authorName + "'");
        }
    }

    private long getMaxFileUploadSizeMb() {
        AppSettings appSettings = this.appSettingService.getAppSettings();

        Integer maxFileUploadSizeMb = appSettings.getMaxFileUploadSizeInMb();

        if (maxFileUploadSizeMb == null) {
            log.warn("Max File Upload Size is unset, defaulting to 0");
            return 0L;
        }

        return maxFileUploadSizeMb.longValue();
    }

    private void validatePhoto(MultipartFile file) {
        if (file.isEmpty()) {
            throw ApiError.INVALID_INPUT.createException("Uploaded file is empty");
        }
        long maxSizeMb = getMaxFileUploadSizeMb();
        long maxFileSize = maxSizeMb * 1024 * 1024;
        if (file.getSize() > maxFileSize) {
            throw ApiError.FILE_TOO_LARGE.createException(maxSizeMb);
        }
    }

    @Transactional
    public void uploadAuthorPhoto(Long authorId, MultipartFile file) {
        AuthorEntity author = authorRepository.findById(authorId)
                .orElseThrow(() -> ApiError.AUTHOR_NOT_FOUND.createException(authorId));

        validatePhoto(file);

        try {
            BufferedImage image = FileService.readImage(file.getInputStream());
            if (image == null) {
                throw ApiError.FILE_READ_ERROR.createException("Failed to decode image");
            }
            fileService.saveAuthorImages(image, authorId);
            image.flush();
        } catch (IOException e) {
            throw ApiError.FILE_READ_ERROR.createException(e.getMessage());
        }
    }

    @Transactional
    public AuthorDetails updateAuthor(Long authorId, AuthorUpdateRequest request) {
        AuthorEntity author = authorRepository.findById(authorId)
                .orElseThrow(() -> ApiError.AUTHOR_NOT_FOUND.createException(authorId));

        if (request.getName() != null) {
            author.setName(request.getName());
        }
        if (request.getDescription() != null) {
            author.setDescription(request.getDescription().isBlank() ? null : request.getDescription());
        }
        if (request.getAsin() != null) {
            author.setAsin(request.getAsin().isBlank() ? null : request.getAsin());
        }
        if (request.getNameLocked() != null) {
            author.setNameLocked(request.getNameLocked());
        }
        if (request.getDescriptionLocked() != null) {
            author.setDescriptionLocked(request.getDescriptionLocked());
        }
        if (request.getAsinLocked() != null) {
            author.setAsinLocked(request.getAsinLocked());
        }
        if (request.getPhotoLocked() != null) {
            author.setPhotoLocked(request.getPhotoLocked());
        }

        authorRepository.save(author);

        auditService.log(AuditAction.AUTHOR_METADATA_UPDATED, "Author", authorId,
                "Updated author '" + author.getName() + "'");

        return toAuthorDetails(author);
    }

    public Flux<CoverImage> searchAuthorPhotos(String name) {
        String searchTerm = name + " author photo portrait";
        return duckDuckGoCoverService.searchImages(searchTerm)
                .take(50);
    }

    @Transactional
    public void uploadAuthorPhotoFromUrl(Long authorId, String imageUrl) {
        AuthorEntity author = authorRepository.findById(authorId)
                .orElseThrow(() -> ApiError.AUTHOR_NOT_FOUND.createException(authorId));

        fileService.createAuthorThumbnailFromUrl(authorId, imageUrl);
    }

    public AuthorDetails getAuthorByName(String name) {
        AuthorEntity author = authorRepository.findByNameIgnoreCase(name)
                .orElseThrow(() -> ApiError.AUTHOR_NOT_FOUND.createException(name));
        verifyAuthorAccess(author.getId());
        return toAuthorDetails(author);
    }

    public AuthorDetails getAuthorDetails(Long authorId) {
        AuthorEntity author = authorRepository.findById(authorId)
                .orElseThrow(() -> ApiError.AUTHOR_NOT_FOUND.createException(authorId));
        verifyAuthorAccess(authorId);
        return toAuthorDetails(author);
    }

    public Resource getAuthorPhoto(Long authorId) {
        Path photoPath = Paths.get(fileService.getAuthorPhotoFile(authorId));
        try {
            if (Files.exists(photoPath)) {
                return new UrlResource(photoPath.toUri());
            }
        } catch (MalformedURLException e) {
            log.warn("Malformed URL for author photo path: {}", photoPath);
        }
        return null;
    }

    public Resource getAuthorThumbnail(Long authorId) {
        Path thumbnailPath = Paths.get(fileService.getAuthorThumbnailFile(authorId));
        try {
            if (Files.exists(thumbnailPath)) {
                return new UrlResource(thumbnailPath.toUri());
            }
        } catch (MalformedURLException e) {
            log.warn("Malformed URL for author thumbnail path: {}", thumbnailPath);
        }
        return null;
    }

    private void verifyAuthorAccess(Long authorId) {
        BookLoreUser user = authenticationService.getAuthenticatedUser();
        if (user.getPermissions().isAdmin()) {
            return;
        }
        Set<Long> libraryIds = user.getAssignedLibraries().stream()
                .map(Library::getId)
                .collect(Collectors.toSet());
        if (libraryIds.isEmpty() || !authorRepository.existsByIdAndLibraryIds(authorId, libraryIds)) {
            throw ApiError.AUTHOR_NOT_FOUND.createException(authorId);
        }
    }

    private void applyMetadataResult(AuthorEntity author, AuthorSearchResult result) {
        if (!author.isDescriptionLocked()) {
            author.setDescription(result.getDescription());
        }
        if (!author.isAsinLocked()) {
            author.setAsin(result.getAsin());
        }
    }

    private AuthorDetails toAuthorDetails(AuthorEntity author) {
        return AuthorDetails.builder()
                .id(author.getId())
                .name(author.getName())
                .description(author.getDescription())
                .asin(author.getAsin())
                .nameLocked(author.isNameLocked())
                .descriptionLocked(author.isDescriptionLocked())
                .asinLocked(author.isAsinLocked())
                .photoLocked(author.isPhotoLocked())
                .build();
    }
}
