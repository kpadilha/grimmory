package org.booklore.service.kobo;

import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.KoboSyncSettings;
import org.booklore.model.dto.kobo.Entitlement;
import org.booklore.model.dto.settings.AppSettings;
import org.booklore.model.dto.settings.KoboSettings;
import org.booklore.model.entity.KoboLibrarySnapshotEntity;
import org.booklore.model.entity.UserBookProgressEntity;
import org.booklore.model.entity.BookEntity;
import org.booklore.repository.KoboDeletedBookProgressRepository;
import org.booklore.repository.UserBookProgressRepository;
import org.booklore.service.appsettings.AppSettingService;
import org.booklore.util.kobo.BookloreSyncTokenGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("KoboLibrarySyncService Tests")
class KoboLibrarySyncServiceTest {

    @Mock
    private BookloreSyncTokenGenerator tokenGenerator;
    @Mock
    private KoboLibrarySnapshotService koboLibrarySnapshotService;
    @Mock
    private KoboEntitlementService entitlementService;
    @Mock
    private KoboDeletedBookProgressRepository koboDeletedBookProgressRepository;
    @Mock
    private UserBookProgressRepository userBookProgressRepository;
    @Mock
    private KoboServerProxy koboServerProxy;
    @Mock
    private AppSettingService appSettingService;
    @Mock
    private KoboSettingsService koboSettingsService;

    @InjectMocks
    private KoboLibrarySyncService service;

    private KoboSyncSettings testSettings;

    @BeforeEach
    void setUp() {
        testSettings = new KoboSyncSettings();
        when(koboSettingsService.getCurrentUserSettings()).thenReturn(testSettings);
    }

    @Nested
    @DisplayName("Kobo Store Forwarding")
    class KoboStoreForwarding {

        private final ObjectMapper mapper = JsonMapper.builder().build();

        @AfterEach
        void clearRequest() {
            RequestContextHolder.resetRequestAttributes();
        }

        @Test
        @DisplayName("Store sync items reach the device verbatim, whatever their type")
        void storeItemsPassThroughVerbatim() {
            String newEntitlement = """
                    {"NewEntitlement":{"BookEntitlement":{"Id":"b1","Accessibility":"Full","ActivePeriod":{"From":"2026-01-01T00:00:00Z"}},"BookMetadata":{"Title":"Store Book","ExtraStoreField":[1,2]}}}""";
            String changedReadingState = """
                    {"ChangedReadingState":{"ReadingState":{"EntitlementId":"43ea0e6a-0000-0000-0000-000000000000","Created":"2026-02-06T22:47:44.0000000Z","LastModified":"2026-10-05T11:59:56.0000000Z","StatusInfo":{"LastModified":"2026-10-05T11:59:56.0000000Z","Status":"Reading","TimesStartedReading":1,"LastTimeStartedReading":"2026-10-05T11:00:00.0000000Z"},"Statistics":{"LastModified":"2026-10-05T11:59:56.0000000Z","SpentReadingMinutes":0,"RemainingTimeMinutes":958},"CurrentBookmark":{"LastModified":"2026-10-05T11:59:56.0000000Z","ProgressPercent":17,"ContentSourceProgressPercent":0,"Location":{"Value":"kobo.1.1","Type":"KoboSpan","Source":"OEBPS/Text/Secret-8.xhtml"}},"PriorityTimestamp":"2026-10-05T11:59:56.0000000Z"}}}""";
            String newTag = """
                    {"NewTag":{"Tag":{"Id":"t1","Name":"Store shelf","Type":"UserTag","Items":[{"RevisionId":"b1","Type":"ProductRevisionTagItem"}]}}}""";
            JsonNode storeBody = mapper.readTree("[" + newEntitlement + "," + changedReadingState + "," + newTag + "]");

            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
            KoboLibrarySnapshotEntity snapshot = new KoboLibrarySnapshotEntity();
            snapshot.setId("snap-1");
            when(koboLibrarySnapshotService.findByIdAndUserId(any(), any())).thenReturn(Optional.empty());
            when(koboLibrarySnapshotService.create(1L)).thenReturn(snapshot);
            when(koboLibrarySnapshotService.getUnsyncedBooks(any(), any())).thenReturn(Page.empty());
            when(appSettingService.getAppSettings()).thenReturn(
                    AppSettings.builder().koboSettings(KoboSettings.builder().forwardToKoboStore(true).build()).build());
            when(koboServerProxy.proxyCurrentRequest(null, true)).thenReturn(ResponseEntity.ok(storeBody));
            when(tokenGenerator.toBase64(any())).thenReturn("token");

            ResponseEntity<List<Entitlement>> response = service.syncLibrary(BookLoreUser.builder().id(1L).build(), "t");

            List<Entitlement> items = response.getBody();
            assertNotNull(items);
            assertEquals(3, items.size());
            for (int i = 0; i < 3; i++) {
                assertEquals(storeBody.get(i), mapper.readTree(mapper.writeValueAsString(items.get(i))));
            }
        }
    }

