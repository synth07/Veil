package foundry.veil.ext;

import foundry.veil.Veil;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.IoSupplier;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

public interface PackResourcesExtension {

    void veil$listResources(PackResourceConsumer consumer);

    @Nullable
    IoSupplier<InputStream> veil$getIcon();

    default Stream<PackResources> veil$listPacks() {
        return Stream.of((PackResources) this);
    }

    static @Nullable Path findDevPath(Path root, Path file) {
        // We're in a Zip file
        if (file.getFileSystem() != FileSystems.getDefault()) {
            return null;
        }

        // Attempt to find actual resources, not gradle copy
        if (file.getNameCount() > 3) {
            try {
                Path buildRoot = root;
                String sourceRoot = null;
                Path localPath = null;
                while (buildRoot != null && buildRoot.getFileName() != null && !"build".equals(buildRoot.getFileName().toString())) {
                    Path parent = buildRoot.getParent();
                    if (parent != null && parent.getFileName() != null && parent.getFileName().toString().equals("resources")) {
                        sourceRoot = buildRoot.getFileName().toString();
                        localPath = buildRoot.relativize(file);
                    }
                    buildRoot = parent;
                }

                // We aren't in a build output, so don't try
                if (buildRoot == null || sourceRoot == null) {
                    return null;
                }

                Path defaultRoot = buildRoot.getParent();
                if (defaultRoot == null) {
                    return null;
                }

                Path buildPath = defaultRoot.resolve("src").resolve(sourceRoot).resolve("resources").resolve(localPath);
                if (Files.exists(buildPath)) {
                    return buildPath;
                }

                Path projectRoot = defaultRoot.getParent();
                if (projectRoot == null) {
                    return null;
                }

                // This sucks, but we have to scan all roots :/
                try (Stream<Path> walk = Files.list(projectRoot).filter(path -> Files.isDirectory(path) && !path.startsWith(defaultRoot))) {
                    for (Path possibleRoot : walk.toList()) {
                        Path resourcesPath = possibleRoot.resolve("src").resolve(sourceRoot).resolve("resources").resolve(localPath);
                        if (Files.exists(resourcesPath)) {
                            return resourcesPath;
                        }
                    }
                }
            } catch (Exception e) {
                Veil.LOGGER.error("Failed to find IDE source root", e);
            }
        }
        return null;
    }

    @FunctionalInterface
    interface PackResourceConsumer {

        void accept(@Nullable PackType packType, ResourceLocation name, Path packPath, Path filePath, @Nullable Path modResourcePath);
    }
}
