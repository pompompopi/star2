package me.pompompopi.star2.database;

import me.pompompopi.star2.Star2;
import me.pompompopi.star2.config.Configuration;
import me.pompompopi.star2.util.ExceptionUtil;
import me.pompompopi.star2.util.FuturePool;
import me.pompompopi.star2.util.Tuple;
import me.pompompopi.star2.wrappers.ExceptionLoggingExecutorService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Message;
import org.jetbrains.annotations.Nullable;

import java.sql.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.*;

public final class DatabaseConnection {
    private final Connection connection;
    private final ExecutorService executorService = new ExceptionLoggingExecutorService(Executors.newSingleThreadExecutor());

    public DatabaseConnection(final Configuration configuration) throws SQLException, ExecutionException, InterruptedException {
        final StringBuilder urlBuilder = new StringBuilder("jdbc:postgresql://");
        urlBuilder.append(configuration.getDatabaseHost());
        if (configuration.getDatabasePort() != 5432)
            urlBuilder.append(":5432");
        urlBuilder.append("/");
        this.connection = DriverManager.getConnection(urlBuilder.toString(), configuration.getDatabaseUsername(), configuration.getDatabasePassword());
        this.executorService.invokeAll(Set.of(
                        () -> this.connection.prepareStatement("ALTER TABLE IF EXISTS ONLY starboard ADD COLUMN IF NOT EXISTS original_author_id bigint NOT NULL DEFAULT -1;").executeUpdate(),
                        () -> this.connection.prepareStatement("ALTER TABLE IF EXISTS ONLY starboard ADD COLUMN IF NOT EXISTS referenced_message_id bigint DEFAULT -1;").executeUpdate(),
                        () -> this.connection.prepareStatement("ALTER TABLE IF EXISTS ONLY starboard ADD COLUMN IF NOT EXISTS referenced_author_id bigint DEFAULT NULL;").executeUpdate(),
                        () -> this.connection.prepareStatement("CREATE TABLE IF NOT EXISTS starboard (original_message_id bigint NOT NULL PRIMARY KEY, original_channel_id bigint NOT NULL, original_author_id bigint NOT NULL, starboard_message_id bigint NOT NULL UNIQUE, referenced_message_id bigint, referenced_author_id bigint, stars smallint NOT NULL);").executeUpdate())
                )
                .getLast().get();
    }