    @Nested
    @DisplayName("Progress Sync Detection")
    class ProgressSyncDetection {

        @Test
        @DisplayName("Should detect unsynced Kobo progress when never sent")
        void needsKoboProgressSync_neverSent() {
            UserBookProgressEntity progress = createProgress(1L);
            progress.setKoboProgressReceivedTime(Instant.now());
            progress.setKoboProgressSentTime(null);

            assertTrue(needsKoboProgressSync(progress));
        }

        @Test
        @DisplayName("Should detect unsynced Kobo progress when received after sent")
        void needsKoboProgressSync_receivedAfterSent() {
            UserBookProgressEntity progress = createProgress(1L);
            progress.setKoboProgressReceivedTime(Instant.now());
            progress.setKoboProgressSentTime(Instant.now().minusSeconds(60));

            assertTrue(needsKoboProgressSync(progress));
        }

        @Test
        @DisplayName("Should not detect sync needed when sent after received")
        void needsKoboProgressSync_alreadySynced() {
            UserBookProgressEntity progress = createProgress(1L);
            progress.setKoboProgressReceivedTime(Instant.now().minusSeconds(60));
            progress.setKoboProgressSentTime(Instant.now());

            assertFalse(needsKoboProgressSync(progress));
        }

        @Test
        @DisplayName("Should not detect sync needed when no progress received")
        void needsKoboProgressSync_noProgressReceived() {
            UserBookProgressEntity progress = createProgress(1L);
            progress.setKoboProgressReceivedTime(null);

            assertFalse(needsKoboProgressSync(progress));
        }
    }

    @Nested
    @DisplayName("Status Sync Detection")
    class StatusSyncDetection {

        @Test
        @DisplayName("Should detect unsynced status when never sent")
        void needsStatusSync_neverSent() {
            UserBookProgressEntity progress = createProgress(1L);
            progress.setReadStatusModifiedTime(Instant.now());
            progress.setKoboStatusSentTime(null);

            assertTrue(needsStatusSync(progress));
        }

        @Test
        @DisplayName("Should detect unsynced status when modified after sent")
        void needsStatusSync_modifiedAfterSent() {
            UserBookProgressEntity progress = createProgress(1L);
            progress.setReadStatusModifiedTime(Instant.now());
            progress.setKoboStatusSentTime(Instant.now().minusSeconds(60));

            assertTrue(needsStatusSync(progress));
        }

        @Test
        @DisplayName("Should not detect status sync when already sent")
        void needsStatusSync_alreadySynced() {
            UserBookProgressEntity progress = createProgress(1L);
            progress.setReadStatusModifiedTime(Instant.now().minusSeconds(60));
            progress.setKoboStatusSentTime(Instant.now());

            assertFalse(needsStatusSync(progress));
        }

