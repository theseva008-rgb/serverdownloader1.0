package com.example.serverdownloader;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Копирует всю корневую папку сервера в mods/serverdownloader_backups/<timestamp>/.
 * Работает в отдельном потоке, чтобы не блокировать тик сервера при больших мирах.
 */
final class BackupTask {

    private static final String BACKUPS_FOLDER_NAME = "serverdownloader_backups";
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private BackupTask() {
    }

    /**
     * @param server   сервер (нужен только чтобы безопасно вернуться в основной поток
     *                 для отправки сообщений игроку/в консоль)
     * @param feedback колбэк для текстовых сообщений о ходе и результате копирования
     */
    static void runAsync(MinecraftServer server, Consumer<String> feedback) {
        Thread thread = new Thread(() -> runBackup(server, feedback), "ServerDownloader-Backup");
        thread.setDaemon(true);
        thread.start();
    }

    private static void runBackup(MinecraftServer server, Consumer<String> feedback) {
        try {
            Path gameDir = FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize();
            Path modsDir = gameDir.resolve("mods");
            Path backupsRoot = modsDir.resolve(BACKUPS_FOLDER_NAME).toAbsolutePath().normalize();
            Files.createDirectories(backupsRoot);

            String stamp = TIMESTAMP_FORMAT.format(LocalDateTime.now());
            Path destination = backupsRoot.resolve(stamp);
            Files.createDirectories(destination);

            // Никогда не копируем саму папку с бэкапами внутрь нового бэкапа
            Set<Path> skip = new HashSet<>();
            skip.add(backupsRoot);

            long[] counters = new long[]{0L, 0L}; // [скопировано файлов, пропущено из-за ошибок]
            copyTree(gameDir, destination, skip, counters);

            String relativePath = gameDir.relativize(destination).toString();
            String resultMessage = "[ServerDownloader] Готово! Скопировано файлов: " + counters[0]
                    + (counters[1] > 0 ? ", пропущено (ошибки чтения): " + counters[1] : "")
                    + ". Папка: " + relativePath;

            runOnServerThread(server, () -> feedback.accept(resultMessage));
        } catch (IOException e) {
            ServerDownloaderMod.LOGGER.error("ServerDownloader: ошибка при создании резервной копии", e);
            runOnServerThread(server, () ->
                    feedback.accept("[ServerDownloader] Ошибка при создании копии: " + e.getMessage()));
        }
    }

    private static void runOnServerThread(MinecraftServer server, Runnable task) {
        if (server != null) {
            server.execute(task);
        } else {
            task.run();
        }
    }

    private static void copyTree(Path source, Path destination, Set<Path> skipAbsolute, long[] counters) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path normalized = dir.toAbsolutePath().normalize();
                if (skipAbsolute.contains(normalized)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Path target = destination.resolve(source.relativize(dir));
                Files.createDirectories(target);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                Path target = destination.resolve(source.relativize(file));
                try {
                    Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                    counters[0]++;
                } catch (IOException e) {
                    // Один занятый/заблокированный файл (например session.lock) не должен
                    // прерывать весь бэкап целиком — пропускаем и продолжаем.
                    counters[1]++;
                    ServerDownloaderMod.LOGGER.warn("ServerDownloader: пропущен файл {} ({})", file, e.toString());
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                counters[1]++;
                ServerDownloaderMod.LOGGER.warn("ServerDownloader: не удалось прочитать {} ({})", file, exc.toString());
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
