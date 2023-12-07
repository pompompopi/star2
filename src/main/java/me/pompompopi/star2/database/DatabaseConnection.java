package me.pompompopi.star2.database;

import me.pompompopi.star2.config.Configuration;
import me.pompompopi.star2.util.ExceptionUtil;

import java.sql.*;
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
        this.executorService.submit(() -> this.connection.prepareStatement("CREATE TABLE IF NOT EXISTS starboard (original_message_id bigint NOT NULL PRIMARY KEY, original_channel_id bigint NOT NULL, starboard_message_id bigint NOT NULL UNIQUE, stars smallint NOT NULL);").executeUpdate()).get();
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

    public CompletableFuture<Void> addBoardEntry(final long originalMessageId, final long originalChannelId, final long starboardMessageId, final short stars) {
        return CompletableFuture.runAsync(() -> ExceptionUtil.wrap(SQLException.class, () -> {
            final PreparedStatement statement = this.connection.prepareStatement("INSERT INTO starboard (original_message_id, original_channel_id, starboard_message_id, stars) VALUES (?, ?, ?, ?);");
            statement.setLong(1, originalMessageId);
            statement.setLong(2, originalChannelId);
            statement.setLong(3, starboardMessageId);
            statement.setShort(4, stars);
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
}
