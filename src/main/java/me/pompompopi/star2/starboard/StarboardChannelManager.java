package me.pompompopi.star2.starboard;

import me.pompompopi.star2.Star2;
import me.pompompopi.star2.config.Configuration;
import me.pompompopi.star2.database.DatabaseConnection;
import me.pompompopi.star2.database.DatabaseRow;
import me.pompompopi.star2.util.ExceptionUtil;
import me.pompompopi.star2.util.FuturePool;
import me.pompompopi.star2.util.NullableUtil;
import me.pompompopi.star2.util.Tuple;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.ISnowflake;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

public final class StarboardChannelManager {
    private final DatabaseConnection databaseConnection;
    private final TextChannel starboardChannel;
    private final String starRaw;

    public StarboardChannelManager(final JDA jda, final Configuration configuration, final DatabaseConnection databaseConnection) {
        this.databaseConnection = databaseConnection;
        this.starboardChannel = jda.getTextChannelById(configuration.getStarboardChannel());
        this.starRaw = configuration.getStarEmoji();
    }

    private CompletableFuture<Void> createEntry(final Message message, final @Nullable Message referencedMessage, final short stars) {
        return CompletableFuture.runAsync(() -> starboardChannel.sendMessageEmbeds(createEmbed(message, referencedMessage, stars)).queue(starboardMessage -> ExceptionUtil.handleExceptionAndLog(databaseConnection.addBoardEntry(message.getIdLong(), message.getChannelIdLong(), message.getAuthor().getIdLong(), starboardMessage.getIdLong(), NullableUtil.mapFromPossiblyNull(referencedMessage, ISnowflake::getIdLong), NullableUtil.mapFromPossiblyNull(referencedMessage, referencedMessageAct -> referencedMessageAct.getAuthor().getIdLong()), stars), "create entry")));
    }

    private CompletableFuture<Void> updateEntry(final Message message, final @Nullable Message referencedMessage, final short stars, final DatabaseRow databaseRow) {
        return CompletableFuture.runAsync(() -> {
            final long originalMessageId = message.getIdLong();
            if (stars != -1 && (stars != databaseRow.stars()))
                databaseConnection.updateStars(originalMessageId, stars).join();
            starboardChannel.editMessageEmbedsById(databaseRow.starboardMessageId(), createEmbed(message, referencedMessage, stars == -1 ? databaseRow.stars() : stars)).queue();
        });
    }

    public CompletableFuture<Boolean> updateOrCreateEntry(final Message message, final @Nullable Message referencedMessage, final short stars) {
        return updateOrCreateEntry0(message, referencedMessage, stars, true);
    }

    public CompletableFuture<Boolean> updateWithoutCreatingEntry(final Message message, final @Nullable Message referencedMessage, final short stars) {
        return updateOrCreateEntry0(message, referencedMessage, stars, false);
    }

    private CompletableFuture<Boolean> updateOrCreateEntry0(final Message message, final @Nullable Message referencedMessage, final short stars, final boolean create) {
        return CompletableFuture.supplyAsync(() -> {
            final Optional<DatabaseRow> databaseRowOpt = databaseConnection.getBoardEntry(message.getIdLong()).join();
            if (databaseRowOpt.isPresent()) {
                updateEntry(message, referencedMessage, stars, databaseRowOpt.get()).join();
                return true;
            }
            if (!create) {
                final Collection<DatabaseRow> inReferenceTo = databaseConnection.getStarboardsInReferenceTo(message.getIdLong()).join();
                if (inReferenceTo.isEmpty())
                    return false;
                final JDA jda = message.getJDA();
                final FuturePool pool = new FuturePool();
                for (final DatabaseRow databaseRow : inReferenceTo) {
                    pool.poolRun(() -> databaseRow.toOriginalMessage(jda).thenAcceptAsync(originalMessageOpt -> {
                        if (originalMessageOpt.isEmpty())
                            return;
                        updateEntry(originalMessageOpt.get(), message, databaseRow.stars(), databaseRow).join();
                    }));
                }
                pool.join();
                return true;
            }

            createEntry(message, referencedMessage, stars).join();
            return true;
        });
    }

