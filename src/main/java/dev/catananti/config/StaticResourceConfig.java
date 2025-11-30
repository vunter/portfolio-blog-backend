package dev.catananti.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

import java.nio.file.Paths;

@Configuration(proxyBeanMethods = false)
public class StaticResourceConfig {

    @Value("${app.upload.path:uploads}")
    private String uploadPath;

    @Bean
    public RouterFunction<ServerResponse> imageRouter() {
        return RouterFunctions.route()
                .GET("/images/{year}/{month}/{filename}", request -> {
                    String year = request.pathVariable("year");
                    String month = request.pathVariable("month");
                    String filename = request.pathVariable("filename");
                    
                    // Path traversal protection: validate resolved path stays within upload directory
                    java.nio.file.Path resolvedPath = Paths.get(uploadPath, year, month, filename).normalize();
                    java.nio.file.Path uploadRoot = Paths.get(uploadPath).normalize();
                    if (!resolvedPath.startsWith(uploadRoot)) {
                        return ServerResponse.status(org.springframework.http.HttpStatus.FORBIDDEN).build();
                    }
                    
                    Resource resource = new FileSystemResource(resolvedPath.toFile());
                    
                    if (!resource.exists()) {
                        return ServerResponse.notFound().build();
                    }
                    
                    MediaType mediaType = getMediaType(filename);
                    
                    return ServerResponse.ok()
                            .contentType(mediaType)
                            .header("Cache-Control", "public, max-age=31536000")
                            .bodyValue(resource);
                })
                .build();
    }

    private MediaType getMediaType(String filename) {
        String extension = filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();
        return switch (extension) {
            case "jpg", "jpeg" -> MediaType.IMAGE_JPEG;
            case "png" -> MediaType.IMAGE_PNG;
            case "gif" -> MediaType.IMAGE_GIF;
            case "webp" -> MediaType.parseMediaType("image/webp");
            case "svg" -> MediaType.APPLICATION_OCTET_STREAM; // SVG uploads are blocked (XSS risk) — serve as binary if any exist
            default -> MediaType.APPLICATION_OCTET_STREAM;
        };
    }
}
