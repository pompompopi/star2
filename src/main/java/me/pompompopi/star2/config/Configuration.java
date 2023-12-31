package me.pompompopi.star2.config;

import me.pompompopi.star2.Star2;
import me.pompompopi.star2.util.ExceptionUtil;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class Configuration {
    private final String token;
    private final String starEmoji;
    private final short minimumReactions;
    private final long starboardChannel;
    private final String databaseHost;
    private final int databasePort;
    private final String databaseUsername;
    private final String databasePassword;
    private final long ownerId;
    private final String prefix;

    public Configuration() {
        this.token = getEnvironmentVariable("DISCORD_TOKEN");
        this.starEmoji = getEnvironmentVariable("EMOJI", "⭐");
        this.minimumReactions = ExceptionUtil.wrap(NumberFormatException.class, () -> Short.parseShort(getEnvironmentVariable("MINIMUM_REACTIONS", "3")), e -> new IllegalArgumentException("Invalid minimum reaction count provided", e));
        this.starboardChannel = ExceptionUtil.wrap(NumberFormatException.class, () -> Long.parseUnsignedLong(getEnvironmentVariable("STARBOARD_CHANNEL")), e -> new IllegalArgumentException("Invalid starboard channel provided", e));
        this.databaseHost = getEnvironmentVariable("DATABASE_HOST", "localhost");
        this.databasePort = ExceptionUtil.wrap(NumberFormatException.class, () -> Integer.parseUnsignedInt(getEnvironmentVariable("DATABASE_PORT", "5432")), e -> new IllegalArgumentException("Invalid database port provided", e));
        this.databaseUsername = getEnvironmentVariable("DATABASE_USERNAME", "postgres");
        this.databasePassword = getEnvironmentVariable("DATABASE_PASSWORD");
        this.ownerId = ExceptionUtil.wrap(NumberFormatException.class, () -> Long.parseUnsignedLong(getEnvironmentVariable("OWNER_ID")), e -> new IllegalArgumentException("Invalid owner id provided", e));
        this.prefix = getEnvironmentVariable("PREFIX", "s2!");
    }

    private String getEnvironmentVariable(final String key) {
        return getEnvironmentVariable(key, null);
    }

    private String getEnvironmentVariable(final String key, final @Nullable String defaultValue) {
        final String filePath = System.getenv(key + "_FILE");
        if (filePath != null)
            return ExceptionUtil.wrap(IOException.class, () -> Files.readString(Path.of(filePath)).trim(), e -> new UncheckedIOException("Failed to read secret file", e));
        final String value = System.getenv(key);
        if (value != null)
            return value;
        if (defaultValue == null)
            throw new IllegalArgumentException("Requested environment variable " + key + " does not have a configured value. Please configure one.");
        Star2.LOGGER.warn("Environment variable " + key + " has not been configured by the user, using defaults.");
        return defaultValue;
    }

    public String getToken() {
        return token;
    }

    public String getStarEmoji() {
        return starEmoji;
    }

    public short getMinimumReactions() {
        return minimumReactions;
    }

    public long getStarboardChannel() {
        return starboardChannel;
    }

    public String getDatabaseHost() {
        return databaseHost;
    }

    public int getDatabasePort() {
        return databasePort;
    }

    public String getDatabaseUsername() {
        return databaseUsername;
    }

    public String getPrefix() {
        return prefix;
    }

    public String getDatabasePassword() {
        return databasePassword;
    }

    public long getOwnerId() {
        return ownerId;
    }
}
