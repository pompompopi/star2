package me.pompompopi.star2.database;

import me.pompompopi.star2.Star2;
import me.pompompopi.star2.config.Configuration;
import me.pompompopi.star2.util.ExceptionUtil;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;

import java.sql.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;

public final class DatabaseConnection {
    private final Connection connection;
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();

    public DatabaseConnection(final Configuration configuration) throws SQLException, ExecutionException, InterruptedException {
        final StringBuilder urlBuilder = new StringBuilder("jdbc:postgresql://");
        urlBuilder.append(configuration.getDatabaseHost());
        if (configuration.getDatabasePort() != 5432)
            urlBuilder.append(":5432");
        urlBuilder.append("/");
        this.connection = DriverManager.getConnection(urlBuilder.toString(), configuration.getDatabaseUsername(), configuration.getDatabasePassword());
        this.executorService.submit(() -> this.connection.prepareStatement("ALTER TABLE IF EXISTS ONLY starboard ADD COLUMN IF NOT EXISTS original_author_id bigint NOT NULL DEFAULT -1;").executeUpdate()).get();
        this.executorService.submit(() -> this.connection.prepareStatement("CREATE TABLE IF NOT EXISTS starboard (original_message_id bigint NOT NULL PRIMARY KEY, original_channel_id bigint NOT NULL, original_author_id bigint NOT NULL, starboard_message_id bigint NOT NULL UNIQUE, stars smallint NOT NULL);").executeUpdate()).get();
    }

    public CompletableFuture<Void> performMigration(final JDA jda) {
        return CompletableFuture.runAsync(() -> ExceptionUtil.wrap(SQLException.class, () -> {
            final ResultSet results = this.connection.prepareStatement("SELECT * FROM starboard WHERE original_author_id = -1;").executeQuery();
            while (results.next()) {
                final DatabaseRow databaseRow = new DatabaseRow(results);
                final TextChannel textChannel = jda.getTextChannelById(databaseRow.originalChannelId());
                if (textChannel == null)
                    continue;
                final long originalMessageId = databaseRow.originalMessageId();
                textChannel.retrieveMessageById(databaseRow.originalMessageId())
                        .map(Message::getAuthor)
                        .map(User::getIdLong)
                        .queue(authorId -> executorService.submit(() -> ExceptionUtil.wrap(SQLException.class, () -> {
                            final PreparedStatement statement = this.connection.prepareStatement("UPDATE starboard SET original_author_id = ? WHERE original_message_id = ?;");
                            statement.setLong(1, authorId);
                            statement.setLong(2, originalMessageId);
                            statement.executeUpdate();
                            Star2.LOGGER.info("Added author id column value ({}) for {}", authorId, originalMessageId);
                        }, e -> new IllegalStateException("Exception migrating starboard entry", e))));
            }
        }, CompletionException::new), executorService);
    }

    public void shutdown() {
        ExceptionUtil.ignore(this.connection::commit);
        ExceptionUtil.ignore(this.connection::close);
    }

    public CompletableFuture<Void> updateStars(final long originalMessageId, final short newStarCount) {
        return CompletableFuture.runAsync(() -> ExceptionUtil.wrap(SQLException.class, () -> {
            final PreparedStatement statement = this.connection.prepareStatement("UPDATE starboard SET stars = ? WHERE original_message_id = ?;");
            statement.setShort(1, newStarCount);
            statement.setLong(2, originalMessageId);
            statement.executeUpdate();
        }, CompletionException::new), executorService);
    }

    public CompletableFuture<Optional<DatabaseRow>> removeBoardEntry(final long originalMessageId) {
        return CompletableFuture.supplyAsync(() -> ExceptionUtil.wrap(SQLException.class, () -> {
            final PreparedStatement statement = this.connection.prepareStatement("DELETE FROM starboard WHERE original_message_id = ? RETURNING *;");
            statement.setLong(1, originalMessageId);
            final ResultSet results = statement.executeQuery();
            if (!results.next())
                return Optional.empty();
            return Optional.of(new DatabaseRow(results));
        }, CompletionException::new), executorService);
    }

    public CompletableFuture<Void> addBoardEntry(final long originalMessageId, final long originalChannelId, final long originalAuthorId, final long starboardMessageId, final short stars) {
        return CompletableFuture.runAsync(() -> ExceptionUtil.wrap(SQLException.class, () -> {
            final PreparedStatement statement = this.connection.prepareStatement("INSERT INTO starboard (original_message_id, original_channel_id, original_author_id, starboard_message_id, stars) VALUES (?, ?, ?, ?, ?);");
            statement.setLong(1, originalMessageId);
            statement.setLong(2, originalChannelId);
            statement.setLong(3, originalAuthorId);
            statement.setLong(4, starboardMessageId);
            statement.setShort(5, stars);
            statement.executeUpdate();
        }, CompletionException::new), executorService);
    }

    public CompletableFuture<Optional<DatabaseRow>> getBoardEntry(final long originalMessageId) {
        return CompletableFuture.supplyAsync(() -> ExceptionUtil.wrap(SQLException.class, () -> {
            final PreparedStatement statement = this.connection.prepareStatement("SELECT * FROM starboard WHERE original_message_id = ?;");
            statement.setLong(1, originalMessageId);
            final ResultSet results = statement.executeQuery();
            if (!results.next())
                return Optional.empty();
            return Optional.of(new DatabaseRow(results));
        }, CompletionException::new), executorService);
    }

    public CompletableFuture<Collection<DatabaseRow>> getUserBoardEntries(final long userId) {
        return CompletableFuture.supplyAsync(() -> ExceptionUtil.wrap(SQLException.class, () -> {
            final PreparedStatement statement = this.connection.prepareStatement("SELECT * FROM starboard WHERE original_author_id = ?;");
            statement.setLong(1, userId);
            final ResultSet results = statement.executeQuery();
            final List<DatabaseRow> databaseRows = new ArrayList<>();
            while (results.next())
                databaseRows.add(new DatabaseRow(results));
            return databaseRows;
        }, CompletionException::new), executorService);
    }

    public CompletableFuture<Boolean> userHasBoardEntry(final long userId) {
        return CompletableFuture.supplyAsync(() -> ExceptionUtil.wrap(SQLException.class, () -> {
            final PreparedStatement statement = this.connection.prepareStatement("SELECT * FROM starboard WHERE original_author_id = ? LIMIT 1;");
            statement.setLong(1, userId);
            return statement.executeQuery().next();
        }, CompletionException::new), executorService);
    }
}