    public CompletableFuture<Boolean> removeEntry(final long originalMessageId) {
        return CompletableFuture.supplyAsync(() -> {
            final Optional<DatabaseRow> databaseRowOpt = databaseConnection.removeBoardEntry(originalMessageId).join();
            if (databaseRowOpt.isEmpty())
                return false;
            final DatabaseRow databaseRow = databaseRowOpt.get();
            starboardChannel.deleteMessageById(databaseRow.starboardMessageId()).queue();
            return true;
        });
    }

    public CompletableFuture<Void> updateEveryUserEntry(final JDA jda, final long userId) {
        return databaseConnection.getUserBoardEntries(userId).thenAcceptAsync(rows -> {
            for (final DatabaseRow row : rows) {
                final Optional<Message> messageOpt = row.toOriginalMessage(jda).join();
                if (messageOpt.isEmpty())
                    continue;
                final Optional<Message> referencedMessageOpt = row.toReferencedMessage(jda).join();
                final Message message = messageOpt.get();
                updateEntry(message, referencedMessageOpt.orElse(null), (short) -1, row);
            }
        });
    }

    public CompletableFuture<Void> recalculateEveryEntry(final JDA jda, final Star2 star2, final boolean redo) {
        return databaseConnection.getAllRows().thenAcceptAsync(rows -> {
            final int minimumStars = star2.getMinimumStars();
            final FuturePool pool = new FuturePool();
            final List<Tuple<Short, Long>> starUpdateList = new ArrayList<>();
            for (final DatabaseRow row : rows) {
                final long originalMessageId = row.originalMessageId();
                pool.poolAdd(row.toOriginalMessage(jda).thenCombineAsync(row.toReferencedMessage(jda), Tuple::new).thenAcceptAsync(messageTup -> {
                    final Optional<Message> messageOpt = messageTup.first();
                    if (messageOpt.isEmpty()) {
                        removeEntry(originalMessageId);
                        return;
                    }
                    final Message message = messageOpt.get();
                    final short stars = (short) (long) star2.countStarsExcludingAuthor(message).join();
                    if (stars < minimumStars) {
                        removeEntry(originalMessageId);
                        return;
                    }
                    if (stars == row.stars() && !redo)
                        return;
                    starUpdateList.add(new Tuple<>(stars, originalMessageId));
                    pool.poolAdd(updateEntry(message, messageTup.second().orElse(null), stars, row));
                }));
            }
            pool.poolAdd(databaseConnection.updateStarsBulk(starUpdateList));
            pool.join();
        });
    }

    public CompletableFuture<Void> removeEntriesInChannel(final JDA jda, final long channelId) {
        return databaseConnection.removeBoardEntriesInChannel(channelId).thenAcceptAsync(databaseRows -> databaseRows.stream().map(row -> row.toStarboardMessage(jda, starboardChannel.getIdLong())).map(CompletableFuture::join).filter(Optional::isPresent).map(Optional::get).forEach(message -> message.delete().queue()));
    }

    private MessageEmbed createEmbedFromMessage(final Message message, final String footer, final int color) {
        final User author = message.getAuthor();
        final EmbedBuilder embedBuilder = new EmbedBuilder()
                .setColor(color)
                .setTimestamp(message.getTimeEdited() == null ? message.getTimeCreated() : message.getTimeEdited())
                .setAuthor(author.getName())
                .setThumbnail(author.getEffectiveAvatarUrl())
                .setTitle("Jump to Message")
                .setUrl(message.getJumpUrl())
                .setFooter(footer)
                .setDescription(message.getContentRaw().trim());

        final List<Message.Attachment> attachments = message.getAttachments();
        if (!attachments.isEmpty())
            embedBuilder.setImage(attachments.getFirst().getUrl());
        return embedBuilder.build();
    }

    public Collection<MessageEmbed> createEmbed(final Message message, final @Nullable Message referencedMessage, final short stars) {
        return Stream.of(NullableUtil.mapFromPossiblyNull(referencedMessage, referencedMessageAct -> createEmbedFromMessage(referencedMessageAct, "Original Message", 0xE3E5E8)),
                        createEmbedFromMessage(message, stars + " " + starRaw, 0xFDD835))
                .filter(Objects::nonNull)
                .toList();
    }
}