// java
package com.dadoirie.trueauth.server;

import com.dadoirie.trueauth.config.TrueauthConfig;
import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 服务端调用 hasJoined 校验正版并获取最终 UUID 与皮肤属性
 */
public final class SessionCheck {
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final Gson GSON = new Gson();

    public record Property(String name, String value, String signature) {}

    public record HasJoinedResult(UUID uuid, String name, List<Property> properties) {}

    private static class HasJoinedJson {
        String id; // 无连字符的 UUID
        String name;
        List<Prop> properties;
    }
    private static class Prop {
        String name;
        String value;
        @SerializedName("signature")
        String sig;
    }

    /**
     * 异步版本：不阻塞调用线程，返回 CompletableFuture\<Optional\<HasJoinedResult\>\>
     */
    public static CompletableFuture<Optional<HasJoinedResult>> hasJoinedAsync(String username, String serverId, String ip) {
        String url = TrueauthConfig.COMMON.mojangReverseProxy.get()+"/session/minecraft/hasJoined"
                + "?username=" + URLEncoder.encode(username, StandardCharsets.UTF_8)
                + "&serverId=" + URLEncoder.encode(serverId, StandardCharsets.UTF_8);

        if (com.dadoirie.trueauth.config.TrueauthConfig.debug()) {
            System.out.println("[TrueAuth][DEBUG] Requesting Mojang validation API: " + url);
        }

        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).GET().build();

        return HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> {
                    if (com.dadoirie.trueauth.config.TrueauthConfig.debug()) {
                        System.out.println("[TrueAuth][DEBUG] Mojang response status code: " + resp.statusCode());
                    }

                    if (resp.statusCode() != 200) {
                        if (com.dadoirie.trueauth.config.TrueauthConfig.debug()) {
                            System.out.println("[TrueAuth][DEBUG] Validation failed, status code not 200, returning empty");
                        }
                        return Optional.<HasJoinedResult>empty();
                    }

                    HasJoinedJson dto = GSON.fromJson(resp.body(), HasJoinedJson.class);
                    if (dto == null || dto.id == null) {
                        if (com.dadoirie.trueauth.config.TrueauthConfig.debug()) {
                            System.out.println("[TrueAuth][DEBUG] JSON parsing failed or no UUID obtained, returning empty");
                        }
                        return Optional.<HasJoinedResult>empty();
                    }

                    UUID uuid = UUID.fromString(dto.id.replaceFirst(
                            "(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{12})",
                            "$1-$2-$3-$4-$5"));

                    if (com.dadoirie.trueauth.config.TrueauthConfig.debug()) {
                        System.out.println("[TrueAuth][DEBUG] Validation successful, UUID: " + uuid + ", player name: " + dto.name);
                    }

                    List<Property> props = dto.properties == null ? List.of() :
                            dto.properties.stream()
                                    .map(p -> new Property(p.name, p.value, p.sig))
                                    .toList();

                    return Optional.of(new HasJoinedResult(uuid, dto.name, props));
                })
                .exceptionally(ex -> {
                    if (com.dadoirie.trueauth.config.TrueauthConfig.debug()) {
                        System.out.println("[TrueAuth][DEBUG] Exception during Mojang communication or parsing: " + ex);
                    }
                    return Optional.empty();
                });
    }

    // Keep sync method (if needed) or remove
    public static Optional<HasJoinedResult> hasJoined(String username, String serverId, String ip) throws Exception {
        // Keep original sync implementation (or internally call hasJoinedAsync().get(), as needed)
        throw new UnsupportedOperationException("Sync hasJoined is deprecated, please use hasJoinedAsync");
    }

    private SessionCheck() {}
}
