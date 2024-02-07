package me.pompompopi.star2.database;

import me.pompompopi.star2.util.ExceptionUtil;
import me.pompompopi.star2.util.NullableUtil;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.exceptions.RateLimitedException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

public record DatabaseRow(long originalMessageId, long originalChannelId, long originalAuthorId,
                          long starboardMessageId, Optional<Long> referencedMessageId,
                          Optional<Long> referencedAuthorId, short stars) {
    public DatabaseRow(final ResultSet resultSet) throws SQLException {
        this(
                resultSet.getLong("original_message_id"),
                resultSet.getLong("original_channel_id"),
                resultSet.getLong("original_author_id"),
                resultSet.getLong("starboard_message_id"),
                NullableUtil.mapToPossiblyEmpty(resultSet.getLong("referenced_message_id"), l -> l == 0),
                NullableUtil.mapToPossiblyEmpty(resultSet.getLong("referenced_author_id"), l -> l == 0),
                resultSet.getShort("stars")
        );
    }

    public static Collection<DatabaseRow> all(final ResultSet resultSet, final Supplier<Collection<DatabaseRow>> collectionSupplier) throws SQLException {
        final Collection<DatabaseRow> databaseRows = collectionSupplier.get();
        while (resultSet.next())
            databaseRows.add(new DatabaseRow(resultSet));
        return databaseRows;
    }

    public CompletableFuture<Optional<Message>> toStarboardMessage(final JDA jda, final long starboardChannelId) {
        return CompletableFuture.supplyAsync(() -> ExceptionUtil.wrap(RateLimitedException.class, () -> {
            final TextChannel textChannel = jda.getTextChannelById(starboardChannelId);
            if (textChannel == null)
                return Optional.empty();
            return Optional.of(textChannel.retrieveMessageById(starboardMessageId).complete(true));
        }, CompletionException::new));
    }

    public CompletableFuture<Optional<Message>> toOriginalMessage(final JDA jda) {
        return CompletableFuture.supplyAsync(() -> ExceptionUtil.wrap(RateLimitedException.class, () -> {
            final TextChannel textChannel = jda.getTextChannelById(originalChannelId);
            if (textChannel == null)
                return Optional.empty();
            return Optional.of(textChannel.retrieveMessageById(originalMessageId).complete(true));
        }, CompletionException::new));
    }

    public CompletableFuture<Optional<Message>> toReferencedMessage(final JDA jda) {
        return CompletableFuture.supplyAsync(() -> ExceptionUtil.wrap(RateLimitedException.class, () -> {
            if (referencedMessageId.isEmpty())
                return Optional.empty();
            final TextChannel textChannel = jda.getTextChannelById(originalChannelId);
            if (textChannel == null)
                return Optional.empty();
            return Optional.of(textChannel.retrieveMessageById(referencedMessageId.get()).complete(true));
        }, CompletionException::new));
    }
}
