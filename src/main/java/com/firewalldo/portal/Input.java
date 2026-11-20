package com.firewalldo.portal;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

record Input(JsonNode value) {
    static final String[] AREAS = {"programacao", "design", "conteudo", "marketing", "infraestrutura", "gestao"};
    static final String[] TASK_STATUS = {"backlog", "a_fazer", "em_andamento", "concluido"};

    Input {
        if (!value.isObject()) throw ApiError.invalid("Envie um objeto JSON.");
    }

    static LocalDate today() { return LocalDate.now(ZoneId.of("America/Sao_Paulo")); }
    boolean has(String field) { return value.has(field); }

    void only(String... allowed) {
        Set<String> keys = Set.of(allowed);
        value.fieldNames().forEachRemaining(field -> {
            if (!keys.contains(field)) throw ApiError.invalid("Campo inválido: " + field);
        });
    }

    String text(String field, int min, int max) {
        JsonNode node = value.path(field);
        String text = node.isTextual() ? node.textValue().strip() : "";
        if (!node.isTextual() || text.length() < min || text.length() > max || text.indexOf(0) >= 0) {
            throw fieldError(field);
        }
        return text;
    }

    String password(String field, int min) {
        JsonNode node = value.path(field);
        if (!node.isTextual() || node.textValue().length() < min || node.textValue().length() > 128) throw fieldError(field);
        return node.textValue();
    }

    String choice(String field, String... choices) {
        String value = text(field, 1, 80);
        if (!Set.of(choices).contains(value)) throw fieldError(field);
        return value;
    }

    boolean bool(String field) {
        JsonNode node = value.path(field);
        if (!node.isBoolean()) throw fieldError(field);
        return node.booleanValue();
    }

    int number(String field, int min, int max) {
        JsonNode node = value.path(field);
        if (!node.isIntegralNumber() || !node.canConvertToInt() || node.intValue() < min || node.intValue() > max) throw fieldError(field);
        return node.intValue();
    }

    LocalDate date(String field) {
        try {
            String value = text(field, 10, 10);
            LocalDate date = LocalDate.parse(value);
            if (date.getYear() < 1) throw fieldError(field);
            return date;
        } catch (java.time.DateTimeException error) {
            throw fieldError(field);
        }
    }

    UUID optionalId(String field) {
        return value.path(field).isNull() ? null : id(text(field, 36, 36));
    }

    LocalDate optionalDate(String field) { return value.path(field).isNull() ? null : date(field); }

    static UUID id(String value) {
        try {
            UUID id = UUID.fromString(value);
            if (!id.toString().equalsIgnoreCase(value)) throw ApiError.invalid("Identificador inválido.");
            return id;
        } catch (IllegalArgumentException error) {
            throw ApiError.invalid("Identificador inválido.");
        }
    }

    String key(String field) {
        String key = text(field, 1, 80);
        if (!key.matches("[A-Za-z0-9_-]+")) throw fieldError(field);
        return key;
    }

    String url(String field, boolean allowEmpty) {
        String url = text(field, allowEmpty ? 0 : 1, 2048);
        if (allowEmpty && url.isEmpty()) return url;
        try {
            URI uri = URI.create(url);
            if (uri.getHost() != null && uri.getUserInfo() == null
                && ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))) return url;
        } catch (IllegalArgumentException error) {
            throw fieldError(field);
        }
        throw fieldError(field);
    }

    /** Logo enviada como data URL (png, jpeg, webp ou gif) com até 256 KB. Vazio remove a imagem. */
    String image(String field) {
        String image = text(field, 0, 400_000);
        if (image.isEmpty()) return image;
        var match = java.util.regex.Pattern.compile("^data:image/(png|jpeg|webp|gif);base64,([A-Za-z0-9+/]+={0,2})$").matcher(image);
        if (!match.matches()) throw ApiError.invalid("Envie uma imagem PNG, JPG, WEBP ou GIF.");
        try {
            if (java.util.Base64.getDecoder().decode(match.group(2)).length > 256 * 1024) throw ApiError.invalid("A imagem deve ter no máximo 256 KB.");
        } catch (IllegalArgumentException error) {
            throw fieldError(field);
        }
        return image;
    }

    /** Arquivo enviado como data URL (base64). Confere tipo declarado, assinatura real e tamanho. */
    record Upload(byte[] bytes, String type) {}

    Upload upload(String field, int maxBytes, String... types) {
        String data = text(field, 1, maxBytes / 3 * 4 + 200);
        var match = java.util.regex.Pattern.compile("^data:([a-z]+/[a-z0-9.+-]+);base64,([A-Za-z0-9+/]+={0,2})$").matcher(data);
        if (!match.matches() || !Set.of(types).contains(match.group(1))) throw ApiError.invalid("Formato de arquivo não permitido.");
        byte[] bytes;
        try { bytes = java.util.Base64.getDecoder().decode(match.group(2)); }
        catch (IllegalArgumentException error) { throw fieldError(field); }
        if (bytes.length == 0) throw fieldError(field);
        if (bytes.length > maxBytes) throw ApiError.invalid("O arquivo deve ter no máximo " + (maxBytes / 1024 / 1024 > 0 ? maxBytes / 1024 / 1024 + " MB." : maxBytes / 1024 + " KB."));
        if (!signature(bytes, match.group(1))) throw ApiError.invalid("O conteúdo do arquivo não corresponde ao formato informado.");
        return new Upload(bytes, match.group(1));
    }

    private static boolean signature(byte[] b, String type) {
        return switch (type) {
            case "image/png" -> b.length > 8 && (b[0] & 0xff) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G';
            case "image/jpeg" -> b.length > 3 && (b[0] & 0xff) == 0xFF && (b[1] & 0xff) == 0xD8 && (b[2] & 0xff) == 0xFF;
            case "image/webp" -> b.length > 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P';
            case "application/pdf" -> b.length > 4 && b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F';
            default -> false;
        };
    }

    /** Valor em reais enviado como centavos inteiros. */
    long cents(String field) {
        JsonNode node = value.path(field);
        if (!node.isIntegralNumber() || !node.canConvertToLong() || node.longValue() < 1 || node.longValue() > 100_000_000_000L) throw fieldError(field);
        return node.longValue();
    }

    /** Texto opcional: ausente ou null viram "". */
    String optionalText(String field, int max) {
        return !value.has(field) || value.path(field).isNull() ? "" : text(field, 0, max);
    }

    List<UUID> ids(String field) {
        return unique(list(value.path(field), 100, node -> {
            if (!node.isTextual()) throw fieldError(field);
            return id(node.textValue());
        }));
    }

    static <T> List<T> list(JsonNode node, int limit, Function<JsonNode, T> map) {
        if (!node.isArray() || node.size() > limit) throw ApiError.invalid("Lista inválida ou acima do limite.");
        List<T> result = new ArrayList<>();
        node.forEach(item -> result.add(map.apply(item)));
        return result;
    }

    static <T> List<T> unique(List<T> items) {
        if (new HashSet<>(items).size() != items.size()) throw ApiError.invalid("Remova os itens duplicados.");
        return items;
    }

    private static ApiError fieldError(String field) { return ApiError.invalid("Valor inválido para " + field + "."); }
}
