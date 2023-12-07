package me.pompompopi.star2;

import me.pompompopi.star2.config.Configuration;
import me.pompompopi.star2.database.DatabaseConnection;
import me.pompompopi.star2.starboard.StarboardChannelManager;
import me.pompompopi.star2.util.ExceptionUtil;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.MessageReaction;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.entities.emoji.UnicodeEmoji;
import net.dv8tion.jda.api.events.message.MessageBulkDeleteEvent;
import net.dv8tion.jda.api.events.message.MessageDeleteEvent;
import net.dv8tion.jda.api.events.message.MessageUpdateEvent;
import net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent;
import net.dv8tion.jda.api.events.message.react.MessageReactionRemoveAllEvent;
import net.dv8tion.jda.api.events.message.react.MessageReactionRemoveEmojiEvent;
import net.dv8tion.jda.api.events.message.react.MessageReactionRemoveEvent;
import net.dv8tion.jda.api.events.user.update.GenericUserUpdateEvent;
import net.dv8tion.jda.api.events.user.update.UserUpdateAvatarEvent;
import net.dv8tion.jda.api.events.user.update.UserUpdateNameEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;

public final class Star2 extends ListenerAdapter {
    public static final Logger LOGGER = LoggerFactory.getLogger("star2");
    private final DatabaseConnection databaseConnection;
    private final StarboardChannelManager starboardChannelManager;
    private final long starboardChannelId;
    private final int minimumStars;
    private final UnicodeEmoji starEmoji;

    Star2(final Configuration configuration) throws InterruptedException {
        try {
            this.databaseConnection = new DatabaseConnection(configuration);
        } catch (SQLException | ExecutionException e) {
            throw new IllegalStateException("Failed to connect to database", e);
        }
        Runtime.getRuntime().addShutdownHook(new Thread(databaseConnection::shutdown));
        final JDA jda = JDABuilder.create(configuration.getToken(), GatewayIntent.GUILD_MESSAGE_REACTIONS, GatewayIntent.GUILD_MESSAGES, GatewayIntent.GUILD_MEMBERS, GatewayIntent.MESSAGE_CONTENT)
                .addEventListeners(this)
                .build();
        jda.awaitReady();
        this.databaseConnection.performMigration(jda);
        this.starboardChannelManager = new StarboardChannelManager(jda, configuration, databaseConnection);
        this.starboardChannelId = configuration.getStarboardChannel();
        this.minimumStars = configuration.getMinimumReactions();
        this.starEmoji = Emoji.fromUnicode(configuration.getStarEmoji());
    }

    public static void main(final String[] args) throws InterruptedException {
        new Star2(new Configuration());
    }

    private boolean isNotStar(final Emoji emoji) {
        return emoji.getType() != Emoji.Type.UNICODE || !emoji.equals(starEmoji);
    }

    @Override
    public void onMessageReactionAdd(final MessageReactionAddEvent event) {
        if (starboardChannelId == event.getChannel().getIdLong())
            return;
        if (isNotStar(event.getEmoji()))
            return;
        event.retrieveMessage().queue(message -> {
            final MessageReaction reaction = message.getReaction(starEmoji);
            if (reaction == null) {
                LOGGER.warn("Reaction null while in the message reaction add event");
                return;
            }
            final int starCount = reaction.getCount();
            if (starCount < minimumStars)
                return;

            ExceptionUtil.handleException(this.starboardChannelManager.updateOrCreateEntry(message, (short) starCount), e -> LOGGER.error("Exception occurred while updating starboard entry in message reaction add", e));
        }, e -> LOGGER.warn("Failed to process message reaction add", e));
    }

    @Override
    public void onMessageReactionRemove(final MessageReactionRemoveEvent event) {
        if (starboardChannelId == event.getChannel().getIdLong())
            return;
        if (isNotStar(event.getEmoji()))
            return;
        event.retrieveMessage().queue(message -> {
            final MessageReaction messageReaction = message.getReaction(starEmoji);
            final int starCount = messageReaction == null ? 0 : messageReaction.getCount();
            if (starCount >= minimumStars)
                return;
            ExceptionUtil.handleException(this.starboardChannelManager.removeEntry(event.getMessageIdLong()), e -> LOGGER.error("Exception occurred while removing starboard entry in message reaction remove", e));
        }, e -> LOGGER.warn("Failed to process message reaction remove", e));
    }

    @Override
    public void onMessageReactionRemoveAll(final MessageReactionRemoveAllEvent event) {
        if (!event.isFromGuild())
            return;
        if (starboardChannelId == event.getChannel().getIdLong())
            return;
        ExceptionUtil.handleExceptionAndLog(starboardChannelManager.removeEntry(event.getMessageIdLong()), "message reaction remove all event handler");
    }

    @Override
    public void onMessageReactionRemoveEmoji(final MessageReactionRemoveEmojiEvent event) {
        if (starboardChannelId == event.getChannel().getIdLong())
            return;
        final Emoji emoji = event.getEmoji();
        if (emoji.getType() != Emoji.Type.UNICODE)
            return;
        if (!emoji.equals(starEmoji))
            return;
        ExceptionUtil.handleExceptionAndLog(starboardChannelManager.removeEntry(event.getMessageIdLong()), "message reaction remove emoji event handler");
    }

    @Override
    public void onMessageUpdate(final MessageUpdateEvent event) {
        if (starboardChannelId == event.getChannel().getIdLong())
            return;
        ExceptionUtil.handleExceptionAndLog(starboardChannelManager.updateWithoutCreatingEntry(event.getMessage(), (short) -1), "message update event handler");
    }

    @Override
    public void onMessageDelete(final MessageDeleteEvent event) {
        final long messageId = event.getMessageIdLong();
        if (starboardChannelId == event.getChannel().getIdLong()) {
            ExceptionUtil.handleExceptionAndLog(databaseConnection.removeBoardEntry(messageId), "message delete event handler (message in starboard channel)");
            return;
        }

        ExceptionUtil.handleExceptionAndLog(starboardChannelManager.removeEntry(messageId), "message delete event handler");
    }

    @Override
    public void onMessageBulkDelete(final MessageBulkDeleteEvent event) {
        final Function<Long, CompletableFuture<?>> messageIdFunc = event.getChannel().getIdLong() == starboardChannelId ? databaseConnection::removeBoardEntry : starboardChannelManager::removeEntry;
        ExceptionUtil.handleExceptionAndLog(CompletableFuture.allOf(event.getMessageIds().stream().map(Long::parseUnsignedLong).map(messageIdFunc).toArray(CompletableFuture[]::new)), "message bulk delete event handler");
    }

    private void onDisplayedUserInfoUpdate(final GenericUserUpdateEvent<?> event) {
        final User user = event.getUser();
        if (user.isBot())
            return;
        final long userId = user.getIdLong();
        ExceptionUtil.handleExceptionAndLog(databaseConnection.userHasBoardEntry(userId).thenAcceptAsync(hasBoardEntry -> {
            if (!hasBoardEntry)
                return;
            starboardChannelManager.updateEveryUserEntry(event.getJDA(), userId);
        }), "displayed user info update");
    }

    @Override
    public void onUserUpdateAvatar(@NotNull final UserUpdateAvatarEvent event) {
        onDisplayedUserInfoUpdate(event);
    }

    @Override
    public void onUserUpdateName(@NotNull final UserUpdateNameEvent event) {
        onDisplayedUserInfoUpdate(event);
    }
}