    public CompletableFuture<Void> performMigration(final JDA jda) {
        return CompletableFuture.allOf(CompletableFuture.runAsync(() -> ExceptionUtil.wrap(SQLException.class, () -> {
            Star2.LOGGER.info("Running database migration #1");
            final ResultSet results = this.connection.prepareStatement("SELECT * FROM starboard WHERE original_author_id = -1;").executeQuery();
            final FuturePool pool = new FuturePool();
            while (results.next()) {
                final DatabaseRow row = new DatabaseRow(results);
                pool.poolRun(() -> row.toOriginalMessage(jda).thenAcceptAsync(messageOpt -> {
                    if (messageOpt.isEmpty())
                        return;
                    final Message message = messageOpt.get();
                    final long originalMessageId = message.getIdLong();
                    final long authorId = message.getAuthor().getIdLong();
                    executorService.submit(() -> ExceptionUtil.wrap(SQLException.class, () -> {
                        final PreparedStatement statement = this.connection.prepareStatement("UPDATE starboard SET original_author_id = ? WHERE original_message_id = ?;");
                        statement.setLong(1, authorId);
                        statement.setLong(2, originalMessageId);
                        statement.executeUpdate();
                        Star2.LOGGER.info("Added author id column value ({}) for {}", authorId, originalMessageId);
                    }, e -> new IllegalStateException("Exception migrating starboard entry", e)));
                }));
            }
            pool.join();
            Star2.LOGGER.info("Finished database migration #1");
        }, CompletionException::new), executorService), CompletableFuture.runAsync(() -> ExceptionUtil.wrap(SQLException.class, () -> {
            Star2.LOGGER.info("Running database migration #2");
            final ResultSet results = this.connection.prepareStatement("SELECT * FROM starboard WHERE referenced_message_id = -1;").executeQuery();
            final FuturePool pool = new FuturePool();
            while (results.next()) {
                final DatabaseRow row = new DatabaseRow(results);
                pool.poolRun(() -> row.toOriginalMessage(jda).thenAcceptAsync(originalMessageOpt -> {
                    if (originalMessageOpt.isEmpty()) {
                        Star2.LOGGER.warn("Could not find original message for row");
                        return;
                    }
                    final Message originalMessage = originalMessageOpt.get();
                    final long originalMessageId = originalMessage.getIdLong();
                    final @Nullable Message referencedMessage = originalMessage.getReferencedMessage();
                    if (referencedMessage == null) {
                        executorService.submit(() -> ExceptionUtil.wrap(SQLException.class, () -> {
                            final PreparedStatement statement = this.connection.prepareStatement("UPDATE starboard SET referenced_message_id = NULL WHERE original_message_id = ?;");
                            statement.setLong(1, originalMessageId);
                            statement.executeUpdate();
                            Star2.LOGGER.info("Nulled out referenced message id column value for {} as it doesn't reply to anything", originalMessageId);
                        }, e -> new IllegalStateException("Exception nulling referenced message id", e)));
                        return;
                    }
                    final long referencedMessageId = referencedMessage.getIdLong();
                    final long referencedAuthorId = referencedMessage.getAuthor().getIdLong();
                    executorService.submit(() -> ExceptionUtil.wrap(SQLException.class, () -> {
                        final PreparedStatement statement = this.connection.prepareStatement("UPDATE starboard SET referenced_message_id = ?, referenced_author_id = ? WHERE original_message_id = ?;");
                        statement.setLong(1, referencedMessageId);
                        statement.setLong(2, referencedAuthorId);
                        statement.setLong(3, originalMessageId);
                        statement.executeUpdate();
                        Star2.LOGGER.info("Added reference information to {}, reference message id: {}, reference author id: {}", originalMessageId, referencedMessageId, referencedAuthorId);
                    }, e -> new IllegalStateException("Exception adding referenced message id and referenced author id", e)));
                }));
            }
            pool.join();
            Star2.LOGGER.info("Finished database migration #2");
        }, CompletionException::new), executorService));
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

    public CompletableFuture<Void> updateStarsBulk(final Collection<Tuple<Short, Long>> updates) {
        return CompletableFuture.runAsync(() -> ExceptionUtil.wrap(SQLException.class, () -> {
            final PreparedStatement statement = this.connection.prepareStatement("UPDATE starboard SET stars = ? WHERE original_message_id = ?;");
            for (final Tuple<Short, Long> update : updates) {
                statement.setShort(1, update.first());
                statement.setLong(2, update.second());
                statement.addBatch();
            }
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

    public CompletableFuture<Void> addBoardEntry(final long originalMessageId, final long originalChannelId, final long originalAuthorId, final long starboardMessageId, final @Nullable Long referencedMessageId, final @Nullable Long referencedAuthorId, final short stars) {
        return CompletableFuture.runAsync(() -> ExceptionUtil.wrap(SQLException.class, () -> {
            final PreparedStatement statement = this.connection.prepareStatement("INSERT INTO starboard (original_message_id, original_channel_id, original_author_id, starboard_message_id, referenced_message_id, referenced_author_id, stars) VALUES (?, ?, ?, ?, ?, ?, ?);");
            statement.setLong(1, originalMessageId);
            statement.setLong(2, originalChannelId);
            statement.setLong(3, originalAuthorId);
            statement.setLong(4, starboardMessageId);
            if (referencedMessageId != null) {
                statement.setLong(5, referencedMessageId);
            } else {
                statement.setNull(5, Types.BIGINT);
            }
            if (referencedAuthorId != null) {
                statement.setLong(6, referencedAuthorId);
            } else {
                statement.setNull(6, Types.BIGINT);
            }
            statement.setShort(7, stars);
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

    public CompletableFuture<Collection<DatabaseRow>> getStarboardsInReferenceTo(final long referencedMessageId) {
        return CompletableFuture.supplyAsync(() -> ExceptionUtil.wrap(SQLException.class, () -> {
            final PreparedStatement statement = this.connection.prepareStatement("SELECT * FROM starboard WHERE referenced_message_id = ?;");
            statement.setLong(1, referencedMessageId);
            return DatabaseRow.all(statement.executeQuery(), ArrayList::new);
        }, CompletionException::new), executorService);
    }

    public CompletableFuture<Collection<DatabaseRow>> getUserBoardEntries(final long userId) {
        return CompletableFuture.supplyAsync(() -> ExceptionUtil.wrap(SQLException.class, () -> {
            final PreparedStatement statement = this.connection.prepareStatement("SELECT * FROM starboard WHERE original_author_id = ?;");
            statement.setLong(1, userId);
            return DatabaseRow.all(statement.executeQuery(), ArrayList::new);
        }, CompletionException::new), executorService);
    }

    public CompletableFuture<Collection<DatabaseRow>> getAllRows() {
        return CompletableFuture.supplyAsync(() -> ExceptionUtil.wrap(SQLException.class, () -> DatabaseRow.all(this.connection.prepareStatement("SELECT * FROM starboard;").executeQuery(), ArrayList::new), CompletionException::new), executorService);
    }

    public CompletableFuture<Boolean> userHasBoardEntry(final long userId) {
        return CompletableFuture.supplyAsync(() -> ExceptionUtil.wrap(SQLException.class, () -> {
            final PreparedStatement statement = this.connection.prepareStatement("SELECT * FROM starboard WHERE original_author_id = ? LIMIT 1;");
            statement.setLong(1, userId);
            return statement.executeQuery().next();
        }, CompletionException::new), executorService);
    }

    public CompletableFuture<Collection<DatabaseRow>> removeBoardEntriesInChannel(final long channelId) {
        return CompletableFuture.supplyAsync(() -> ExceptionUtil.wrap(SQLException.class, () -> {
            final PreparedStatement statement = this.connection.prepareStatement("DELETE FROM starboard WHERE original_channel_id = ? RETURNING *;");
            statement.setLong(1, channelId);
            return DatabaseRow.all(statement.executeQuery(), ArrayList::new);
        }, CompletionException::new), executorService);
    }

    public CompletableFuture<Void> removeAllBoardEntries() {
        return CompletableFuture.runAsync(() -> ExceptionUtil.wrap(SQLException.class, () -> this.connection.prepareStatement("DELETE FROM starboard;").executeUpdate(), CompletionException::new), executorService);
    }
}
