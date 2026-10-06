package org.booklore.config;

import org.booklore.model.enums.AuthorMetadataSource;
import org.booklore.service.metadata.parser.AudnexusAuthorParser;
import org.booklore.service.metadata.parser.AuthorParser;
import org.booklore.service.metadata.parser.OpenLibraryAuthorParser;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.EnumMap;
import java.util.Map;

@Configuration
public class AuthorParserConfig {

    @Bean
    // EnumMap iterates in declaration order, which is the quick-match provider priority.
    public Map<AuthorMetadataSource, AuthorParser> authorParserMap(AudnexusAuthorParser audnexusAuthorParser,
                                                                   OpenLibraryAuthorParser openLibraryAuthorParser) {
        Map<AuthorMetadataSource, AuthorParser> parsers = new EnumMap<>(AuthorMetadataSource.class);
        parsers.put(AuthorMetadataSource.AUDNEXUS, audnexusAuthorParser);
        parsers.put(AuthorMetadataSource.OPENLIBRARY, openLibraryAuthorParser);
        return parsers;
    }
}
