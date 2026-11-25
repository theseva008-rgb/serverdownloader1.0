package com.example.serverdownloader;

import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Pattern;

/**
 * ServerDownloader
 * ----------------
 * Серверный мод: по команде "/server download" (или по сообщению в чат
 * ".server download" от игрока с правами оператора уровня 4) копирует
 * ВСЁ содержимое корневой папки сервера (мир, config, сам mods/ со всеми
 * модами/плагинами, server.properties, whitelist, ops.json и т.д.) в новую
 * папку-бэкап внутри mods/serverdownloader_backups/<дата_время>/.
 *
 * Папка с бэкапами сама себя не копирует (иначе каждый следующий бэкап
 * раздувался бы, включая в себя все предыдущие).
 */
public class ServerDownloaderMod implements ModInitializer {

    public static final String MOD_ID = "serverdownloader";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    // Срабатывает на сообщение вида ".server download" (регистр и лишние пробелы не важны)
    private static final Pattern CHAT_TRIGGER = Pattern.compile("^\\.server\\s+download$", Pattern.CASE_INSENSITIVE);

    // Требуемый уровень прав оператора (4 = полный доступ, как у владельца сервера)
    private static final int REQUIRED_PERMISSION_LEVEL = 4;

    @Override
    public void onInitialize() {
        LOGGER.info("ServerDownloader загружен");

        registerCommand();
        registerChatTrigger();
    }

    private void registerCommand() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(Commands.literal("server")
                        .requires(source -> source.hasPermission(REQUIRED_PERMISSION_LEVEL))
                        .then(Commands.literal("download")
                                .executes(ServerDownloaderMod::executeDownloadCommand))));
    }

    private static int executeDownloadCommand(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        source.sendSuccess(() -> Component.literal("[ServerDownloader] Начинаю копирование файлов сервера..."), true);

        BackupTask.runAsync(
                source.getServer(),
                message -> source.sendSystemMessage(Component.literal(message))
        );
        return 1;
    }

    private void registerChatTrigger() {
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, boundChatType) -> {
            String raw = message.signedContent();
            if (raw == null || !CHAT_TRIGGER.matcher(raw.trim()).matches()) {
                // Обычное сообщение — пропускаем в чат как есть
                return true;
            }

            boolean isOperator = sender.getServer() != null
                    && sender.getServer().getPlayerList().isOp(sender.getGameProfile());

            if (isOperator) {
                sender.sendSystemMessage(Component.literal("[ServerDownloader] Начинаю копирование файлов сервера..."));
                BackupTask.runAsync(
                        sender.getServer(),
                        msg -> sender.sendSystemMessage(Component.literal(msg))
                );
            } else {
                sender.sendSystemMessage(Component.literal(
                        "[ServerDownloader] Недостаточно прав (нужен оператор уровня " + REQUIRED_PERMISSION_LEVEL + ")."));
            }

            // Не даём команде ".server download" уйти в публичный чат
            return false;
        });
    }
}
