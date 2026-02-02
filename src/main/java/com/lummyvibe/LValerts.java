package com.lummyvibe;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.event.Listener;
import org.bukkit.event.EventHandler;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.time.LocalDateTime;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.io.OutputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class LValerts extends JavaPlugin implements Listener {

    // ============ КОНСТАНТЫ ============
    private static final int DIAMOND_THRESHOLD = 45;
    private static final int SESSION_TIME = 300; // 5 минут
    private static final int INACTIVITY_TIME = 30; // 30 секунд
    private static final int STACK_SIZE = 64;
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final int MAX_LOG_INTERVAL = 20; // Максимум 1 лог в 20 алмазов

    // ============ КОНФИГУРАЦИЯ ============
    private String telegramToken;
    private List<String> telegramAlertRecipients;
    private String telegramReportRecipient;
    private String discordToken;
    private String discordChannelId;
    private String discordYourId;
    private String mysqlHost;
    private int mysqlPort;
    private String mysqlDatabase;
    private String mysqlUsername;
    private String mysqlPassword;

    // ============ БАЗА ДАННЫХ ============
    private Connection mysqlConnection;

    // ============ ПУЛ ПОТОКОВ ДЛЯ АСИНХРОННОЙ ОТПРАВКИ ============
    private ExecutorService executorService;

    // ============ СЕССИИ ИГРОКОВ ============
    private class PlayerSession {
        LocalDateTime startTime;
        LocalDateTime lastActivity;
        int diamonds = 0;
        int ores = 0;
        int ancientDebris = 0;
        int gold = 0;
        boolean alertSent = false;
        Map<Integer, Integer> diamondsPerMinute = new HashMap<>();
        int lastLoggedDiamonds = 0;

        PlayerSession() {
            startTime = LocalDateTime.now();
            lastActivity = LocalDateTime.now();
        }

        void update(int diamonds, int ores, int ancientDebris, int gold) {
            this.diamonds += diamonds;
            this.ores += ores;
            this.ancientDebris += ancientDebris;
            this.gold += gold;
            this.lastActivity = LocalDateTime.now();

            // Записываем алмазы за текущую минуту
            int currentMinute = (int) Duration.between(startTime, LocalDateTime.now()).toMinutes();
            diamondsPerMinute.put(currentMinute,
                    diamondsPerMinute.getOrDefault(currentMinute, 0) + diamonds);
        }

        boolean isExpired() {
            Duration inactivity = Duration.between(lastActivity, LocalDateTime.now());
            Duration totalDuration = Duration.between(startTime, LocalDateTime.now());
            return inactivity.getSeconds() > INACTIVITY_TIME ||
                    totalDuration.getSeconds() > SESSION_TIME;
        }

        Map<String, Object> getStats() {
            Map<String, Object> stats = new HashMap<>();
            stats.put("player", "");
            stats.put("startTime", startTime);
            stats.put("endTime", lastActivity);
            stats.put("diamonds", diamonds);
            stats.put("ores", ores);
            stats.put("ancientDebris", ancientDebris);
            stats.put("gold", gold);
            stats.put("diamondsPerMinute", diamondsPerMinute);
            return stats;
        }

        Map.Entry<Integer, Integer> getMostProductiveMinute() {
            return diamondsPerMinute.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .orElse(null);
        }

        double getDiamondsPerMinuteAverage() {
            if (diamondsPerMinute.isEmpty()) return 0.0;
            long totalMinutes = diamondsPerMinute.keySet().stream().max(Integer::compareTo).orElse(0) + 1;
            return (double) diamonds / totalMinutes;
        }

        boolean shouldLog() {
            if (diamonds - lastLoggedDiamonds >= MAX_LOG_INTERVAL) {
                lastLoggedDiamonds = diamonds;
                return true;
            }
            return false;
        }
    }

    private Map<String, PlayerSession> playerSessions = new ConcurrentHashMap<>();

    // ============ ОСНОВНЫЕ МЕТОДЫ ============

    @Override
    public void onEnable() {
        getLogger().info("╔══════════════════════════════════════════╗");
        getLogger().info("║         🤖 LVAlerts - ЗАПУСК!           ║");
        getLogger().info("║ Telegram + Discord + MySQL + CoreProtect ║");
        getLogger().info("║   СТАТИСТИКА + ИСТОРИЯ + ПРОДУКТИВНОСТЬ  ║");
        getLogger().info("╚══════════════════════════════════════════╝");

        // Создаем пул потоков для асинхронной отправки
        executorService = Executors.newFixedThreadPool(3);

        // Сохраняем конфиг по умолчанию если его нет
        saveDefaultConfig();
        // Загружаем конфиг
        loadConfig();

        // Регистрируем команды
        Objects.requireNonNull(getCommand("lvalerts")).setExecutor(this);

        getLogger().info("📁 Загружена конфигурация:");
        getLogger().info("   Telegram получателей: " + telegramAlertRecipients.size());
        getLogger().info("   Discord канал статистики: " + discordChannelId);
        getLogger().info("   Discord ваш ID: " + discordYourId);
        getLogger().info("   MySQL хост: " + mysqlHost);

        // 1. Подключаемся к MySQL
        if (!connectToMySQL()) {
            getLogger().severe("Не удалось подключиться к MySQL! Плагин отключен.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        createSessionsTable();

        // 2. Регистрируем слушатель событий
        getServer().getPluginManager().registerEvents(this, this);

        // 3. Запускаем проверку сессий каждые 5 секунд
        new BukkitRunnable() {
            @Override
            public void run() {
                checkSessions();
            }
        }.runTaskTimer(this, 100L, 100L);

        getLogger().info("✅ Плагин успешно запущен!");
        getLogger().info("💎 Порог алмазов: " + DIAMOND_THRESHOLD);
        getLogger().info("⏱️  Время сессии: " + SESSION_TIME + " сек");
        getLogger().info("📊 Хранение истории: ВКЛЮЧЕНО");
        getLogger().info("📈 Расчет продуктивности: ВКЛЮЧЕНО");

        // Тестовая отправка при запуске
        new BukkitRunnable() {
            @Override
            public void run() {
                getLogger().info("🔄 Отправка тестовых сообщений...");
                sendTelegramToAll("🤖 *LVAlerts запущен!*\nПлагин мониторинга добычи работает.");
                sendDiscordToChannel("🤖 **LVAlerts запущен!**\nПлагин мониторинга добычи работает.");
            }
        }.runTaskLater(this, 60L);
    }

    private void loadConfig() {
        FileConfiguration config = getConfig();

        // Загружаем Telegram настройки
        telegramToken = config.getString("telegram.token", "");
        telegramAlertRecipients = config.getStringList("telegram.alert-recipients");
        telegramReportRecipient = config.getString("telegram.report-recipient", "");

        // Загружаем Discord настройки
        discordToken = config.getString("discord.token", "");
        discordChannelId = config.getString("discord.channel-id", "");
        discordYourId = config.getString("discord.your-id", "");

        // Загружаем MySQL настройки
        mysqlHost = config.getString("mysql.host", "");
        mysqlPort = config.getInt("mysql.port", 3306);
        mysqlDatabase = config.getString("mysql.database", "");
        mysqlUsername = config.getString("mysql.username", "");
        mysqlPassword = config.getString("mysql.password", "");

        // Проверяем обязательные поля
        if (telegramToken.isEmpty()) {
            getLogger().warning("⚠️ Telegram token не установлен в config.yml!");
        }
        if (discordToken.isEmpty()) {
            getLogger().warning("⚠️ Discord token не установлен в config.yml!");
        }
        if (mysqlHost.isEmpty()) {
            getLogger().warning("⚠️ MySQL host не установлен в config.yml!");
        }
    }

    @Override
    public void onDisable() {
        // Останавливаем пул потоков
        if (executorService != null) {
            executorService.shutdown();
        }

        // Сохраняем оставшиеся сессии перед выключением
        saveAllSessions();

        // Закрываем соединение с БД
        closeMySQL();

        getLogger().info("LVAlerts - плагин остановлен");
    }

    // ============ MYSQL ПОДКЛЮЧЕНИЕ ============

    private boolean connectToMySQL() {
        try {
            String url = "jdbc:mysql://" + mysqlHost + ":" + mysqlPort + "/" + mysqlDatabase +
                    "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=utf8";

            mysqlConnection = DriverManager.getConnection(url, mysqlUsername, mysqlPassword);
            getLogger().info("✅ Подключение к MySQL установлено!");
            return true;
        } catch (SQLException e) {
            getLogger().severe("❌ Ошибка подключения к MySQL: " + e.getMessage());
            return false;
        }
    }

    private void createSessionsTable() {
        try {
            Statement stmt = mysqlConnection.createStatement();
            String sql = "CREATE TABLE IF NOT EXISTS player_sessions (" +
                    "id INT AUTO_INCREMENT PRIMARY KEY," +
                    "player_name VARCHAR(32) NOT NULL," +
                    "session_start DATETIME NOT NULL," +
                    "session_end DATETIME NOT NULL," +
                    "diamonds INT NOT NULL," +
                    "ores INT NOT NULL," +
                    "ancient_debris INT NOT NULL," +
                    "gold INT NOT NULL," +
                    "diamonds_per_minute_avg DOUBLE," +
                    "most_productive_minute INT," +
                    "most_productive_minute_diamonds INT," +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                    "INDEX idx_player (player_name)," +
                    "INDEX idx_time (session_start)" +
                    ")";
            stmt.executeUpdate(sql);
            getLogger().info("✅ Таблица player_sessions создана/проверена");
        } catch (SQLException e) {
            getLogger().severe("❌ Ошибка создания таблицы: " + e.getMessage());
        }
    }

    private void saveSessionToDatabase(String playerName, PlayerSession session) {
        try {
            Map.Entry<Integer, Integer> productiveMinute = session.getMostProductiveMinute();

            String sql = "INSERT INTO player_sessions " +
                    "(player_name, session_start, session_end, diamonds, ores, " +
                    "ancient_debris, gold, diamonds_per_minute_avg, " +
                    "most_productive_minute, most_productive_minute_diamonds) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

            PreparedStatement pstmt = mysqlConnection.prepareStatement(sql);
            pstmt.setString(1, playerName);
            pstmt.setTimestamp(2, Timestamp.valueOf(session.startTime));
            pstmt.setTimestamp(3, Timestamp.valueOf(session.lastActivity));
            pstmt.setInt(4, session.diamonds);
            pstmt.setInt(5, session.ores);
            pstmt.setInt(6, session.ancientDebris);
            pstmt.setInt(7, session.gold);
            pstmt.setDouble(8, session.getDiamondsPerMinuteAverage());
            if (productiveMinute != null) {
                pstmt.setInt(9, productiveMinute.getKey());
                pstmt.setInt(10, productiveMinute.getValue());
            } else {
                pstmt.setNull(9, Types.INTEGER);
                pstmt.setNull(10, Types.INTEGER);
            }

            pstmt.executeUpdate();
            getLogger().info("💾 Сессия сохранена в БД: " + playerName + " (💎" + session.diamonds + ")");
        } catch (SQLException e) {
            getLogger().severe("❌ Ошибка сохранения сессии в БД: " + e.getMessage());
        }
    }

    private void saveAllSessions() {
        for (Map.Entry<String, PlayerSession> entry : playerSessions.entrySet()) {
            if (entry.getValue().ores > 0) {
                saveSessionToDatabase(entry.getKey(), entry.getValue());
            }
        }
    }

    private void closeMySQL() {
        if (mysqlConnection != null) {
            try {
                mysqlConnection.close();
                getLogger().info("✅ Соединение с MySQL закрыто");
            } catch (SQLException e) {
                getLogger().warning("⚠️ Ошибка при закрытии MySQL: " + e.getMessage());
            }
        }
    }

    // ============ ОБРАБОТКА СОБЫТИЙ ============

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        Block block = event.getBlock();

        String playerName = player.getName();
        String material = block.getType().toString().toLowerCase();

        // Проверяем, это ли алмаз, обломки или золото
        int diamonds = 0;
        int ancientDebris = 0;
        int gold = 0;
        int ores = 0;

        if (material.contains("diamond_ore") || material.equals("diamond_ore")) {
            diamonds = 1;
            ores = 1;
        } else if (material.contains("ancient_debris") || material.equals("ancient_debris")) {
            ancientDebris = 1;
            ores = 1;
        } else if (material.contains("gold_ore") || material.equals("gold_ore")) {
            gold = 1;
            ores = 1;
        }

        if (ores > 0) {
            updatePlayerSession(playerName, diamonds, ores, ancientDebris, gold);
        }
    }

    // ============ УПРАВЛЕНИЕ СЕССИЯМИ ============

    private void updatePlayerSession(String playerName, int diamonds, int ores, int ancientDebris, int gold) {
        PlayerSession session = playerSessions.get(playerName);

        if (session == null) {
            session = new PlayerSession();
            playerSessions.put(playerName, session);
            getLogger().info("🆕 Новая сессия: " + playerName);
        }

        session.update(diamonds, ores, ancientDebris, gold);

        // Проверяем порог алмазов (мгновенно!)
        if (!session.alertSent && session.diamonds >= DIAMOND_THRESHOLD) {
            sendInstantAlert(playerName, session);
            session.alertSent = true;
        }

        // Логируем активность (оптимизированно)
        if (ores > 0 && session.shouldLog()) {
            getLogger().info("⛏️  " + playerName + " добыл: " +
                    (session.diamonds > 0 ? "💎" + session.diamonds + " " : "") +
                    (session.ancientDebris > 0 ? "🔥" + session.ancientDebris + " " : "") +
                    (session.gold > 0 ? "🟡" + session.gold + " " : ""));
        }
    }

    private void checkSessions() {
        LocalDateTime now = LocalDateTime.now();
        List<String> expiredPlayers = new ArrayList<>();

        for (Map.Entry<String, PlayerSession> entry : playerSessions.entrySet()) {
            String playerName = entry.getKey();
            PlayerSession session = entry.getValue();

            if (session.isExpired()) {
                expiredPlayers.add(playerName);

                // Сохраняем статистику если что-то добыто
                if (session.ores > 0) {
                    Map<String, Object> stats = session.getStats();
                    stats.put("player", playerName);

                    // Сохраняем в БД
                    saveSessionToDatabase(playerName, session);

                    // Отправляем статистику асинхронно
                    executorService.submit(() -> {
                        sendSessionStats(playerName, stats);
                    });
                }
            }
        }

        // Удаляем истекшие сессии
        for (String player : expiredPlayers) {
            playerSessions.remove(player);
        }
    }

    // ============ ФОРМАТИРОВАНИЕ СТАТИСТИКИ ============

    private String calculateStacks(int amount) {
        if (amount <= 0) return "0 шт";

        int stacks = amount / STACK_SIZE;
        int remainder = amount % STACK_SIZE;

        if (stacks == 0) {
            return amount + " шт";
        } else if (remainder == 0) {
            return stacks + " стаков";
        } else {
            return stacks + " стак. " + remainder + " шт";
        }
    }

    private String formatDiscordStats(String playerName, Map<String, Object> stats) {
        LocalDateTime startTime = (LocalDateTime) stats.get("startTime");
        LocalDateTime endTime = (LocalDateTime) stats.get("endTime");
        int diamonds = (int) stats.get("diamonds");
        int ores = (int) stats.get("ores");
        int ancientDebris = (int) stats.get("ancientDebris");
        int gold = (int) stats.get("gold");
        Map<Integer, Integer> diamondsPerMinute = (Map<Integer, Integer>) stats.get("diamondsPerMinute");

        double avgPerMinute = 0.0;
        Map.Entry<Integer, Integer> productiveMinute = null;

        if (!diamondsPerMinute.isEmpty()) {
            // Средняя скорость
            long totalMinutes = diamondsPerMinute.keySet().stream().max(Integer::compareTo).orElse(0) + 1;
            avgPerMinute = (double) diamonds / totalMinutes;

            // Продуктивная минута
            productiveMinute = diamondsPerMinute.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .orElse(null);
        }

        Duration duration = Duration.between(startTime, endTime);
        long minutes = duration.toMinutes();
        long seconds = duration.minusMinutes(minutes).getSeconds();

        String diamondsStacks = calculateStacks(diamonds);
        String debrisStacks = calculateStacks(ancientDebris);
        String goldStacks = calculateStacks(gold);
        String startTimeStr = startTime.format(TIME_FORMATTER);
        String endTimeStr = endTime.format(TIME_FORMATTER);

        StringBuilder sb = new StringBuilder();

        // Улучшенное форматирование для Discord
        sb.append("## 📊 **СТАТИСТИКА СЕССИИ ИГРОКА**\n\n");
        sb.append("▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬\n\n");

        // Имя игрока выделено в блоках
        sb.append("### 👤 **ИГРОК:** ").append("**`").append(playerName).append("`**\n");
        sb.append("**⏱️ Длительность:** ").append(minutes).append(" мин ").append(seconds).append(" сек\n");
        sb.append("**🕒 Время:** ").append(startTimeStr).append(" - ").append(endTimeStr).append("\n\n");

        sb.append("### 📈 **РЕЗУЛЬТАТЫ ДОБЫЧИ:**\n");
        sb.append("```diff\n");

        // 💎 АЛМАЗЫ - синий цвет (diff +)
        sb.append("+ Алмазы: ").append(diamonds).append(" (").append(diamondsStacks).append(")\n");

        // 🔥 ОБЛОМКИ - оранжевый/красный цвет (diff !)
        if (ancientDebris > 0) {
            sb.append("! Обломки древних останков: ").append(ancientDebris).append(" (").append(debrisStacks).append(")\n");
        }

        // 🟡 ЗОЛОТО - желтый цвет
        if (gold > 0) {
            sb.append("- Золотая руда: ").append(gold).append(" (").append(goldStacks).append(")\n");
        }

        // 📊 ОБЩЕЕ КОЛИЧЕСТВО - нейтральный цвет
        sb.append("# Всего руд: ").append(ores).append("\n\n");

        // Дополнительные метрики
        sb.append("## ⚡ СКОРОСТЬ И ПРОДУКТИВНОСТЬ:\n");

        // СКОРОСТЬ - зеленый/голубой
        sb.append("+ Средняя скорость: ").append(String.format("%.1f", avgPerMinute)).append(" алм/мин\n");

        // ПРОДУКТИВНАЯ МИНУТА - фиолетовый
        if (productiveMinute != null && productiveMinute.getValue() > 0) {
            sb.append("@@ Продуктивная минута: #").append(productiveMinute.getKey() + 1)
                    .append(" (").append(productiveMinute.getValue()).append(" алмазов)\n");
        }

        sb.append("```\n");
        sb.append("▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬\n");

        // ВРЕМЯ ЗАВЕРШЕНИЯ - серый цвет
        sb.append("```md\n");
        sb.append("< Сессия завершена в ").append(LocalDateTime.now().format(TIME_FORMATTER)).append(" >\n");
        sb.append("```");

        return sb.toString();
    }

    private String formatTelegramStats(String playerName, Map<String, Object> stats) {
        LocalDateTime startTime = (LocalDateTime) stats.get("startTime");
        LocalDateTime endTime = (LocalDateTime) stats.get("endTime");
        int diamonds = (int) stats.get("diamonds");
        int ores = (int) stats.get("ores");
        int ancientDebris = (int) stats.get("ancientDebris");
        int gold = (int) stats.get("gold");
        Map<Integer, Integer> diamondsPerMinute = (Map<Integer, Integer>) stats.get("diamondsPerMinute");

        double avgPerMinute = 0.0;
        Map.Entry<Integer, Integer> productiveMinute = null;

        if (!diamondsPerMinute.isEmpty()) {
            long totalMinutes = diamondsPerMinute.keySet().stream().max(Integer::compareTo).orElse(0) + 1;
            avgPerMinute = (double) diamonds / totalMinutes;
            productiveMinute = diamondsPerMinute.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .orElse(null);
        }

        Duration duration = Duration.between(startTime, endTime);
        long minutes = duration.toMinutes();
        long seconds = duration.minusMinutes(minutes).getSeconds();

        String diamondsStacks = calculateStacks(diamonds);
        String startTimeStr = startTime.format(TIME_FORMATTER);
        String endTimeStr = endTime.format(TIME_FORMATTER);

        StringBuilder sb = new StringBuilder();

        sb.append("📊 *СТАТИСТИКА ЗА ").append(minutes).append(" МИНУТ*\n\n");
        sb.append("*👤 Игрок:* ").append(playerName).append("\n");
        sb.append("*⏱️ Длительность:* ").append(minutes).append(" мин ").append(seconds).append(" сек\n");
        sb.append("*🕒 Время:* ").append(startTimeStr).append(" - ").append(endTimeStr).append("\n\n");
        sb.append("*📈 Результаты добычи:*\n");
        sb.append("```\n");
        sb.append("Алмазы: ").append(diamonds).append(" (").append(diamondsStacks).append(")\n");
        if (ancientDebris > 0) {
            sb.append("Обломки: ").append(ancientDebris).append("\n");
        }
        if (gold > 0) {
            sb.append("Золото: ").append(gold).append("\n");
        }
        sb.append("Всего руд: ").append(ores).append("\n\n");

        // Дополнительные метрики
        sb.append("⚡ Средняя скорость: ").append(String.format("%.1f", avgPerMinute)).append(" алм/мин\n");
        if (productiveMinute != null && productiveMinute.getValue() > 0) {
            sb.append("🔥 Продуктивная минута: #").append(productiveMinute.getKey() + 1)
                    .append(" (").append(productiveMinute.getValue()).append(" алмазов)\n");
        }
        sb.append("```\n");
        sb.append("_Сессия завершена в ").append(LocalDateTime.now().format(TIME_FORMATTER)).append("_");

        return sb.toString();
    }

    // ============ ОТПРАВКА СТАТИСТИКИ ============

    private void sendSessionStats(String playerName, Map<String, Object> stats) {
        try {
            // Discord - в канал статистики
            String discordMessage = formatDiscordStats(playerName, stats);
            sendDiscordToChannel(discordMessage);

            // Telegram - всем получателям
            String telegramMessage = formatTelegramStats(playerName, stats);
            sendTelegramToAll(telegramMessage);

            getLogger().info("📊 Отправлена статистика сессии для " + playerName);
        } catch (Exception e) {
            getLogger().warning("⚠️ Ошибка при отправке статистики: " + e.getMessage());
        }
    }

    // ============ МЕТОД ДЛЯ МГНОВЕННОГО ОПОВЕЩЕНИЯ 45+ ============

    private void sendInstantAlert(String playerName, PlayerSession session) {
        executorService.submit(() -> {
            try {
                Duration duration = Duration.between(session.startTime, LocalDateTime.now());
                long minutes = duration.toMinutes();
                long seconds = duration.minusMinutes(minutes).getSeconds();

                String diamondsStacks = calculateStacks(session.diamonds);
                String debrisStacks = calculateStacks(session.ancientDebris);
                String goldStacks = calculateStacks(session.gold);
                String currentTime = LocalDateTime.now().format(TIME_FORMATTER);

                // 1. Telegram сообщение для ВСЕХ троих получателей
                String telegramMessage = "🚨 *МГНОВЕННОЕ ОПОВЕЩЕНИЕ! 45+ АЛМАЗОВ!*\n\n" +
                        "*👤 Игрок:* " + playerName + "\n" +
                        "*💎 Алмазов:* " + session.diamonds + " (" + diamondsStacks + ")\n" +
                        "*⏱️ Время сессии:* " + minutes + " мин " + seconds + " сек\n" +
                        "*🕒 Время:* " + currentTime;

                if (session.ancientDebris > 0) {
                    telegramMessage += "\n*🔥 Обломков:* " + session.ancientDebris + " (" + debrisStacks + ")";
                }
                if (session.gold > 0) {
                    telegramMessage += "\n*🟡 Золота:* " + session.gold + " (" + goldStacks + ")";
                }

                // 2. Discord сообщение для ЛС ТОЛЬКО тебе
                String discordMessage = "## 🚨 **МГНОВЕННОЕ ОПОВЕЩЕНИЕ! 45+ АЛМАЗОВ!**\n\n" +
                        "### 👤 **ИГРОК:** `" + playerName + "`\n\n" +
                        "```diff\n" +
                        "+ Алмазы: " + session.diamonds + " (" + diamondsStacks + ")\n";

                if (session.ancientDebris > 0) {
                    discordMessage += "! Обломки: " + session.ancientDebris + " (" + debrisStacks + ")\n";
                }

                if (session.gold > 0) {
                    discordMessage += "- Золото: " + session.gold + " (" + goldStacks + ")\n";
                }

                discordMessage += "```\n\n" +
                        "**⏱️ Время сессии:** " + minutes + " мин " + seconds + " сек\n" +
                        "**🕒 Время:** " + currentTime;

                // ОТПРАВЛЯЕМ:
                // 1. В Telegram ВСЕМ троим получателям
                sendTelegramToAll(telegramMessage);

                // 2. В Discord ЛС ТОЛЬКО тебе
                sendDiscordDM(discordMessage);

                getLogger().info("⚡ Мгновенное оповещение 45+ для " + playerName +
                        " (" + session.diamonds + " алмазов):" +
                        "\n   → Telegram: отправлено " + telegramAlertRecipients.size() + " получателям" +
                        "\n   → Discord: отправлено в ЛС тебе");

            } catch (Exception e) {
                getLogger().warning("⚠️ Ошибка при отправке мгновенного оповещения: " + e.getMessage());
            }
        });
    }

    // ============ TELEGRAM ОТПРАВКА ============

    private void sendTelegramToAll(String message) {
        if (telegramAlertRecipients == null || telegramAlertRecipients.isEmpty()) {
            getLogger().warning("⚠️ Нет получателей Telegram! Проверь config.yml -> telegram.alert-recipients");
            return;
        }

        if (telegramToken == null || telegramToken.isEmpty()) {
            getLogger().warning("⚠️ Telegram токен не установлен! Проверь config.yml -> telegram.token");
            return;
        }

        executorService.submit(() -> {
            int sentCount = 0;
            List<String> failedRecipients = new ArrayList<>();

            for (String chatId : telegramAlertRecipients) {
                if (sendTelegramToUser(chatId, message)) {
                    sentCount++;
                } else {
                    failedRecipients.add(chatId);
                }
            }

            if (sentCount > 0) {
                getLogger().info("✅ Telegram отправлено " + sentCount + "/" + telegramAlertRecipients.size() + " получателям");
            }

            if (!failedRecipients.isEmpty()) {
                getLogger().warning("⚠️ Не удалось отправить Telegram этим получателям: " + failedRecipients);
            }
        });
    }

    private boolean sendTelegramToUser(String chatId, String message) {
        if (telegramToken == null || telegramToken.isEmpty()) {
            getLogger().warning("⚠️ Telegram токен не установлен!");
            return false;
        }

        try {
            String url = "https://api.telegram.org/bot" + telegramToken + "/sendMessage";
            String payload = String.format(
                    "{\"chat_id\":\"%s\",\"text\":\"%s\",\"parse_mode\":\"Markdown\"}",
                    chatId,
                    escapeJson(message)
            );

            URI uri = new URI(url);
            URL telegramUrl = uri.toURL();
            HttpURLConnection conn = (HttpURLConnection) telegramUrl.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = payload.getBytes("utf-8");
                os.write(input, 0, input.length);
            }

            int responseCode = conn.getResponseCode();

            StringBuilder response = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(
                    responseCode == 200 ? conn.getInputStream() : conn.getErrorStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    response.append(line);
                }
            }

            conn.disconnect();

            if (responseCode == 200) {
                return true;
            } else {
                getLogger().warning("⚠️ Ошибка Telegram " + responseCode + " для " + chatId + ": " + response.toString());
                return false;
            }
        } catch (Exception e) {
            getLogger().warning("⚠️ Ошибка отправки Telegram: " + e.getMessage());
            return false;
        }
    }

    private String escapeJson(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    // ============ DISCORD ОТПРАВКА ============

    private void sendDiscordToChannel(String message) {
        if (discordToken == null || discordToken.isEmpty() || discordChannelId == null || discordChannelId.isEmpty()) {
            getLogger().warning("⚠️ Discord токен или ID канала не установлены!");
            return;
        }

        // Проверяем интернет соединение
        if (!checkInternetConnection()) {
            getLogger().warning("⚠️ Нет подключения к интернету! Пропускаем отправку в Discord.");
            return;
        }

        executorService.submit(() -> {
            for (int attempt = 1; attempt <= 3; attempt++) {
                try {
                    String url = "https://discord.com/api/v10/channels/" + discordChannelId + "/messages";

                    // Ограничиваем длину сообщения
                    String discordFormatted = message;
                    if (discordFormatted.length() > 1900) {
                        discordFormatted = discordFormatted.substring(0, 1900) + "...";
                    }

                    String payload = String.format(
                            "{\"content\":\"%s\"}",
                            escapeJson(discordFormatted)
                    );

                    URI uri = new URI(url);
                    URL discordUrl = uri.toURL();
                    HttpURLConnection conn = (HttpURLConnection) discordUrl.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setRequestProperty("Content-Type", "application/json");
                    conn.setRequestProperty("Authorization", "Bot " + discordToken);
                    conn.setRequestProperty("User-Agent", "LVAlerts-Bot");
                    conn.setDoOutput(true);

                    // УВЕЛИЧИВАЕМ ТАЙМАУТЫ
                    conn.setConnectTimeout(30000); // 30 секунд вместо 10
                    conn.setReadTimeout(30000);    // 30 секунд вместо 10

                    // Отключаем кэширование
                    conn.setUseCaches(false);
                    conn.setDefaultUseCaches(false);

                    try (OutputStream os = conn.getOutputStream()) {
                        byte[] input = payload.getBytes("utf-8");
                        os.write(input, 0, input.length);
                    }

                    int responseCode = conn.getResponseCode();

                    StringBuilder response = new StringBuilder();
                    try (BufferedReader br = new BufferedReader(new InputStreamReader(
                            responseCode == 200 ? conn.getInputStream() : conn.getErrorStream()))) {
                        String line;
                        while ((line = br.readLine()) != null) {
                            response.append(line);
                        }
                    }

                    conn.disconnect();

                    if (responseCode == 200) {
                        getLogger().info("✅ Discord отправлено в канал статистики");
                        return; // Успешно отправили, выходим
                    } else if (responseCode == 429) {
                        // Rate limit - ждем и пробуем снова
                        try {
                            // Парсим время ожидания из заголовков
                            String retryAfter = conn.getHeaderField("Retry-After");
                            int waitTime = retryAfter != null ? Integer.parseInt(retryAfter) * 1000 : 1000;
                            getLogger().warning("⚠️ Discord rate limit. Ждем " + (waitTime / 1000) + " секунд...");
                            Thread.sleep(waitTime);
                            continue; // Пробуем снова
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            getLogger().warning("⚠️ Прервано ожидание rate limit");
                        }
                    } else if (responseCode == 403) {
                        getLogger().severe("❌ Discord: Доступ запрещен! Проверь права бота и ID канала: " + discordChannelId);
                        return;
                    } else if (responseCode == 404) {
                        getLogger().severe("❌ Discord: Канал не найден! ID: " + discordChannelId);
                        return;
                    } else if (responseCode == 401) {
                        getLogger().severe("❌ Discord: Неверный токен бота!");
                        return;
                    } else {
                        getLogger().warning("⚠️ Ошибка Discord канал: код " + responseCode + " - " + response.toString());
                        // Пробуем снова через 2 секунды
                        if (attempt < 3) {
                            getLogger().info("🔄 Повторная попытка " + (attempt + 1) + "/3 через 2 секунды...");
                            Thread.sleep(2000);
                            continue;
                        }
                    }
                } catch (Exception e) {
                    getLogger().warning("⚠️ Ошибка отправки Discord (попытка " + attempt + "/3): " +
                            e.getClass().getSimpleName() + ": " + e.getMessage());

                    if (attempt < 3) {
                        try {
                            getLogger().info("🔄 Повторная попытка " + (attempt + 1) + "/3 через 3 секунды...");
                            Thread.sleep(3000);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    } else {
                        getLogger().severe("❌ Не удалось отправить в Discord после 3 попыток!");
                    }
                }
            }
        });
    }

    private boolean checkInternetConnection() {
        try {
            URL url = new URI("https://discord.com").toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestMethod("HEAD");
            int responseCode = conn.getResponseCode();
            conn.disconnect();
            return responseCode == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private void sendDiscordDM(String message) {
        if (discordToken == null || discordToken.isEmpty() || discordYourId == null || discordYourId.isEmpty()) {
            getLogger().warning("⚠️ Discord токен или ваш ID не установлены!");
            return;
        }

        executorService.submit(() -> {
            for (int attempt = 1; attempt <= 3; attempt++) {
                try {
                    // Ограничиваем длину сообщения
                    String discordFormatted = message;
                    if (discordFormatted.length() > 1900) {
                        discordFormatted = discordFormatted.substring(0, 1900) + "...";
                    }

                    // Пытаемся отправить в DM через создание канала
                    String createUrl = "https://discord.com/api/v10/users/@me/channels";
                    String createPayload = String.format(
                            "{\"recipient_id\":\"%s\"}",
                            discordYourId
                    );

                    URI createUri = new URI(createUrl);
                    URL discordUrl = createUri.toURL();
                    HttpURLConnection createConn = (HttpURLConnection) discordUrl.openConnection();
                    createConn.setRequestMethod("POST");
                    createConn.setRequestProperty("Content-Type", "application/json");
                    createConn.setRequestProperty("Authorization", "Bot " + discordToken);
                    createConn.setRequestProperty("User-Agent", "LVAlerts-Bot");
                    createConn.setDoOutput(true);

                    // УВЕЛИЧИВАЕМ ТАЙМАУТЫ
                    createConn.setConnectTimeout(30000);
                    createConn.setReadTimeout(30000);
                    createConn.setUseCaches(false);
                    createConn.setDefaultUseCaches(false);

                    try (OutputStream os = createConn.getOutputStream()) {
                        byte[] input = createPayload.getBytes("utf-8");
                        os.write(input, 0, input.length);
                    }

                    int createCode = createConn.getResponseCode();

                    if (createCode != 200) {
                        // Если не удалось создать DM, отправляем просто в канал с упоминанием
                        getLogger().warning("⚠️ Не удалось создать DM канал: код " + createCode);

                        if (createCode == 429 && attempt < 3) {
                            // Rate limit
                            String retryAfter = createConn.getHeaderField("Retry-After");
                            int waitTime = retryAfter != null ? Integer.parseInt(retryAfter) * 1000 : 1000;
                            getLogger().info("🔄 Discord rate limit. Ждем " + (waitTime / 1000) + " секунд...");
                            Thread.sleep(waitTime);
                            continue;
                        }

                        String mentionMessage = "<@" + discordYourId + "> " + discordFormatted;
                        sendDiscordToChannel(mentionMessage);
                        return;
                    }

                    // Читаем ответ с ID канала
                    StringBuilder response = new StringBuilder();
                    try (BufferedReader br = new BufferedReader(new InputStreamReader(createConn.getInputStream()))) {
                        String line;
                        while ((line = br.readLine()) != null) {
                            response.append(line);
                        }
                    }

                    createConn.disconnect();

                    // Парсим ID канала
                    String channelId = parseJson(response.toString(), "id");
                    if (channelId == null || channelId.isEmpty()) {
                        getLogger().warning("⚠️ Не удалось получить ID DM канала");
                        if (attempt < 3) {
                            Thread.sleep(2000);
                            continue;
                        }
                        return;
                    }

                    // Отправляем сообщение в DM канал
                    String sendUrl = "https://discord.com/api/v10/channels/" + channelId + "/messages";
                    String sendPayload = String.format(
                            "{\"content\":\"%s\"}",
                            escapeJson(discordFormatted)
                    );

                    URI sendUri = new URI(sendUrl);
                    URL sendDiscordUrl = sendUri.toURL();
                    HttpURLConnection sendConn = (HttpURLConnection) sendDiscordUrl.openConnection();
                    sendConn.setRequestMethod("POST");
                    sendConn.setRequestProperty("Content-Type", "application/json");
                    sendConn.setRequestProperty("Authorization", "Bot " + discordToken);
                    sendConn.setRequestProperty("User-Agent", "LVAlerts-Bot");
                    sendConn.setDoOutput(true);

                    // УВЕЛИЧИВАЕМ ТАЙМАУТЫ
                    sendConn.setConnectTimeout(30000);
                    sendConn.setReadTimeout(30000);
                    sendConn.setUseCaches(false);
                    sendConn.setDefaultUseCaches(false);

                    try (OutputStream os = sendConn.getOutputStream()) {
                        byte[] input = sendPayload.getBytes("utf-8");
                        os.write(input, 0, input.length);
                    }

                    int sendCode = sendConn.getResponseCode();
                    sendConn.disconnect();

                    if (sendCode == 200) {
                        getLogger().info("✅ Discord отправлено в ЛС");
                        return;
                    } else if (sendCode == 429 && attempt < 3) {
                        // Rate limit
                        String retryAfter = sendConn.getHeaderField("Retry-After");
                        int waitTime = retryAfter != null ? Integer.parseInt(retryAfter) * 1000 : 1000;
                        getLogger().info("🔄 Discord rate limit. Ждем " + (waitTime / 1000) + " секунд...");
                        Thread.sleep(waitTime);
                        continue;
                    } else {
                        getLogger().warning("⚠️ Ошибка Discord ЛС: код " + sendCode);
                        if (attempt < 3) {
                            Thread.sleep(2000);
                            continue;
                        }
                    }
                } catch (Exception e) {
                    getLogger().warning("⚠️ Ошибка отправки Discord ЛС (попытка " + attempt + "/3): " +
                            e.getClass().getSimpleName() + ": " + e.getMessage());

                    if (attempt < 3) {
                        try {
                            getLogger().info("🔄 Повторная попытка " + (attempt + 1) + "/3 через 3 секунды...");
                            Thread.sleep(3000);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    } else {
                        getLogger().severe("❌ Не удалось отправить Discord ЛС после 3 попыток!");
                    }
                }
            }
        });
    }

    private String parseJson(String json, String key) {
        try {
            String searchKey = "\"" + key + "\":\"";
            int start = json.indexOf(searchKey);
            if (start == -1) {
                searchKey = "\"" + key + "\":";
                start = json.indexOf(searchKey);
                if (start == -1) return null;
                start += searchKey.length();
                int end = json.indexOf(",", start);
                if (end == -1) end = json.indexOf("}", start);
                if (end == -1) return null;
                String value = json.substring(start, end).trim();
                if (value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                }
                return value;
            }
            start += searchKey.length();
            int end = json.indexOf("\"", start);
            return json.substring(start, end);
        } catch (Exception e) {
            getLogger().warning("⚠️ Ошибка парсинга JSON: " + e.getMessage());
            return null;
        }
    }

    // ============ КОМАНДЫ ============

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (cmd.getName().equalsIgnoreCase("lvalerts")) {
            if (args.length == 0) {
                sender.sendMessage("§6=== LVAlerts ===");
                sender.sendMessage("§a/lvalerts reload §7- Перезагрузить конфиг");
                sender.sendMessage("§a/lvalerts test §7- Тест оповещения");
                sender.sendMessage("§a/lvalerts stats §7- Активные сессии");
                sender.sendMessage("§a/lvalerts history <игрок> §7- История сессий");
                sender.sendMessage("§a/lvalerts testtelegram §7- Тест Telegram");
                sender.sendMessage("§a/lvalerts testdiscord §7- Тест Discord");
                sender.sendMessage("§a/lvalerts debug §7- Отладочная информация");
                return true;
            }

            switch (args[0].toLowerCase()) {
                case "reload":
                    if (!sender.hasPermission("lvalerts.admin")) {
                        sender.sendMessage("§cНет прав!");
                        return true;
                    }
                    reloadConfig();
                    loadConfig();
                    sender.sendMessage("§aКонфигурация перезагружена!");
                    getLogger().info("Конфигурация перезагружена командой от " + sender.getName());
                    return true;

                case "test":
                    sender.sendMessage("§aОтправка тестового оповещения 45+...");
                    PlayerSession testSession = new PlayerSession() {{
                        diamonds = 50;
                        ores = 60;
                        ancientDebris = 5;
                        gold = 10;
                        diamondsPerMinute.put(0, 20);
                        diamondsPerMinute.put(1, 30);
                    }};
                    sendInstantAlert("TestPlayer", testSession);
                    sender.sendMessage("§aПроверь Telegram (всем 3 получателям) и Discord ЛС!");
                    return true;

                case "testtelegram":
                    sender.sendMessage("§aТест Telegram...");
                    sendTelegramToAll("✅ *ТЕСТОВОЕ СООБЩЕНИЕ ТЕЛЕГРАМ*\nПлагин LVAlerts работает корректно!");
                    sender.sendMessage("§aПроверь Telegram у всех 3 получателей!");
                    return true;

                case "testdiscord":
                    sender.sendMessage("§aТест Discord...");
                    sendDiscordToChannel("✅ **ТЕСТОВОЕ СООБЩЕНИЕ DISCORD**\nПлагин LVAlerts работает корректно!");
                    sender.sendMessage("§aПроверь Discord канал статистики!");
                    return true;

                case "stats":
                    sender.sendMessage("§6=== Активные сессии ===");
                    if (playerSessions.isEmpty()) {
                        sender.sendMessage("§7Нет активных сессий");
                    } else {
                        for (Map.Entry<String, PlayerSession> entry : playerSessions.entrySet()) {
                            PlayerSession session = entry.getValue();
                            Duration dur = Duration.between(session.startTime, LocalDateTime.now());
                            sender.sendMessage(String.format("§e%s: §a💎%d §7⏱️%d:%02d §6🔥%d §e🟡%d",
                                    entry.getKey(),
                                    session.diamonds,
                                    dur.toMinutes(),
                                    dur.minusMinutes(dur.toMinutes()).getSeconds(),
                                    session.ancientDebris,
                                    session.gold));
                        }
                    }
                    return true;

                case "history":
                    if (args.length < 2) {
                        sender.sendMessage("§cИспользование: /lvalerts history <игрок>");
                        return true;
                    }

                    String playerName = args[1];
                    try {
                        String sql = "SELECT * FROM player_sessions WHERE player_name = ? ORDER BY session_start DESC LIMIT 10";
                        PreparedStatement pstmt = mysqlConnection.prepareStatement(sql);
                        pstmt.setString(1, playerName);
                        ResultSet rs = pstmt.executeQuery();

                        sender.sendMessage("§6=== История сессий: " + playerName + " ===");
                        int count = 0;
                        while (rs.next()) {
                            count++;
                            Timestamp start = rs.getTimestamp("session_start");
                            Timestamp end = rs.getTimestamp("session_end");
                            int diamonds = rs.getInt("diamonds");
                            int debris = rs.getInt("ancient_debris");
                            int gold = rs.getInt("gold");

                            Duration dur = Duration.between(start.toLocalDateTime(), end.toLocalDateTime());
                            String stacks = calculateStacks(diamonds);

                            sender.sendMessage(String.format("§7#%d. §a💎%d (%s) §7⏱️%d:%02d §6🔥%d §e🟡%d",
                                    count, diamonds, stacks,
                                    dur.toMinutes(), dur.minusMinutes(dur.toMinutes()).getSeconds(),
                                    debris, gold));
                        }

                        if (count == 0) {
                            sender.sendMessage("§7Нет записей для этого игрока");
                        }
                    } catch (SQLException e) {
                        sender.sendMessage("§cОшибка получения истории: " + e.getMessage());
                    }
                    return true;

                case "debug":
                    sender.sendMessage("§6=== Отладочная информация ===");
                    sender.sendMessage("§7Telegram получателей: " + telegramAlertRecipients.size());
                    sender.sendMessage("§7Discord канал ID: " + discordChannelId);
                    sender.sendMessage("§7Discord ваш ID: " + discordYourId);
                    sender.sendMessage("§7Активных сессий: " + playerSessions.size());
                    sender.sendMessage("§7MySQL подключен: " + (mysqlConnection != null));
                    sender.sendMessage("§7Порог алмазов: " + DIAMOND_THRESHOLD);
                    sender.sendMessage("§7Discord токен установлен: " + (!discordToken.isEmpty()));
                    sender.sendMessage("§7Telegram токен установлен: " + (!telegramToken.isEmpty()));
                    sender.sendMessage("§7Telegram получатели: " + telegramAlertRecipients);
                    return true;

                default:
                    sender.sendMessage("§cНеизвестная команда. Используй §a/lvalerts");
                    return true;
            }
        }
        return false;
    }
}