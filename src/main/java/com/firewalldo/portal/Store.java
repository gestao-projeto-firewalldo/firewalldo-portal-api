package com.firewalldo.portal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
class Store {
    final JdbcTemplate sql;
    private final ObjectMapper json;

    Store(JdbcTemplate sql, ObjectMapper json) {
        this.sql = sql;
        this.json = json;
    }

    List<JsonNode> list(String query, Object... args) {
        return sql.query(query, (row, index) -> decode(row.getString(1)), args);
    }

    JsonNode first(String query, Object... args) {
        return list(query, args).stream().findFirst().orElse(null);
    }

    JsonNode require(String message, String query, Object... args) {
        JsonNode value = first(query, args);
        if (value == null) throw ApiError.missing(message);
        return value;
    }

    JsonNode decode(String value) {
        try {
            return json.readTree(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("JSON inválido no banco.", error);
        }
    }

    void lock(long key) {
        sql.query("SELECT pg_advisory_xact_lock(?)", row -> {}, key);
    }

    void validateMembers(List<UUID> ids) {
        if (ids.isEmpty()) return;
        var valid = sql.queryForList("SELECT id FROM members WHERE id = ANY(?::uuid[]) AND active FOR SHARE",
            UUID.class, array(ids));
        if (valid.size() != ids.size()) throw ApiError.invalid("Selecione apenas usuários ativos.");
    }

    static String array(List<?> items) {
        return "{" + String.join(",", items.stream().map(Object::toString).toList()) + "}";
    }
}
