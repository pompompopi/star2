package me.pompompopi.star2;

import me.pompompopi.star2.config.Configuration;
import me.pompompopi.star2.database.DatabaseConnection;
import me.pompompopi.star2.starboard.StarboardChannelManager;
import me.pompompopi.star2.util.ExceptionUtil;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageReaction;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.entities.emoji.UnicodeEmoji;
import net.dv8tion.jda.api.events.message.MessageBulkDeleteEvent;
import net.dv8tion.jda.api.events.message.MessageDeleteEvent;
import net.dv8tion.jda.api.events.message.MessageUpdateEvent;
import net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent;
import net.dv8tion.jda.api.events.message.react.MessageReactionRemoveAllEvent;
import net.dv8tion.jda.api.events.message.react.MessageReactionRemoveEmojiEvent;
import net.dv8tion.jda.api.events.message.react.MessageReactionRemoveEvent;
import net.dv8tion.jda.api.exceptions.RateLimitedException;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.function.Consumer;

public final class Star2 extends ListenerAdapter {
    public static final Logger LOGGER = LoggerFactory.getLogger("star2");
    private final DatabaseConnection databaseConnection;
    private final StarboardChannelManager starboardChannelManager;
    private final long starboardChannelId;
    private final int minimumStars;
    private final UnicodeEmoji starEmoji;
    private final JDA jda;

    Star2(final Configuration configuration) throws InterruptedException {
        try {
            this.databaseConnection = new DatabaseConnection(configuration);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to connect to database", e);
        }
        Runtime.getRuntime().addShutdownHook(new Thread(databaseConnection::shutdown));
        this.jda = JDABuilder.create(configuration.getToken(), GatewayIntent.GUILD_MESSAGE_REACTIONS, GatewayIntent.GUILD_MESSAGES, GatewayIntent.MESSAGE_CONTENT)
                .addEventListeners(this)
                .build();
        jda.awaitReady();
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
        final Message message;
        try {
            message = event.retrieveMessage().complete(true);
        } catch (RateLimitedException e) {
            LOGGER.warn("Ratelimited", e);
            return;
        }
        final MessageReaction reaction = message.getReaction(starEmoji);
        if (reaction == null) {
            LOGGER.warn("Reaction null while in the message reaction add event");
            return;
        }
        final int starCount = reaction.getCount();
        if (starCount < minimumStars)
            return;

        try {
            this.starboardChannelManager.updateOrCreateEntry(message, (short) starCount);
        } catch (RateLimitedException | SQLException e) {
            throw new RuntimeException("Failed to add starboard entry", e);
        }
    }

    @Override
    public void onMessageReactionRemove(final MessageReactionRemoveEvent event) {
        if (starboardChannelId == event.getChannel().getIdLong())
            return;
        if (isNotStar(event.getEmoji()))
            return;
        final Message message;
        try {
            message = event.retrieveMessage().complete(true);
        } catch (RateLimitedException e) {
            LOGGER.warn("Ratelimited", e);
            return;
        }
        final MessageReaction messageReaction = message.getReaction(starEmoji);
        final int starCount = messageReaction == null ? 0 : messageReaction.getCount();
        if (starCount >= minimumStars)
            return;
        try {
            this.starboardChannelManager.removeEntry(event.getMessageIdLong());
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to remove starboard entry", e);
        } catch (RateLimitedException e) {
            LOGGER.warn("Ratelimited", e);
        }
    }

    @Override
    public void onMessageReactionRemoveAll(final MessageReactionRemoveAllEvent event) {
        if (!event.isFromGuild())
            return;
        if (starboardChannelId == event.getChannel().getIdLong())
            return;
        try {
            starboardChannelManager.removeEntry(event.getMessageIdLong());
        } catch (RateLimitedException e) {
            LOGGER.warn("Ratelimited", e);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to remove starboard entry", e);
        }
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
        try {
            starboardChannelManager.removeEntry(event.getMessageIdLong());
        } catch (RateLimitedException e) {
            LOGGER.warn("Ratelimited", e);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to remove starboard entry", e);
        }
    }

    @Override
    public void onMessageUpdate(final MessageUpdateEvent event) {
        if (starboardChannelId == event.getChannel().getIdLong())
            return;
        try {
            starboardChannelManager.updateWithoutCreatingEntry(event.getMessage(), (short) -1);
        } catch (RateLimitedException e) {
            LOGGER.warn("Ratelimited", e);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to remove starboard entry", e);
        }
    }

    @Override
    public void onMessageDelete(final MessageDeleteEvent event) {
        final long messageId = event.getMessageIdLong();
        if (starboardChannelId == event.getChannel().getIdLong()) {
            try {
                databaseConnection.removeBoardEntry(messageId);
            } catch (SQLException e) {
                throw new RuntimeException("Failed to remove starboard entry", e);
            }
            return;
        }
        try {
            starboardChannelManager.removeEntry(messageId);
        } catch (RateLimitedException e) {
            LOGGER.warn("Ratelimited");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to remove starboard entry", e);
        }
    }

    @Override
    public void onMessageBulkDelete(final MessageBulkDeleteEvent event) {
        final Consumer<Long> messageIdFunc;
        if (event.getChannel().getIdLong() == starboardChannelId) {
            messageIdFunc = (messageId) -> ExceptionUtil.wrap(SQLException.class, () -> databaseConnection.removeBoardEntry(messageId), e -> new IllegalStateException("Failed to remove starboard entry from database", e));
        } else {
            messageIdFunc = (messageId) -> ExceptionUtil.wrapMultiple(() -> starboardChannelManager.removeEntry(messageId), e -> new IllegalStateException("Failed to remove starboard entry", e), SQLException.class, RateLimitedException.class);
        }
        event.getMessageIds().stream().map(Long::parseUnsignedLong).forEach(messageIdFunc);
    }
}