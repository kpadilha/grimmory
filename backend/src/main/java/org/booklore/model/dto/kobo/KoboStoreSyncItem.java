package org.booklore.model.dto.kobo;

import com.fasterxml.jackson.annotation.JsonValue;
import tools.jackson.databind.JsonNode;

/** A Kobo store sync item, serialised to the device exactly as the store sent it. */
public record KoboStoreSyncItem(@JsonValue JsonNode node) implements Entitlement {
}
