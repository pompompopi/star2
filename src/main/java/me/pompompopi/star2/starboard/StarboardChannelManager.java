package me.pompompopi.star2.starboard;

import me.pompompopi.star2.Star2;
import me.pompompopi.star2.config.Configuration;
import me.pompompopi.star2.database.DatabaseConnection;
import me.pompompopi.star2.database.DatabaseRow;
import me.pompompopi.star2.util.ExceptionUtil;
import me.pompompopi.star2.util.FuturePool;
import me.pompompopi.star2.util.Tuple;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public final class StarboardChannelManager {
    private final DatabaseConnection databaseConnection;
    private final TextChannel starboardChannel;
    private final String starRaw;

    public StarboardChannelManager(final JDA jda, final Configuration configuration, final DatabaseConnection databaseConnection) {
        this.databaseConnection = databaseConnection;
        this.starboardChannel = jda.getTextChannelById(configuration.getStarboardChannel());
        this.starRaw = configuration.getStarEmoji();
    }

    private CompletableFuture<Void> createEntry(final Message message, final short stars) {
        return CompletableFuture.runAsync(() -> starboardChannel.sendMessageEmbeds(createEmbed(message, stars)).queue(starboardMessage -> ExceptionUtil.handleExceptionAndLog(databaseConnection.addBoardEntry(message.getIdLong(), message.getChannelIdLong(), message.getAuthor().getIdLong(), starboardMessage.getIdLong(), stars), "create entry")));
    }

    private CompletableFuture<Void> updateEntry(final Message message, final short stars, final DatabaseRow databaseRow) {
        return CompletableFuture.runAsync(() -> {
            final long originalMessageId = message.getIdLong();
            if (stars != -1) {
                if (stars != databaseRow.stars())
                    databaseConnection.updateStars(originalMessageId, stars).join();
            }
            starboardChannel.editMessageEmbedsById(databaseRow.starboardMessageId(), createEmbed(message, stars == -1 ? databaseRow.stars() : stars)).queue();
        });
    }

    public CompletableFuture<Boolean> updateOrCreateEntry(final Message message, final short stars) {
        return updateOrCreateEntry0(message, stars, true);
    }

    public CompletableFuture<Boolean> updateWithoutCreatingEntry(final Message message, final short stars) {
        return updateOrCreateEntry0(message, stars, false);
    }

    private CompletableFuture<Boolean> updateOrCreateEntry0(final Message message, final short stars, final boolean create) {
        return CompletableFuture.supplyAsync(() -> {
            final Optional<DatabaseRow> databaseRowOpt = databaseConnection.getBoardEntry(message.getIdLong()).join();
            if (databaseRowOpt.isPresent()) {
                updateEntry(message, stars, databaseRowOpt.get()).join();
                return true;
            }
            if (!create)
                return false;

            createEntry(message, stars).join();
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
                final Message message = messageOpt.get();
                updateEntry(message, (short) -1, row);
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
                pool.poolAdd(row.toOriginalMessage(jda).thenAcceptAsync(messageOpt -> {
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
                    pool.poolAdd(updateEntry(message, stars, row));
                }));
            }
            pool.poolAdd(databaseConnection.updateStarsBulk(starUpdateList));
            pool.join();
        });
    }

    public CompletableFuture<Void> removeEntriesInChannel(final JDA jda, final long channelId) {
        return databaseConnection.removeBoardEntriesInChannel(channelId).thenAcceptAsync(databaseRows -> databaseRows.stream().map(row -> row.toStarboardMessage(jda, starboardChannel.getIdLong())).map(CompletableFuture::join).filter(Optional::isPresent).map(Optional::get).forEach(message -> message.delete().queue()));
    }

    public MessageEmbed createEmbed(final Message message, final short stars) {
        final User author = message.getAuthor();
        final EmbedBuilder builder = new EmbedBuilder()
                .setColor(0xFDD835)
                .setTimestamp(message.getTimeEdited() == null ? message.getTimeCreated() : message.getTimeEdited())
                .setAuthor(author.getName())
                .setThumbnail(author.getEffectiveAvatarUrl())
                .setFooter(stars + " " + starRaw)
                .setTitle("Jump to Message")
                .setUrl(message.getJumpUrl())
                .setDescription(message.getContentRaw().trim());

        final List<Message.Attachment> attachments = message.getAttachments();
        if (!attachments.isEmpty())
            builder.setImage(attachments.getFirst().getUrl());
        return builder.build();
    }
}