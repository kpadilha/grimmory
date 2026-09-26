package org.booklore.model.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LibrarySeriesStat {
    private String seriesName;
    private long bookCount;
    private Integer seriesTotal;
    private long readCount;
    private long readingCount;
    private long partiallyReadCount;
    private long pausedCount;
    private long abandonedCount;
    private long wontReadCount;
    private long unreadCount;
    private String nextUnreadTitle;
    private Double avgPersonalRating;
    private Double avgExternalRating;
}