        @Test
        @DisplayName("Should not detect status sync when no modification time")
        void needsStatusSync_noModificationTime() {
            UserBookProgressEntity progress = createProgress(1L);
            progress.setReadStatusModifiedTime(null);

            assertFalse(needsStatusSync(progress));
        }
    }

    @Nested
    @DisplayName("Web Reader Progress Sync")
    class WebReaderProgressSync {

        @Test
        @DisplayName("Should detect web reader progress needing sync when lastReadTime after sent")
        void needsProgressSync_webReaderNewer() {
            UserBookProgressEntity progress = createProgress(1L);
            progress.setEpubProgress("epubcfi(/6/4)");
            progress.setEpubProgressPercent(65f);
            progress.setLastReadTime(Instant.now());
            progress.setKoboProgressSentTime(Instant.now().minusSeconds(60));
            progress.setKoboProgressReceivedTime(Instant.now().minusSeconds(120));

            assertTrue(needsProgressSync(progress));
        }

        @Test
        @DisplayName("Should detect href-only web reader progress when lastReadTime after sent")
        void needsProgressSync_webReaderHrefOnly() {
            UserBookProgressEntity progress = createProgress(1L);
            progress.setEpubProgressHref("OPS/chapter3.xhtml");
            progress.setEpubProgressPercent(65f);
            progress.setLastReadTime(Instant.now());
            progress.setKoboProgressSentTime(Instant.now().minusSeconds(60));
            progress.setKoboProgressReceivedTime(Instant.now().minusSeconds(120));

            assertTrue(needsProgressSync(progress));
        }

        @Test
        @DisplayName("Should not bounce Kobo progress back immediately")
        void needsProgressSync_preventBounce() {
            UserBookProgressEntity progress = createProgress(1L);
            progress.setEpubProgress("epubcfi(/6/4)");
            progress.setEpubProgressPercent(65f);
            progress.setLastReadTime(Instant.now().minusSeconds(120));
            progress.setKoboProgressSentTime(Instant.now().minusSeconds(60));
            progress.setKoboProgressReceivedTime(Instant.now());

            assertFalse(needsProgressSyncWebReader(progress));
        }
    }

    private UserBookProgressEntity createProgress(Long bookId) {
        BookEntity book = new BookEntity();
        book.setId(bookId);
        UserBookProgressEntity progress = new UserBookProgressEntity();
        progress.setBook(book);
        return progress;
    }

    private boolean needsStatusSync(UserBookProgressEntity progress) {
        Instant modifiedTime = progress.getReadStatusModifiedTime();
        if (modifiedTime == null) return false;
        Instant sentTime = progress.getKoboStatusSentTime();
        return sentTime == null || modifiedTime.isAfter(sentTime);
    }

    private boolean needsKoboProgressSync(UserBookProgressEntity progress) {
        Instant sentTime = progress.getKoboProgressSentTime();
        Instant receivedTime = progress.getKoboProgressReceivedTime();
        return receivedTime != null && (sentTime == null || receivedTime.isAfter(sentTime));
    }

    private boolean needsProgressSync(UserBookProgressEntity progress) {
        if (needsKoboProgressSync(progress)) return true;

        if (progress.getEpubProgressPercent() != null) {
            Instant sentTime = progress.getKoboProgressSentTime();
            Instant lastReadTime = progress.getLastReadTime();
            if (lastReadTime != null && (sentTime == null || lastReadTime.isAfter(sentTime))) {
                return true;
            }
        }
        return false;
    }

    private boolean needsProgressSyncWebReader(UserBookProgressEntity progress) {
        if (progress.getEpubProgress() == null || progress.getEpubProgressPercent() == null) return false;

        Instant lastReadTime = progress.getLastReadTime();
        Instant sentTime = progress.getKoboProgressSentTime();
        Instant receivedTime = progress.getKoboProgressReceivedTime();

        if (lastReadTime == null) return false;
        if (sentTime != null && !lastReadTime.isAfter(sentTime)) return false;
        return receivedTime == null || lastReadTime.isAfter(receivedTime);
    }
}
